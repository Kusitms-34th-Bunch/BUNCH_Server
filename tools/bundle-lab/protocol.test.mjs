import test from 'node:test';
import assert from 'node:assert/strict';
import {frame, Frames, applyContent, redact, category} from './protocol.mjs';

test('STOMP buffers split frames, ignores heartbeats, measures Korean UTF-8 bytes', () => {
  const body = JSON.stringify({value:'같이 남긴 하루\u0000!'});
  const encoded = frame('MESSAGE', {subscription:'topic', destination:'/topic/a:b'}, body);
  const parser = new Frames();
  assert.deepEqual(parser.push('\n\n' + encoded.slice(0, 38)), []);
  const messages = parser.push(encoded.slice(38) + '\n' + frame('CONNECTED', {version:'1.2'}));
  assert.equal(messages.length, 2);
  assert.equal(messages[0].headers.destination, '/topic/a:b');
  assert.equal(messages[0].body, body);
  assert.equal(messages[0].headers['content-length'], String(new TextEncoder().encode(body).length));
  assert.equal(messages[1].command, 'CONNECTED');
});

test('CONNECT native Authorization is preserved and later frames carry no credential', () => {
  const connect = frame('CONNECT', {Authorization:'Bearer local-only-test'});
  assert.match(connect, /Authorization:Bearer local-only-test/);
  const subscribe = frame('SUBSCRIBE', {id:'topic', destination:'/topic/bunches/1/bundles/2'});
  assert.doesNotMatch(subscribe, /Authorization|Bearer/);
});

const snapshot = {revision:8, title:'기존', titleVersion:3, comments:[], photos:[{id:901, photoId:101}]};
test('content is applied once, skipped revisions trigger snapshot recovery', () => {
  const event = {type:'TITLE', revision:9, data:{value:'변경', version:4}};
  const applied = applyContent(snapshot, event);
  assert.equal(applied.action, 'applied');
  assert.equal(applied.snapshot.title, '변경');
  assert.equal(snapshot.title, '기존');
  assert.equal(applyContent(applied.snapshot, event).action, 'ignored');
  assert.equal(applyContent(applied.snapshot, {...event, revision:7}).action, 'ignored');
  assert.equal(applyContent(snapshot, {...event, revision:10}).action, 'recover');
  assert.equal(applyContent(snapshot, {...event, revision:null}).action, 'recover');
});

test('lease and activity events are never deduplicated by content revision', () => {
  for (const type of ['LEASES','LEASE_GRANTED','LEASE_HEARTBEAT','LEASE_RELEASED','ACTIVITY','PRESENCE','DATE_CHIPS','PERMISSIONS','COMMAND_REJECTED']) {
    assert.equal(applyContent(snapshot, {type, revision:8}).action, 'auxiliary');
  }
});

test('caption addresses BundlePhoto ID, not original Photo ID', () => {
  const photo = {id:901, photoId:101, caption:'캡션', captionVersion:2};
  const result = applyContent(snapshot, {type:'CAPTION', revision:9, data:photo});
  assert.deepEqual(result.snapshot.photos, [photo]);
});

test('logs redact nested tokens and literal secrets, distinguish failures', () => {
  const result = redact({data:{token:'lease-secret', target:'TITLE'}, detail:'secret in error: local-secret',
    Authorization:'Bearer credential', leaseToken:'lease-secret'}, ['local-secret']);
  assert.equal(result.data.token, '[마스킹]');
  assert.doesNotMatch(JSON.stringify(result), /lease-secret|local-secret|Bearer credential/);
  assert.equal(result.data.target, 'TITLE');
  assert.equal(category('AUTH_001'), '인증 실패');
  assert.equal(category('AUTH_002'), '권한 거절');
  assert.equal(category('BUNDLE_003'), '버전 충돌');
  assert.equal(category('BUNDLE_004'), '편집권 충돌');
  assert.equal(category('TIMEOUT'), 'timeout');
});


test('title CRDT state and overflow survive events, location updates share content revision', () => {
  const title = applyContent(snapshot, {type:'TITLE', revision:9,
    data:{value:'구값', version:4, title:'병합 제목', titleVersion:4, titleCrdtState:'synthetic', titleTooLong:true}});
  assert.equal(title.snapshot.title, '병합 제목');
  assert.equal(title.snapshot.titleCrdtState, 'synthetic');
  assert.equal(title.snapshot.titleTooLong, true);
  const location = applyContent(title.snapshot, {type:'LOCATION', revision:10,
    data:{locations:[{id:7,name:'바다',position:0}],version:2}});
  assert.equal(location.snapshot.locations[0].id,7);
  assert.equal(location.snapshot.locationVersion,2);
});
