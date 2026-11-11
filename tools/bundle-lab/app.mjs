import * as Automerge from '/vendor/automerge/entrypoints/fullfat_base64.js';
import {frame, Frames, applyContent, contentTypes, redact, category} from './protocol.mjs';

const $ = (selector, root = document) => root.querySelector(selector);
const notice = message => { $('#notice').textContent = message; };
let config;
const clients = [];
const fieldNames = {title:'제목', date:'날짜', visibility:'최종 발행 공개 범위', comment:'내 코멘트', };

function seoulToday() {
  return new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

function configuration() {
  const rest = new URL($('#rest').value);
  const ws = new URL($('#ws').value);
  for (const url of [rest, ws]) {
    if (url.username || url.password || url.search || url.hash) throw new Error('URL에 인증·query·fragment를 넣지 마세요.');
  }
  if (!['http:', 'https:'].includes(rest.protocol) || !['ws:', 'wss:'].includes(ws.protocol)) {
    throw new Error('HTTP(S), WS(S) 주소가 필요합니다.');
  }
  const bunch = $('#bunch').value;
  const bundle = $('#bundle').value;
  if (![bunch, bundle].every(id => /^[1-9]\d*$/.test(id))) throw new Error('번치·번들 ID를 입력하세요.');
  return {rest:rest.href.replace(/\/$/, ''), ws:ws.href, bunch, bundle};
}

function applySettings() {
  try {
    const next = configuration();
    for (const client of clients) client.reset();
    config = next;
    notice(`적용됨 · ${config.rest} · 번치 ${config.bunch} / 번들 ${config.bundle}`);
    return true;
  } catch (error) { notice(error.message); return false; }
}

function memberId(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload)).sub;
  } catch { return null; }
}

