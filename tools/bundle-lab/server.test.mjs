import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createServer, connect} from 'node:net';
import {once} from 'node:events';
import {request} from 'node:http';

test('local fixture denies foreign Origin/Host, hides source and shuts down open sockets', async () => {
  const reservation = createServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const origin = `http://localhost:${port}`;
  const fake = {bunchId:1, bundleId:2, clients:{
    A:{memberId:11, token:'unit-test-placeholder-aaaaaaaaaaaaaaaa'},
    B:{memberId:12, token:'unit-test-placeholder-bbbbbbbbbbbbbbbb'}
  }};
  const child = spawn(process.execPath, [new URL('./server.mjs', import.meta.url).pathname], {
    env:{...process.env, BUNDLE_LAB_PORT:String(port), BUNDLE_LAB_ORIGIN:origin,
      BUNDLE_LAB_FIXTURE_JSON:JSON.stringify(fake)}, stdio:['ignore','pipe','pipe']
  });
  try {
    await Promise.race([
      once(child.stdout, 'data'),
      new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error('server startup timeout')), 4000); timer.unref(); })
    ]);
    const base = `http://127.0.0.1:${port}`;
    const headers = {Origin:origin, Host:`localhost:${port}`};
    assert.equal((await fetch(`${base}/__lab/fixture`, {method:'POST'})).status, 403);
    assert.equal((await fetch(`${base}/__lab/fixture`, {method:'POST', headers:{...headers, Origin:'https://foreign.invalid'}})).status, 403);
    const foreignHostStatus = await new Promise((resolve, reject) => {
      const req = request(`${base}/__lab/fixture`, {method:'POST', headers:{...headers, Host:`foreign.invalid:${port}`}}, res => {
        res.resume(); resolve(res.statusCode);
      });
      req.on('error', reject); req.end();
    });
    assert.equal(foreignHostStatus, 403);
    const response = await fetch(`${base}/__lab/fixture`, {method:'POST', headers});
    assert.equal(response.status, 200);
    assert.match(response.headers.get('cache-control'), /no-store/);
    assert.deepEqual(await response.json(), fake);
    assert.equal((await fetch(`${base}/seed.py`)).status, 404);
    assert.equal((await fetch(`${base}/package.json`)).status, 404);
    assert.equal((await fetch(`${base}/vendor/automerge/../../seed.py`)).status, 404);
    assert.equal((await fetch(`${base}/server.mjs`)).status, 404);
    assert.equal((await fetch(`${base}/actuator/health`)).status, 404);
    const socket = connect(port, '127.0.0.1');
    await once(socket, 'connect');
    const exited = once(child, 'exit');
    child.kill('SIGTERM');
    const [code] = await Promise.race([
      exited,
      new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error('shutdown hung with active socket')), 2000); timer.unref(); })
    ]);
    assert.equal(code, 0);
    socket.destroy();
  } finally { if (child.exitCode === null) child.kill('SIGKILL'); }
});
