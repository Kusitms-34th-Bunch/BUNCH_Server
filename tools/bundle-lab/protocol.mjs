// Minimal STOMP 1.2 text transport. No debug hooks: CONNECT contains a credential.
export function frame(command, headers = {}, body = '') {
  const escape = value => String(value).replace(/\\/g, '\\\\').replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n').replace(/:/g, '\\c');
  const lines = Object.entries(headers).map(([key, value]) =>
    `${escape(key)}:${command === 'CONNECT' ? value : escape(value)}`);
  if (body) lines.push(`content-length:${new TextEncoder().encode(body).length}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

export class Frames {
  buffer = new Uint8Array();
  push(text) {
    const bytes = new TextEncoder().encode(text);
    const combined = new Uint8Array(this.buffer.length + bytes.length);
    combined.set(this.buffer);
    combined.set(bytes, this.buffer.length);
    this.buffer = combined;
    const result = [];
    const decoder = new TextDecoder();
    while (this.buffer.length) {
      while ([10, 13].includes(this.buffer[0])) this.buffer = this.buffer.slice(1);
      let split = -1;
      for (let i = 0; i < this.buffer.length - 1; i++) {
        if (this.buffer[i] === 10 && this.buffer[i + 1] === 10) { split = i; break; }
      }
      if (split < 0) break;
      const [command, ...lines] = decoder.decode(this.buffer.slice(0, split)).split('\n');
      const headers = {};
      const unescape = value => value.replace(/\\([nrc\\])/g, (_, c) => ({n:'\n', r:'\r', c:':', '\\':'\\'}[c]));
      for (const line of lines) {
        const colon = line.indexOf(':');
        if (colon > 0) headers[unescape(line.slice(0, colon))] ??= unescape(line.slice(colon + 1));
      }
      const start = split + 2;
      const end = headers['content-length'] === undefined
        ? this.buffer.indexOf(0, start) : start + Number(headers['content-length']);
      if (end < start || end >= this.buffer.length) break;
      if (this.buffer[end] !== 0) throw new Error('STOMP 프레임 종료 오류');
      result.push({command, headers, body:decoder.decode(this.buffer.slice(start, end))});
      this.buffer = this.buffer.slice(end + 1);
    }
    return result;
  }
}

export const contentTypes = new Set([
  'CREATED', 'TITLE', 'VISIBILITY', 'DATE', 'COMMENT', 'CAPTION', 'PARTICIPANTS', 'PHOTOS', 'PUBLISHED', 'DELETED', 'LOCATION', 'EDITED'
]);

export function applyContent(snapshot, event) {
  if (!contentTypes.has(event.type)) return {action:'auxiliary', snapshot};
  if (!snapshot) return {action:'recover', snapshot};
  if (!Number.isSafeInteger(event.revision)) return {action:'recover', snapshot};
  if (event.revision <= snapshot.revision) return {action:'ignored', snapshot};
  if (event.revision !== snapshot.revision + 1) return {action:'recover', snapshot};
  const next = structuredClone(snapshot);
  const data = event.data;
  switch (event.type) {
    case 'TITLE': next.title = data.title ?? data.value; next.titleVersion = data.titleVersion ?? data.version; next.titleCrdtState = data.titleCrdtState; next.titleTooLong = data.titleTooLong; break;
    case 'DATE': next.date = data.value; next.dateVersion = data.version; next.dateChips = data.dateChips; break;
    case 'LOCATION': next.locations = data.locations; next.locationVersion = data.version; break;
    case 'VISIBILITY': next.visibility = data.value; next.visibilityVersion = data.version; break;
    case 'COMMENT': next.comments = [...next.comments.filter(c => c.memberId !== data.memberId), data]; break;
    case 'CAPTION': next.photos = next.photos.map(p => p.id === data.id ? data : p); break;
    case 'PHOTOS': next.photos = data.photos; next.dateChips = data.dateChips; break;
    // Permissions and publish blockers are requester dependent: obtain a fresh snapshot.
    case 'CREATED': case 'PARTICIPANTS': case 'PUBLISHED': case 'EDITED': return {action:'recover', snapshot};
    case 'DELETED': return {action:'deleted', snapshot:null};
  }
  next.revision = event.revision;
  return {action:'applied', snapshot:next};
}

export function redact(value, secrets = []) {
  const sensitive = /^(authorization|token|leaseToken|accessToken|refreshToken|sessionId|connectionId)$/i;
  const clean = (key, item) => {
    if (sensitive.test(key)) return '[마스킹]';
    if (typeof item === 'string') {
      let result = item.replace(/Bearer\s+\S+/gi, 'Bearer [마스킹]')
        .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[JWT 마스킹]');
      for (const secret of secrets.filter(Boolean)) result = result.split(secret).join('[마스킹]');
      return result;
    }
    return item;
  };
  return JSON.parse(JSON.stringify(value, clean));
}

export function category(code, status) {
  if (status === 401 || code === 'AUTH_001') return '인증 실패';
  if (status === 403 || code === 'AUTH_002') return '권한 거절';
  if (code === 'BUNDLE_003') return '버전 충돌';
  if (code === 'BUNDLE_004') return '편집권 충돌';
  if (code === 'TIMEOUT') return 'timeout';
  return '요청 실패';
}