class Client {
  constructor(name) {
    this.name = name;
    this.saveQueue = Promise.resolve();
    this.presence = {members:[], typing:[]};
    this.dirty = new Set();
    this.uncertainSaves = new Set();
    this.root = document.createElement('section');
    this.root.className = 'client';
    this.root.id = `client-${name}`;
    this.root.setAttribute('aria-label', `클라이언트 ${name}`);
    this.root.innerHTML = `
      <div class="client-header"><span class="client-name">클라이언트 ${name}</span><span class="connection">연결 해제</span><span class="rev">revision —</span></div>
      <div class="credentials"><label>Access JWT · 메모리 전용<input class="jwt" type="password" autocomplete="off" spellcheck="false" placeholder="Bearer를 제외한 JWT"></label><button data-action="copy">다른 클라이언트와 같은 계정</button></div>
      <div class="actions"><button data-action="connect" class="primary">연결</button><button data-action="disconnect">연결 끊기</button><button data-action="subscribe">구독 / 재구독</button><button data-action="refresh">최신 상세 조회</button><button data-action="activity">화면 입장 / VIEW</button><button data-action="replay">마지막 내용 중복 재적용</button></div>
      <div class="toggles"><label class="toggle"><input class="heartbeat" type="checkbox" checked>자동 heartbeat · 5초</label><label class="toggle"><input class="drop" type="checkbox">다음 내용 이벤트 버리기</label></div>
      <div class="result" role="status">JWT와 연결 대상을 설정하세요.</div>
      <h2>함께 보는 멤버 · 비독점</h2><div class="presence"></div>
      <h2>내용 저장</h2><div class="fields"></div>
      <button data-action="discard" style="margin-top:12px">미저장 입력 버리고 최신 값 채우기</button>
      <h2>사진 ID 이동</h2>
      <div class="photo-tools"><p class="field-presence" data-edit-target="PHOTOS" role="status">입력 중인 다른 멤버 없음</p><div class="photo-drag-list" aria-label="드래그로 사진 이동"></div><label>대상 BundlePhoto ID<select class="move-target"></select></label>
      <div class="field-controls"><label>앞 ID · 없으면 맨 앞<select class="move-prev"></select></label><label>뒤 ID · 없으면 맨 뒤<select class="move-next"></select></label><button data-action="move">이동 저장</button></div><p class="photo-order"></p></div>
      <h2>장소 보내기 · 최대 3개</h2>
      <div class="location-tools">
        <p class="field-presence" data-edit-target="LOCATION" role="status">입력 중인 다른 멤버 없음</p>
        <p>장소를 직접 입력해 공동 편집을 테스트합니다. 실제 장소 검색은 연결하지 않았습니다.</p>
        <ol class="location-list"></ol>
        <label>작업<select class="location-intent"><option value="ADD">추가</option><option value="REPLACE">교체</option><option value="REMOVE">삭제</option><option value="MOVE">순서 이동</option></select></label>
        <label class="location-target-wrap" hidden>대상 장소<select class="location-target"></select></label>
        <div class="location-place">
          <label>장소 이름<input class="location-name" placeholder="예: 서울숲"></label>
          <label>주소<input class="location-address" placeholder="예: 서울 성동구 뚝섬로 273"></label>
          <details><summary>장소 식별값 · 중복 추가 테스트용</summary><label>출처<input class="location-provider" value="lab"></label><label>장소 식별값<input class="location-external-id"></label></details>
        </div>
        <div class="location-anchors" hidden><label>앞에 오는 장소<select class="location-prev"></select></label><label>뒤에 오는 장소<select class="location-next"></select></label><p>뒤 장소 바로 앞에 놓습니다. 뒤 장소가 없으면 앞 장소 뒤, 둘 다 없으면 맨 뒤입니다.</p></div>
        <button data-action="location">장소 보내기</button><p class="location-result" role="status">연결 후 장소를 추가해 보세요.</p>
      </div>
      <h2>최종 발행</h2><button data-action="publish" class="primary">선택한 공개 범위로 발행</button><p class="publish-status"></p>
      <h2>현재 번들 상태</h2><div class="snapshot-summary">REST 상세를 먼저 조회하세요.</div>
      <details><summary>현재 snapshot JSON</summary><pre class="snapshot">아직 조회하지 않았습니다.</pre></details>
      <details><summary>마지막 REST 저장 응답</summary><pre class="response">아직 저장하지 않았습니다.</pre></details>
      <div class="log-heading"><h2>수신 / 작업 로그</h2><button data-action="clear">로그 지우기</button></div><div class="log" role="log" aria-label="${name} 실시간 로그"></div>`;
    $('#clients').append(this.root);
    const fields = $('.fields', this.root);
    for (const [key, label] of Object.entries(fieldNames)) {
      const row = document.createElement('div');
      row.className = 'field';
      row.dataset.field = key;
      const control = key === 'visibility'
        ? '<select class="value"><option value="">미선택</option><option>PUBLIC</option><option>PRIVATE</option></select>'
        : key === 'comment' ? '<textarea class="value" rows="2"></textarea>'
        : `<input class="value" type="${key === 'date' ? 'date' : 'text'}">`;
      row.innerHTML = `<div class="field-top"><label for="${name}-${key}">${label}</label>${key === 'comment' ? '<label>expectedVersion<input class="version" aria-label="내 코멘트 expectedVersion" type="number" min="0"></label>' : ''}</div>
        <div class="field-controls">${control}${key === 'visibility' ? '' : `<button data-save="${key}">REST 저장</button>`}</div>${key === 'date' ? '<p class="date-rule">서울 기준 오늘까지 저장할 수 있어요. 날짜를 비우면 지정한 날짜를 해제합니다.</p>' : ''}<p class="field-presence" data-edit-target="${key.toUpperCase()}" role="status" data-active="false">입력 중인 다른 멤버 없음</p><p class="field-hint" role="status"></p>`;
      $('.value', row).id = `${name}-${key}`;
      fields.append(row);
      const input = $('.value', row);
      if (key === 'date') input.max = seoulToday();
      input.addEventListener('input', event => {
        this.dirty.add(key);
        this.fieldStatus(key, key === 'visibility' ? '최종 요청에만 전달 · 아직 저장하지 않음' : '미저장 입력', 'pending');
        if (key === 'title' && !event.isComposing) this.editTitle();
        if (['title', 'comment'].includes(key) && !event.isComposing) this.presenceCommand('TYPE', {target:key.toUpperCase(), text:input.value});
      });
      input.addEventListener('compositionstart', () => { if (key === 'title') { this.composing = true; clearTimeout(this.autosave); } });
      input.addEventListener('compositionend', () => {
        if (key === 'title') { this.composing = false; this.editTitle(); }
        if (['title', 'comment'].includes(key)) this.presenceCommand('TYPE', {target:key.toUpperCase(), text:input.value});
      });
      input.addEventListener('blur', () => {
        if (['title', 'comment'].includes(key)) this.presenceCommand('BLUR');
        if (key === 'title' && this.dirty.has(key) && !this.composing) void this.run(() => this.save('title'));
      });
    }
    $('.move-target', this.root).addEventListener('change', () => this.renderAnchors());
    $('.location-external-id', this.root).value = crypto.randomUUID();
    for (const selector of ['.location-intent', '.location-target']) {
      $(selector, this.root).addEventListener('change', () => this.renderLocations());
    }
    this.root.addEventListener('focusin', event => {
      const target = this.interactionTarget(event.target);
      if (target && !this.dragging) this.beginEditing(target);
    });
    for (const name of ['input', 'change']) this.root.addEventListener(name, event => {
      const target = this.interactionTarget(event.target);
      if (target && this.editingTarget !== target) this.beginEditing(target);
    });
    this.root.addEventListener('focusout', event => {
      if (this.interactionTarget(event.relatedTarget) !== this.editingTarget && !this.dragging) this.endEditing();
    });
    $('.move-target', this.root).addEventListener('change', () => this.beginEditing(this.interactionTarget($('.move-target', this.root))));
    this.root.addEventListener('dragstart', event => {
      const card = event.target.closest('.photo-drag-card');
      if (!card || !this.editable) { event.preventDefault(); return; }
      this.pointerPhoto = null;
      this.dragging = card.dataset.photoId;
      this.nativeDragging = true;
      event.dataTransfer.setData('text/plain', this.dragging);
      event.dataTransfer.effectAllowed = 'move';
      this.beginEditing(`PHOTO:${this.dragging}`);
    });
    this.root.addEventListener('dragover', event => {
      if (this.dragging && event.target.closest('.photo-drag-card')) event.preventDefault();
    });
    this.root.addEventListener('drop', event => {
      if (!this.dragging) return;
      event.preventDefault();
      const beforeId = event.target.closest('.photo-drag-card')?.dataset.photoId;
      this.finishPhotoDrag(this.dragging, beforeId);
    });
    this.root.addEventListener('dragend', () => { if (this.dragging) this.finishPhotoDrag(); });
    this.root.addEventListener('keydown', event => {
      const card = event.target.closest('.photo-drag-card');
      if (event.key === 'Escape') { this.finishPhotoDrag(); return; }
      if (!card || !this.editable) return;
      if (event.key === ' ' && !this.dragging) {
        event.preventDefault();
        this.dragging = card.dataset.photoId;
        this.beginEditing(`PHOTO:${this.dragging}`);
      } else if (event.key === 'Enter' && this.dragging) {
        event.preventDefault();
        this.finishPhotoDrag(this.dragging, card.dataset.photoId);
      } else if (['ArrowLeft', 'ArrowRight'].includes(event.key) && this.dragging) {
        event.preventDefault();
        const next = event.key === 'ArrowLeft' ? card.previousElementSibling : card.nextElementSibling;
        next?.focus();
      }
    });
    this.root.addEventListener('pointerdown', event => {
      const card = event.target.closest('.photo-drag-card');
      if (!card || !this.editable) return;
      this.pointerPhoto = {id:card.dataset.photoId, x:event.clientX, y:event.clientY, moved:false};
      this.beginEditing(`PHOTO:${card.dataset.photoId}`);
    });
    window.addEventListener('pointermove', event => {
      if (this.pointerPhoto && Math.hypot(event.clientX - this.pointerPhoto.x, event.clientY - this.pointerPhoto.y) > 8) this.pointerPhoto.moved = true;
    });
    window.addEventListener('pointerup', event => {
      if (!this.pointerPhoto) return;
      const pointer = this.pointerPhoto;
      const card = document.elementFromPoint(event.clientX, event.clientY)?.closest('.photo-drag-card');
      this.finishPhotoDrag(pointer.moved ? pointer.id : null, card && this.root.contains(card) ? card.dataset.photoId : null);
    });
    // Native HTML drag dispatches pointercancel itself; dragend owns its cancellation.
    window.addEventListener('pointercancel', () => {
      if (this.pointerPhoto || (this.dragging && !this.nativeDragging)) this.finishPhotoDrag();
    });
    window.addEventListener('blur', () => { this.endEditing(); this.dragging = null; this.pointerPhoto = null; });
    this.root.addEventListener('click', event => {
      const button = event.target.closest('button');
      if (!button) return;
      const actions = {
        connect:() => this.connect(), disconnect:() => this.disconnect(), subscribe:() => this.subscribe(),
        refresh:() => this.recover('수동 상세 조회'), activity:() => this.presenceCommand('VIEW'), move:() => this.movePhoto(), location:() => this.saveLocation(), publish:() => this.publish(),
        copy:() => { this.reset(); this.token = clients.find(c => c !== this)?.token ?? ''; this.status('동일 JWT 복사됨 · 독립 소켓으로 연결하세요.'); },
        discard:() => { this.dirty.clear(); this.uncertainSaves.clear(); clearTimeout(this.autosave); this.titleDoc = null; this.inputDoc = null; this.mergeTitle(this.snapshot?.titleCrdtState); this.render(); },
        replay:() => {
          if (!this.lastContent) throw new Error('수신한 내용 이벤트가 없습니다.');
          return this.receive(this.lastContent, 'topic');
        },
        clear:() => { $('.log', this.root).replaceChildren(); }
      };
      void this.run(async () => {
        if (button.dataset.action) await actions[button.dataset.action]();
        if (button.dataset.save) await this.save(button.dataset.save);
      });
    });
    $('.jwt', this.root).addEventListener('change', () => this.reset());
    this.timer = setInterval(() => this.tick(), 5000);
    this.presenceTimer = setInterval(() => this.renderPresence(), 250);
    this.render();
  }

