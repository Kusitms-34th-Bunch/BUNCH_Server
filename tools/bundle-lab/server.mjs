#!/usr/bin/env node

import { createReadStream, promises as fs } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { createConnection } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const moduleDir = path.dirname(fileURLToPath(import.meta.url));
const staticRoot = path.resolve(process.env.BUNDLE_LAB_STATIC_ROOT ?? moduleDir);
const labPort = positivePort(process.env.BUNDLE_LAB_PORT, 3000);
const backendPort = positivePort(process.env.BUNDLE_LAB_BACKEND_PORT, 18080);
const labOrigin = process.env.BUNDLE_LAB_ORIGIN ?? `http://localhost:${labPort}`;
const fixturePipe = process.env.BUNDLE_LAB_SEED_PIPE === '1';
const automergeRoot = path.resolve(process.env.BUNDLE_LAB_AUTOMERGE_ROOT ?? path.join(moduleDir, 'node_modules/@automerge/automerge/dist/mjs'));
const maxFixtureInputBytes = 64 * 1024;

const staticFiles = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/app.mjs', ['app.mjs', 'text/javascript; charset=utf-8']],
  ['/protocol.mjs', ['protocol.mjs', 'text/javascript; charset=utf-8']],
  ['/interaction.mjs', ['interaction.mjs', 'text/javascript; charset=utf-8']],
  ['/style.css', ['style.css', 'text/css; charset=utf-8']],
]);

const hopByHopHeaders = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]);

function positivePort(value, fallback) {
  const port = Number(value ?? fallback);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('invalid port');
  }
  return port;
}

function isLoopbackAddress(address) {
  if (!address) return false;
  const normalized = address.replace(/^::ffff:/i, '').replace(/%.*$/, '');
  return normalized === '::1' || normalized === '127.0.0.1' || normalized.startsWith('127.');
}

function isLoopbackHost(host) {
  if (typeof host !== 'string') return false;
  const normalized = host.trim().toLowerCase();
  return new Set([
    `localhost:${labPort}`,
    `127.0.0.1:${labPort}`,
    `[::1]:${labPort}`,
  ]).has(normalized);
}

function isLocalRequest(request) {
  return request.headers.origin === labOrigin &&
    isLoopbackHost(request.headers.host) &&
    isLoopbackAddress(request.socket.remoteAddress);
}

function requestUrl(request) {
  try {
    return new URL(request.url ?? '/', labOrigin);
  } catch {
    return null;
  }
}

function writeText(response, status, body, headers = {}) {
  if (response.writableEnded) return;
  response.writeHead(status, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    ...headers,
  });
  response.end(body);
}

function writeJson(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store, no-cache, max-age=0',
    'Pragma': 'no-cache',
    'Expires': '0',
  });
  response.end(body);
}

function fixtureShape(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid fixture');
  if (!Number.isSafeInteger(value.bunchId) || value.bunchId < 1) throw new Error('invalid fixture');
  if (!Number.isSafeInteger(value.bundleId) || value.bundleId < 1) throw new Error('invalid fixture');
  if (!value.clients || typeof value.clients !== 'object') throw new Error('invalid fixture');

  const clients = {};
  for (const name of ['A', 'B']) {
    const client = value.clients[name];
    if (!client || !Number.isSafeInteger(client.memberId) || client.memberId < 1) {
      throw new Error('invalid fixture');
    }
    if (typeof client.token !== 'string' || client.token.length < 32) throw new Error('invalid fixture');
    clients[name] = Object.freeze({token: client.token, memberId: client.memberId});
  }

  return Object.freeze({
    bunchId: value.bunchId,
    bundleId: value.bundleId,
    clients: Object.freeze(clients),
  });
}

async function readFixture() {
  const configured = process.env.BUNDLE_LAB_FIXTURE_JSON;
  if (configured) return fixtureShape(JSON.parse(configured));
  if (!fixturePipe && process.stdin.isTTY) return null;

  const chunks = [];
  let size = 0;
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > maxFixtureInputBytes) throw new Error('fixture is too large');
    chunks.push(chunk);
  }
  if (size === 0) {
    if (fixturePipe) throw new Error('fixture pipe was empty');
    return null;
  }
  return fixtureShape(JSON.parse(Buffer.concat(chunks).toString('utf8')));
}

function proxyHeaders(request) {
  const headers = {};
  for (const [name, value] of Object.entries(request.headers)) {
    if (!hopByHopHeaders.has(name)) headers[name] = value;
  }
  headers.host = `127.0.0.1:${backendPort}`;
  return headers;
}

function proxyApi(request, response) {
  const upstream = httpRequest({
    hostname: '127.0.0.1',
    port: backendPort,
    method: request.method,
    path: request.url,
    headers: proxyHeaders(request),
  }, upstreamResponse => {
    const headers = {};
    for (const [name, value] of Object.entries(upstreamResponse.headers)) {
      if (!hopByHopHeaders.has(name)) headers[name] = value;
    }
    response.writeHead(upstreamResponse.statusCode ?? 502, headers);
    upstreamResponse.pipe(response);
  });

  upstream.on('error', () => {
    if (!response.headersSent) writeText(response, 502, 'Local backend unavailable\n');
    else response.destroy();
  });
  request.on('aborted', () => upstream.destroy());
  request.pipe(upstream);
}

function rejectUpgrade(socket, status) {
  if (socket.destroyed) return;
  socket.end(
    `HTTP/1.1 ${status} Local WebSocket Rejected\r\n` +
    'Connection: close\r\n' +
    'Content-Length: 0\r\n\r\n'
  );
}