  get token() { return $('.jwt', this.root).value.trim().replace(/^Bearer\s+/i, ''); }
  set token(value) { $('.jwt', this.root).value = value; }
  get path() { return `/api/v1/bunches/${config.bunch}/bundles/${config.bundle}`; }
  get connected() { return this.socket?.readyState === WebSocket.OPEN && this.stompConnected; }
  async run(action) {
    try { await action(); }
    catch (error) {
      const message = error.code ? `${category(error.code, error.status)} · ${error.code}` : error.message;
      this.status(message, true);
      this.log('실패', {message, detail:error.detail}, true);
    }
  }
  status(message, error = false) {
    const result = $('.result', this.root);
    result.textContent = message;
    result.classList.toggle('error', error);
  }
  log(label, data, error = false) {
    const secrets = clients.map(c => c.token);
    const item = document.createElement('div');
    item.className = `log-entry${error ? ' error' : ''}`;
    item.textContent = `${new Date().toLocaleTimeString('ko-KR')} ${label}\n${JSON.stringify(redact(data, secrets))}`;
    const log = $('.log', this.root);
    log.prepend(item);
    while (log.childElementCount > 180) log.lastChild.remove();
  }
  async request(path = '', body, method = body ? 'PUT' : 'GET', extraHeaders = {}) {
    if (!config || !this.token) throw new Error('설정 적용과 JWT 입력이 필요합니다.');
    const headers = {Authorization:`Bearer ${this.token}`, ...extraHeaders};
    if (body) { headers['Content-Type'] = 'application/json'; headers['X-Request-Id'] = crypto.randomUUID(); }
    let response;
    try {
      response = await fetch(`${config.rest}${this.path}${path}`, {
        method, headers, body:body ? JSON.stringify(body) : undefined,
        cache:'no-store', credentials:'omit', redirect:'error', signal:AbortSignal.timeout(8000)
      });
    } catch (error) {
      if (error.name === 'TimeoutError') throw Object.assign(new Error(), {code:'TIMEOUT'});
      throw new Error('네트워크/Origin 실패 · 주소와 로컬 프록시를 확인하세요.');
    }
    const result = await response.json();
    if (!response.ok || result.success === false) throw Object.assign(new Error(), {
      status:response.status, code:result.error?.code ?? `HTTP_${response.status}`, detail:result.error?.detail
    });
    return result.data;
  }
  async recover(reason) {
    if (this.recovery) return this.recovery;
    const generation = this.generation;
    const recovery = (async () => {
      const snapshot = await this.request();
      if (generation !== this.generation) return;
      if (!this.snapshot || snapshot.revision >= this.snapshot.revision) {
        this.snapshot = snapshot;
        this.mergeTitle(snapshot.titleCrdtState);
      }
      this.log('REST snapshot 복구', {reason, revision:snapshot.revision});
      this.render();
      this.status(`${reason} · revision ${this.snapshot.revision}`);
    })().finally(() => { if (this.recovery === recovery) this.recovery = null; });
    this.recovery = recovery;
    return recovery;
  }
  reset() {
    this.disconnect();
    this.snapshot = null;
    this.dirty.clear();
    this.uncertainSaves.clear();
    this.photoId = null;
    this.subscribed = false;
    this.lastContent = null;
    this.titleDoc = null;
    this.inputDoc = null;
    $('.response', this.root).textContent = '아직 저장하지 않았습니다.';
    this.render();
  }
  disconnect() {
    this.presenceCommand('LEAVE');
    clearTimeout(this.autosave);
    this.generation = (this.generation ?? 0) + 1;
    this.recovery = null;
    clearTimeout(this.connectTimeout);
    const socket = this.socket;
    this.socket = null;
    this.stompConnected = false;
    this.subscribed = false;
    this.dirty.clear();
    this.titleDoc = null;
    this.inputDoc = null;
    this.saveQueue = Promise.resolve();
    this.presence = {members:[], typing:[]};
    this.editingTarget = null;
    this.dragging = null;
    this.pointerPhoto = null;
    if (socket) { socket.onclose = null; socket.close(); }
    this.connectionLabel('연결 해제');
    this.render();
  }
  connectionLabel(text) {
    const label = $('.connection', this.root);
    label.textContent = text;
    label.classList.toggle('online', this.connected);
  }
  async connect() {
    if (!config && !applySettings()) return;
    this.disconnect();
    const generation = this.generation;
    await this.recover('연결 전 인증·권한 확인');
    if (generation !== this.generation) return;
    this.parser = new Frames();
    const socket = new WebSocket(config.ws, ['v12.stomp']);
    this.socket = socket;
    this.connectionLabel('연결 중…');
    this.connectTimeout = setTimeout(() => {
      if (this.socket !== socket || this.connected) return;
      this.disconnect();
      this.status('timeout · CONNECT 응답 없음 (인증/Origin/권한 확인)', true);
      this.log('timeout', {phase:'CONNECT'}, true);
    }, 8000);
    socket.onopen = () => {
      if (this.socket === socket) socket.send(frame('CONNECT', {
        'accept-version':'1.2', host:new URL(config.ws).hostname, 'heart-beat':'0,0', Authorization:`Bearer ${this.token}`
      }));
    };
    socket.onmessage = event => {
      if (this.socket !== socket) return;
      void this.run(async () => {
        for (const message of this.parser.push(event.data)) {
          if (message.command === 'CONNECTED') {
            clearTimeout(this.connectTimeout);
            this.stompConnected = true;
            this.connectionLabel('연결됨');
            this.log('CONNECTED', {version:message.headers.version});
            await this.subscribe();
          } else if (message.command === 'MESSAGE' || message.command === 'ERROR') {
            const envelope = JSON.parse(message.body);
            if (message.command === 'ERROR') {
              this.log('STOMP ERROR', envelope, true);
              this.status(`${category(envelope.data?.code)} · ${envelope.data?.code ?? 'WS_001'}`, true);
              this.disconnect();
            } else await this.receive(envelope, message.headers.subscription);
          }
        }
      });
    };
    socket.onerror = () => {
      if (this.socket === socket) this.status('WebSocket 연결 실패 · 주소/Origin/서버 확인', true);
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.disconnect();
      this.status('연결 종료 · 읽기 전용 · 미저장 입력 폐기. 다시 연결하여 REST 복구하세요.');
      this.log('DISCONNECTED', {pendingDiscarded:true});
    };
  }
  send(command, headers, body) {
    if (!this.connected) throw new Error('STOMP 연결이 필요합니다.');
    this.socket.send(frame(command, headers, body));
  }
  async subscribe() {
    if (!this.connected) throw new Error('먼저 연결하세요.');
    if (this.subscribed) {
      for (const id of ['topic', 'leases', 'activity']) this.send('UNSUBSCRIBE', {id});
    }
    this.send('SUBSCRIBE', {id:'topic', destination:`/topic/bunches/${config.bunch}/bundles/${config.bundle}`, ack:'auto'});
    this.send('SUBSCRIBE', {id:'leases', destination:'/user/queue/bundle-leases', ack:'auto'});
    this.send('SUBSCRIBE', {id:'activity', destination:'/user/queue/bundle-activity', ack:'auto'});
    this.subscribed = true;
    this.log('SUBSCRIBE 요청', {topic:`/topic/bunches/${config.bunch}/bundles/${config.bundle}`});
    await this.recover('구독·재연결 상세 복구');
    const generation = this.generation;
    const presence = await this.request('/presence');
    if (generation !== this.generation || !this.connected) return;
    this.presence = presence;
    this.presenceCommand('VIEW');
    this.connectionLabel('연결됨 · presence 준비');
    this.render();
  }
  presenceCommand(action, data = {}) {
    if (!this.connected || !this.subscribed || this.snapshot?.status !== 'DRAFT') return;
    this.send('SEND', {destination:`/app/bunches/${config.bunch}/bundles/${config.bundle}/presence`, 'content-type':'application/json'}, JSON.stringify({action, ...data}));
  }
  interactionTarget(element) {
    if (!element || !this.root.contains(element)) return null;
    const field = element.closest('[data-field]');
    if (field && element.matches('.value')) return field.dataset.field.toUpperCase();
    if (element.closest('.location-tools')) return 'LOCATION';
    const card = element.closest('.photo-drag-card');
    if (card) return `PHOTO:${card.dataset.photoId}`;
    if (element.closest('.photo-tools')) {
      const id = $('.move-target', this.root).value;
      return id ? `PHOTO:${id}` : 'PHOTOS';
    }
    return null;
  }
  beginEditing(target) {
    if (!target || !this.editable || document.hidden) return;
    if (this.editingTarget && this.editingTarget !== target) this.endEditing();
    this.editingTarget = target;
    this.presenceCommand('BEGIN', {target});
  }
  endEditing() {
    if (this.editingTarget) this.presenceCommand('END', {target:this.editingTarget});
    this.editingTarget = null;
  }
  finishPhotoDrag(photoId, beforeId) {
    this.endEditing();
    this.dragging = null;
    this.nativeDragging = false;
    this.pointerPhoto = null;
    this.render();
    if (!photoId || !beforeId || photoId === beforeId || !this.editable) return;
    if (!this.snapshot.photos.some(p => String(p.id) === photoId)) {
      this.status('다른 멤버가 삭제한 사진이에요.', true);
      return;
    }
    const rest = this.snapshot.photos.filter(p => String(p.id) !== photoId);
    const index = rest.findIndex(p => String(p.id) === beforeId);
    if (index < 0) return;
    void this.run(() => this.enqueue(async generation => {
      const result = await this.request(`/photos/${photoId}/move`, {
        previousBundlePhotoId:index > 0 ? rest[index - 1].id : null,
        nextBundlePhotoId:rest[index].id, mode:'DRAFT'
      }, 'POST');
      if (generation !== this.generation) return;
      if (result.revision >= this.snapshot.revision) this.snapshot = result;
      this.render();
    }));
  }
  async receive(envelope, subscription) {
    if (String(envelope.bunchId) !== config.bunch || String(envelope.bundleId) !== config.bundle) return;
    // Private queues may carry legacy lease tokens: only log public content and safe errors.
    if (subscription === 'topic' && contentTypes.has(envelope.type)) this.log(`수신 · ${subscription}`, envelope);
    const {type, data, revision} = envelope;
    if (type === 'PRESENCE') { this.presence = data; this.renderPresence(); return; }
    if (type === 'PERMISSIONS') { await this.recover('접근 권한 갱신'); return; }
    if (type === 'DATE_CHIPS' && this.snapshot) { this.snapshot.dateChips = data.dateChips; this.render(); return; }
    if (contentTypes.has(type)) {
      this.lastContent = envelope;
      if ($('.drop', this.root).checked) {
        $('.drop', this.root).checked = false;
        this.log('테스트 모드 · 내용 이벤트 버림', {type, revision});
        return;
      }
      const result = applyContent(this.snapshot, envelope);
      if (result.action === 'recover') await this.recover('내용 revision 누락·계약 상태 복구');
      else if (result.action === 'ignored') this.log('중복/이전 내용 무시', {type, revision});
      else {
        this.snapshot = result.snapshot;
        if (type === 'TITLE') this.mergeTitle(data.titleCrdtState);
        this.render();
        this.status(result.action === 'deleted' ? '삭제된 번들' : `${type} 반영 · revision ${revision}`);
      }
      return;
    }
    if (type === 'COMMAND_REJECTED') {
      this.status(`${category(data.code)} · ${data.code}`, true);
      this.log('명령 거절', {code:data.code}, true);
    }
  }
  async tick() {
    this.renderPresence();
    if (document.hidden || !this.connected || !this.subscribed || !$('.heartbeat', this.root).checked) return;
    this.presenceCommand('VIEW');
    if (this.editingTarget) this.presenceCommand('BEGIN', {target:this.editingTarget});
    // PRESENCE has no revision. Poll the content snapshot to detect missed content events.
    await this.run(() => this.recover('5초 내용 확인'));
  }
  renderPresence() {
    const now = Date.now();
    const ownId = String(memberId(this.token));
    const members = (this.presence?.members ?? []).filter(m => Date.parse(m.expiresAt) > now);
    const typing = (this.presence?.typing ?? []).filter(t => Date.parse(t.expiresAt) > now && String(t.memberId) !== ownId);
    $('.presence', this.root).textContent = `화면 멤버: ${members.map(m => `회원 ${m.memberId}${String(m.memberId) === ownId ? ' (나)' : ''}`).join(', ') || '없음'}${typing.length ? '\n' + typing.map(t => `회원 ${t.memberId} · ${t.target} 작성 중: ${t.text}`).join(' / ') : ' · 다른 멤버 입력 없음'}`;
    const editing = (this.presence?.editing ?? []).filter(t => Date.parse(t.expiresAt) > now && String(t.memberId) !== ownId);
    if (editing.length) $('.presence', this.root).textContent += '\n' + editing.map(t => `회원 ${t.memberId} · ${t.target} 작업 중`).join(' / ');
    for (const label of this.root.querySelectorAll('[data-edit-target]')) {
      const target = label.dataset.editTarget;
      const matches = t => t.target === target || (target === 'PHOTOS' && /^(PHOTO|PICK):/.test(t.target));
      const previews = typing.filter(matches);
      const active = editing.filter(matches).filter(t => !previews.some(p => p.memberId === t.memberId && p.target === t.target));
      label.textContent = [...previews.map(t => `회원 ${t.memberId} · ${t.target === 'COMMENT' ? '본인 코멘트' : '제목'} 작성 중: ${t.text}`),
        ...active.map(t => `회원 ${t.memberId} · ${t.target} 작업 중`)].join('\n') || '작업 중인 다른 멤버 없음';
      label.dataset.active = String(previews.length + active.length > 0);
    }
  }
  fieldStatus(key, text, state) {
    const row = $(`[data-field="${key}"]`, this.root);
    $('.field-hint', row).textContent = text;
    row.dataset.saveState = state;
  }
  mergeTitle(state) {
    if (!state) return;
    const remote = Automerge.load(Uint8Array.from(atob(state), c => c.charCodeAt(0)));
    this.titleDoc = this.titleDoc ? Automerge.merge(this.titleDoc, remote) : remote;
    // Keep the local input branch while dirty. The outgoing document still merges all remote changes.
    if (!this.dirty.has('title')) this.inputDoc = Automerge.clone(this.titleDoc);
  }
  editTitle() {
    if (!this.connected || !this.inputDoc) return;
    const value = $('[data-field="title"] .value', this.root).value;
    this.inputDoc = Automerge.change(this.inputDoc, draft => Automerge.updateText(draft, ['title'], value));
    this.titleDoc = Automerge.merge(this.titleDoc, this.inputDoc);
    clearTimeout(this.autosave);
    this.autosave = setTimeout(() => { if (!this.composing) void this.run(() => this.save('title')); }, 1000);
  }
  get editable() { return this.connected && this.subscribed && this.snapshot?.status === 'DRAFT' && this.snapshot.permissions?.canEdit; }
  renderLocations() {
    const places = this.snapshot?.locations ?? [];
    const list = $('.location-list', this.root);
    list.replaceChildren(...places.map(place => {
      const item = document.createElement('li');
      item.textContent = `${place.name} · ${place.address} (ID ${place.id})`;
      return item;
    }));
    if (!places.length) {
      const item = document.createElement('li');
      item.textContent = '등록된 장소 없음';
      list.append(item);
    }
    const target = $('.location-target', this.root);
    const selected = target.value;
    target.replaceChildren(new Option('선택하세요', ''), ...places.map(p => new Option(`${p.name} (ID ${p.id})`, String(p.id))));
    target.value = places.some(p => String(p.id) === selected) ? selected : '';
    for (const side of ['prev', 'next']) {
      const select = $(`.location-${side}`, this.root);
      const value = select.value;
      const available = places.filter(p => String(p.id) !== target.value);
      select.replaceChildren(new Option('없음', ''), ...available.map(p => new Option(p.name, String(p.id))));
      select.value = available.some(p => String(p.id) === value) ? value : '';
    }
    const intent = $('.location-intent', this.root).value;
    $('.location-target-wrap', this.root).hidden = intent === 'ADD';
    $('.location-place', this.root).hidden = !['ADD', 'REPLACE'].includes(intent);
    $('.location-anchors', this.root).hidden = intent !== 'MOVE';
    for (const input of this.root.querySelectorAll('.location-tools input, .location-tools select')) input.disabled = !this.editable;
    $('[data-action="location"]', this.root).disabled = !this.editable || this.saving;
  }
  renderAnchors() {
    const target = $('.move-target', this.root).value;
    for (const side of ['prev', 'next']) {
      const select = $(`.move-${side}`, this.root);
      const previous = select.value;
      select.replaceChildren(new Option('없음', ''), ...(this.snapshot?.photos ?? []).filter(p => String(p.id) !== target).map(p => new Option(String(p.id), String(p.id))));
      if ([...select.options].some(o => o.value === previous)) select.value = previous;
    }
  }
  renderPhotoDrag() {
    if (this.dragging || this.pointerPhoto) return;
    const list = $('.photo-drag-list', this.root);
    list.replaceChildren(...(this.snapshot?.photos ?? []).map(photo => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'photo-drag-card';
      card.dataset.photoId = String(photo.id);
      card.draggable = this.editable;
      card.disabled = !this.editable;
      card.textContent = `사진 ${photo.id}`;
      card.setAttribute('aria-label', `사진 ${photo.id} 드래그 이동`);
      return card;
    }));
  }
  render() {
    const snapshot = this.snapshot;
    $('.rev', this.root).textContent = `revision ${snapshot?.revision ?? '—'}`;
    const available = snapshot?.photos ?? [];
    const photos = $('.move-target', this.root);
    const selected = photos.value;
    photos.replaceChildren(...available.map(photo => new Option(`BundlePhoto ${photo.id} / Photo ${photo.photoId ?? '삭제됨'}`, String(photo.id))));
    if (available.some(p => String(p.id) === selected)) photos.value = selected;
    this.renderAnchors();
    this.renderPhotoDrag();
    this.renderLocations();
    $('.photo-order', this.root).textContent = `현재 순서: ${available.map(p => p.id).join(' → ')}`;
    const comment = snapshot?.comments.find(c => String(c.memberId) === String(memberId(this.token)));
    const fields = {
      title:this.titleDoc?.title ?? snapshot?.title,
      date:snapshot?.date, visibility:snapshot?.visibility, comment:comment?.content ?? ''
    };
    for (const [key, value] of Object.entries(fields)) {
      const row = $(`[data-field="${key}"]`, this.root);
      if (key === 'date') $('.value', row).max = seoulToday();
      if (!this.dirty.has(key)) {
        $('.value', row).value = value ?? '';
        if (key === 'comment') $('.version', row).value = snapshot ? comment?.version ?? 0 : '';
        if (row.dataset.saveState !== 'saved') this.fieldStatus(key, key === 'title' ? 'CRDT · 1초 무입력 / blur 저장' : key === 'date' ? '마지막 서버 저장 적용 · 편집권 없음' : key === 'visibility' ? '최종 발행 요청에만 전달' : '본인 코멘트 · REST 저장', 'ready');
      }
      for (const input of row.querySelectorAll('input,select,textarea')) input.disabled = !this.editable;
    }
    for (const button of this.root.querySelectorAll('[data-save], [data-action="move"], [data-action="publish"]')) button.disabled = !this.editable || this.saving;
    for (const select of this.root.querySelectorAll('.photo-tools select')) select.disabled = !this.editable;
    $('[data-action="publish"]', this.root).disabled ||= !snapshot?.permissions?.canPublish;
    $('.publish-status', this.root).textContent = snapshot?.status === 'PUBLISHED'
      ? `발행 완료 · publishedBy ${snapshot.publishedBy ?? '—'} · ${snapshot.publisherNickname ?? ''}`
      : `발행 차단 사유: ${snapshot?.publishBlockers?.join(', ') || '없음'}${snapshot?.titleTooLong ? ' · 제목 40자 초과' : ''}`;
    $('.snapshot', this.root).textContent = snapshot ? JSON.stringify(snapshot, null, 2) : '조회된 snapshot 없음';
    $('.snapshot-summary', this.root).textContent = snapshot
      ? `${snapshot.status} · 제목: ${snapshot.title || '(비어 있음)'} · 참여자 ${snapshot.participants.length}명 · 사진 ${snapshot.photos.length}장 · canEdit ${snapshot.permissions.canEdit}`
      : 'REST 상세를 조회하세요.';
    this.renderPresence();
  }
  enqueue(action) {
    const generation = this.generation;
    const operation = this.saveQueue.catch(() => {}).then(async () => {
      if (generation !== this.generation || !this.editable) return;
      this.saving = true;
      this.render();
      try { return await action(generation); }
      finally { this.saving = false; this.render(); }
    });
    this.saveQueue = operation;
    return operation;
  }
  async save(key) {
    if (!this.editable) throw new Error('연결된 초안 참여자만 저장할 수 있습니다.');
    if (key === 'title') clearTimeout(this.autosave);
    return this.enqueue(async generation => {
      if (key === 'title' && (!this.dirty.has('title') || this.composing)) return;
      if (this.uncertainSaves.has(key)) throw new Error('이전 저장 결과가 불확실합니다. 상세와 입력을 비교한 뒤 미저장 입력을 버리고 다시 작성하세요.');
      const row = $(`[data-field="${key}"]`, this.root);
      const submittedValue = $('.value', row).value;
      if (key === 'date') {
        const input = $('.value', row);
        input.max = seoulToday();
        const reason = input.validity.badInput ? '날짜를 올바르게 입력해 주세요.'
          : submittedValue > input.max ? `미래 날짜는 저장할 수 없어요. ${input.max}까지 선택해 주세요.`
          : !input.validity.valid ? '날짜를 올바르게 입력해 주세요.' : null;
        if (reason) {
          this.fieldStatus(key, reason, 'error');
          this.status(reason, true);
          return;
        }
      }
      let body, path, method = 'PUT';
      if (key === 'title') {
        body = {state:btoa(String.fromCharCode(...Automerge.save(this.titleDoc)))};
        path = '/title/crdt'; method = 'PATCH';
      } else {
        body = {value:key === 'date' && !submittedValue ? null : submittedValue, mode:'DRAFT',
          expectedVersion:key === 'comment' ? Number($('.version', row).value) : this.snapshot.dateVersion};
        path = key === 'comment' ? '/comments/me' : '/date';
      }
      this.fieldStatus(key, '저장 중 · 추가 입력은 다음 저장으로 유지', 'saving');
      let result;
      try { result = await this.request(path, body, method); }
      catch (error) {
        if (generation !== this.generation) return;
        this.fieldStatus(key, error.code === 'TIMEOUT' ? '응답 시간 초과 · 서버 저장 여부 확인 필요' : `저장 실패 · ${error.code ?? error.message}`, 'error');
        if (error.code === 'TIMEOUT') {
          this.uncertainSaves.add(key);
          this.log('REST timeout · 커밋 여부 불확실', {field:key}, true);
          await this.recover('REST timeout 이후 저장 상태 복구');
        }
        throw error;
      }
      if (generation !== this.generation) return;
      $('.response', this.root).textContent = JSON.stringify(result, null, 2);
      if (key === 'title') this.mergeTitle(result.titleCrdtState);
      if ($('.value', row).value === submittedValue) {
        this.dirty.delete(key);
        if (key === 'title') this.inputDoc = Automerge.clone(this.titleDoc);
        this.fieldStatus(key, '저장됨 · 이 클라이언트 입력 확인', 'saved');
      } else this.fieldStatus(key, '새 입력 대기 · 다음 자동 저장', 'pending');
      if (result.revision !== undefined && (!this.snapshot || result.revision >= this.snapshot.revision)) {
        this.snapshot = result;
        this.mergeTitle(result.titleCrdtState);
      }
      this.log('REST 저장 성공', {field:key, revision:result.revision});
      await this.recover(`${key} 저장 후 상세 확인`);
      this.render();
      if (this.dirty.has('title') && !this.composing) {
        clearTimeout(this.autosave);
        this.autosave = setTimeout(() => void this.run(() => this.save('title')), 1000);
      }
    });
  }
  movePhoto() {
    const target = $('.move-target', this.root).value;
    const body = {previousBundlePhotoId:Number($('.move-prev', this.root).value) || null,
      nextBundlePhotoId:Number($('.move-next', this.root).value) || null, mode:'DRAFT'};
    return this.enqueue(async generation => {
      const result = await this.request(`/photos/${target}/move`, body, 'POST');
      if (generation !== this.generation) return;
      if (result.revision >= this.snapshot.revision) this.snapshot = result;
      this.log('사진 이동 저장', {target, ...body, revision:result.revision});
      this.endEditing();
      this.render();
    });
  }
  async saveLocation() {
    if (!this.editable) throw new Error('연결된 초안 참여자만 장소를 바꿀 수 있습니다.');
    const intent = $('.location-intent', this.root).value;
    const body = {intent};
    if (['ADD', 'REPLACE'].includes(intent)) {
      const location = Object.fromEntries(['provider', 'externalId', 'name', 'address'].map(key => [key,
        $(`.location-${key === 'externalId' ? 'external-id' : key}`, this.root).value.trim()]));
      if (Object.values(location).some(value => !value)) throw new Error('장소 이름과 주소, 식별값을 입력하세요.');
      if (intent === 'ADD') body.locations = [location];
      else body.location = location;
    }
    if (intent !== 'ADD') {
      const target = Number($('.location-target', this.root).value);
      if (!target) throw new Error('대상 장소를 선택하세요.');
      body.targetLocationId = target;
    }
    if (intent === 'MOVE') {
      body.previousLocationId = Number($('.location-prev', this.root).value) || null;
      body.nextLocationId = Number($('.location-next', this.root).value) || null;
      if (body.previousLocationId && body.previousLocationId === body.nextLocationId) throw new Error('앞뒤에 같은 장소를 지정할 수 없습니다.');
    }
    return this.enqueue(async generation => {
      const hint = $('.location-result', this.root);
      hint.textContent = '장소 저장 중…';
      let result;
      try { result = await this.request('/locations/intents', body, 'POST'); }
      catch (error) {
        if (generation === this.generation) hint.textContent = `장소 저장 실패 · ${error.code ?? error.message}`;
        throw error;
      }
      if (generation !== this.generation) return;
      if (result.revision >= this.snapshot.revision) {
        this.snapshot = {...this.snapshot, locations:result.locations, locationVersion:result.locationVersion, revision:result.revision};
      }
      $('.response', this.root).textContent = JSON.stringify(result, null, 2);
      hint.textContent = intent === 'ADD'
        ? result.acceptedCount ? `${result.acceptedCount}개 추가했어요. 현재 ${result.locations.length}/3개입니다.`
          : '추가된 장소가 없어요. 이미 등록된 장소이거나 최대 3개가 찼습니다.'
        : result.changed ? '장소 변경을 저장했어요.' : '이미 반영됐거나 변경할 내용이 없어요.';
      if (intent === 'ADD' && result.acceptedCount > 0 && $('.location-external-id', this.root).value === body.locations[0].externalId) {
        $('.location-external-id', this.root).value = crypto.randomUUID();
      }
      this.log('장소 저장 성공', {intent, acceptedCount:result.acceptedCount, revision:result.revision});
      this.endEditing();
      await this.recover('장소 저장 후 상세 확인');
    });
  }
  async publish() {
    if (this.dirty.has('title')) await this.save('title');
    const visibility = $('[data-field="visibility"] .value', this.root).value;
    if (!visibility) throw new Error('최종 공개 범위를 선택하세요.');
    return this.enqueue(async generation => {
      const result = await this.request('/publish', {visibility}, 'POST', {'Idempotency-Key':crypto.randomUUID()});
      if (generation !== this.generation) return;
      this.snapshot = result;
      clearTimeout(this.autosave);
      this.dirty.clear();
      this.presence = {members:[], typing:[]};
      this.log('발행 완료', {publishedBy:result.publishedBy, visibility:result.visibility, revision:result.revision});
      this.render();
    });
  }

}