function proxyWebSocket(request, clientSocket, clientHead) {
  const url = requestUrl(request);
  const connection = String(request.headers.connection ?? '').toLowerCase();
  const upgrade = String(request.headers.upgrade ?? '').toLowerCase();
  if (
    request.method !== 'GET' ||
    !url ||
    url.pathname !== '/ws' ||
    url.search ||
    !isLocalRequest(request) ||
    !connection.split(',').map(value => value.trim()).includes('upgrade') ||
    upgrade !== 'websocket'
  ) {
    rejectUpgrade(clientSocket, 403);
    return;
  }

  const backendSocket = createConnection({host: '127.0.0.1', port: backendPort});
  let connected = false;
  backendSocket.setNoDelay(true);

  backendSocket.once('connect', () => {
    connected = true;
    const lines = [`${request.method} ${request.url} HTTP/${request.httpVersion}`];
    for (const [name, value] of Object.entries(request.headers)) {
      if (['host', 'connection', 'upgrade'].includes(name)) continue;
      if (value === undefined) continue;
      const values = Array.isArray(value) ? value : [value];
      for (const item of values) lines.push(`${name}: ${item}`);
    }
    lines.push(`Host: 127.0.0.1:${backendPort}`, 'Connection: Upgrade', 'Upgrade: websocket');
    backendSocket.write(`${lines.join('\r\n')}\r\n\r\n`);
    if (clientHead.length) backendSocket.write(clientHead);
    clientSocket.pipe(backendSocket);
    backendSocket.pipe(clientSocket);
  });

  backendSocket.on('error', () => {
    if (!connected) rejectUpgrade(clientSocket, 502);
    else clientSocket.destroy();
  });
  backendSocket.on('close', () => clientSocket.destroy());
  clientSocket.on('error', () => backendSocket.destroy());
  clientSocket.on('close', () => backendSocket.destroy());
}

async function serveStatic(request, response, url) {
  if (!['GET', 'HEAD'].includes(request.method)) {
    writeText(response, 405, 'Method Not Allowed\n', {Allow: 'GET, HEAD'});
    return;
  }
  const vendorPrefix = '/vendor/automerge/';
  const vendor = url.pathname.startsWith(vendorPrefix);
  const vendorName = vendor ? url.pathname.slice(vendorPrefix.length) : '';
  const entry = vendor && /^[a-zA-Z0-9_/.-]+\.js$/.test(vendorName)
    ? [vendorName, 'text/javascript; charset=utf-8'] : staticFiles.get(url.pathname);
  if (!entry) {
    writeText(response, 404, 'Not Found\n');
    return;
  }
  const [fileName, contentType] = entry;
  const root = vendor ? automergeRoot : staticRoot;
  const filePath = path.resolve(root, fileName);
  if (filePath !== root && !filePath.startsWith(`${root}${path.sep}`)) {
    writeText(response, 404, 'Not Found\n');
    return;
  }
  let stat;
  try {
    stat = await fs.stat(filePath);
  } catch {
    writeText(response, 404, 'Not Found\n');
    return;
  }
  if (!stat.isFile()) {
    writeText(response, 404, 'Not Found\n');
    return;
  }
  response.writeHead(200, {
    'Content-Type': contentType,
    'Content-Length': stat.size,
    'Cache-Control': 'no-store',
  });
  if (request.method === 'HEAD') response.end();
  else createReadStream(filePath).pipe(response);
}

async function handleRequest(request, response, fixture) {
  const url = requestUrl(request);
  if (!url) {
    request.resume();
    writeText(response, 400, 'Bad Request\n');
    return;
  }

  if (url.pathname === '/__lab/fixture') {
    request.resume();
    if (!isLocalRequest(request)) {
      writeText(response, 403, 'Local fixture only\n');
      return;
    }
    if (request.method !== 'POST') {
      writeText(response, 405, 'Method Not Allowed\n', {Allow: 'POST'});
      return;
    }
    const contentLength = Number(request.headers['content-length'] ?? 0);
    if (Number.isFinite(contentLength) && contentLength > 4096) {
      writeText(response, 413, 'Request body too large\n');
      return;
    }
    if (!fixture) {
      writeText(response, 503, 'Local fixture is not ready\n');
      return;
    }
    writeJson(response, 200, fixture);
    return;
  }

  if (url.pathname === '/api/v1' || url.pathname.startsWith('/api/v1/')) {
    proxyApi(request, response);
    return;
  }

  await serveStatic(request, response, url);
}

async function main() {
  const fixture = await readFixture();
  const server = createServer((request, response) => {
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('X-Frame-Options', 'DENY');
    void handleRequest(request, response, fixture).catch(() => {
      if (!response.headersSent) writeText(response, 500, 'Local server error\n');
      else response.destroy();
    });
  });

  server.on('upgrade', (request, socket, head) => proxyWebSocket(request, socket, head));
  server.on('clientError', (_error, socket) => {
    if (!socket.destroyed) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
  });

  const sockets = new Set();
  server.on('connection', socket => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  const shutdown = () => {
    for (const socket of sockets) socket.destroy();
    server.close(() => process.exit(0));
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen({host: '127.0.0.1', port: labPort}, resolve);
  });
  console.log(`[bundle-lab] http://${new URL(labOrigin).hostname}:${labPort}`);
}

main().catch(() => {
  console.error('[bundle-lab] startup failed');
  process.exitCode = 1;
});