clients.push(new Client('A'), new Client('B'));
$('#rest').value = location.origin;
$('#ws').value = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
$('#apply').addEventListener('click', applySettings);
$('#fixture').addEventListener('click', async () => {
  const button = $('#fixture'); button.disabled = true;
  notice('로컬 fixture와 A/B 상세를 불러오는 중…');
  try {
    for (const client of clients) client.reset();
    const response = await fetch('/__lab/fixture', {method:'POST', headers:{'Content-Type':'application/json'}, body:'{}', cache:'no-store', credentials:'omit'});
    if (!response.ok) throw new Error('로컬 fixture 준비 실패 · start.sh로 실행했는지 확인하세요.');
    const fixture = await response.json();
    $('#rest').value = location.origin;
    $('#ws').value = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
    $('#bunch').value = fixture.bunchId;
    $('#bundle').value = fixture.bundleId;
    if (!applySettings()) return;
    for (const client of clients) {
      client.token = fixture.clients[client.name].token;
      await client.run(() => client.recover('로컬 fixture 조회'));
    }
    notice('로컬 A/B 준비 완료 · 각 클라이언트 연결을 눌러 시작하세요. 데이터는 이 실행 동안 유지됩니다.');
  } catch (error) { notice(error.message); }
  finally { button.disabled = false; }
});
document.addEventListener('visibilitychange', () => {
  for (const client of clients) {
    if (document.hidden) {
      client.endEditing();
      client.dragging = null;
      client.pointerPhoto = null;
      client.presenceCommand('BACKGROUND');
    }
    else if (client.connected) client.presenceCommand('VIEW');
  }
});
window.addEventListener('beforeunload', () => { for (const client of clients) client.disconnect(); });
