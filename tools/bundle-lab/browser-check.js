// Run against a freshly seeded DRAFT lab. This test publishes it at the end.
// No traces, request headers, storage state or DOM snapshots after fixture loading.
async page => {
  const errors = [];
  const onError = error => errors.push(error.message);
  page.on('pageerror', onError);
  const a = page.locator('#client-A');
  const b = page.locator('#client-B');
  const wait = async (condition, label, timeout = 15000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      if (await condition()) return;
      await page.waitForTimeout(100);
    }
    throw new Error(`검증 실패: ${label}`);
  };
  const click = (client, action) => client.locator(`[data-action="${action}"]`).click();
  const field = (client, key) => client.locator(`[data-field="${key}"] .value`);
  const snapshot = client => client.locator('.snapshot').textContent().then(JSON.parse);
  const logHas = (client, text) => client.locator('.log').textContent().then(log => log.includes(text));
  const saved = (client, key) => wait(async () =>
    await client.locator(`[data-field="${key}"]`).getAttribute('data-save-state') === 'saved', `${key} own save status`);
  const connect = async client => {
    await click(client, 'connect');
    await wait(async () => (await client.locator('.connection').textContent()).includes('presence 준비'), 'CONNECT + presence');
  };
  const save = async (client, key, value) => {
    await field(client, key).fill(value);
    await client.locator(`[data-save="${key}"]`).click();
    await saved(client, key);
  };
  try {
    await page.reload();
    await page.locator('#fixture').click();
    await wait(async () => (await page.locator('#notice').textContent()).includes('준비 완료'), 'fixture');
    if ((await snapshot(a)).status !== 'DRAFT') throw new Error('Fresh DRAFT fixture required; this test publishes at its end.');
    if (await a.locator('[data-lease]').count()) throw new Error('Obsolete lease UI remains');
    await connect(a);
    await connect(b);
    await wait(async () => (await a.locator('.presence').textContent()).includes('회원 2') &&
      (await b.locator('.presence').textContent()).includes('회원 1'), 'nonexclusive presence / null revision');

    await field(a, 'date').focus();
    await wait(async () => await b.locator('[data-edit-target="DATE"]').getAttribute('data-active') === 'true', 'date interaction begins immediately');
    await click(a, 'refresh');
    await wait(async () => await b.locator('[data-edit-target="DATE"]').getAttribute('data-active') === 'false', 'date interaction ends on blur');
    await a.locator('.location-name').focus();
    await wait(async () => await b.locator('[data-edit-target="LOCATION"]').getAttribute('data-active') === 'true', 'location interaction begins immediately');
    await a.locator('.location-name').press('Escape');
    await wait(async () => await b.locator('[data-edit-target="LOCATION"]').getAttribute('data-active') === 'false', 'location cancel clears editing');
    await a.locator('.photo-drag-card').first().press('Space');
    await wait(async () => await b.locator('[data-edit-target="PHOTOS"]').getAttribute('data-active') === 'true', 'photo grab begins immediately');
    await a.locator('.photo-drag-card').first().press('Escape');
    await wait(async () => await b.locator('[data-edit-target="PHOTOS"]').getAttribute('data-active') === 'false', 'photo cancel clears editing');

    // Delay B's real PATCH before forwarding. A saves while B remains dirty and focused.
    const initial = (await snapshot(a)).title;
    const localB = `${initial} 1일차`;
    const localA = `우리 ${initial}`;
    let releaseB;
    const gate = new Promise(resolve => { releaseB = resolve; });
    let held = false;
    const route = '**/title/crdt';
    await page.route(route, async request => {
      if (JSON.parse(request.request().postData()).state && !held) {
        held = true;
        await gate;
      }
      await request.continue();
    });
    try {
      await field(b, 'title').fill(localB);
      await wait(() => held, 'B autosave PATCH held');
      await field(a, 'title').fill(localA);
      await saved(a, 'title');
      await wait(async () => (await snapshot(b)).title === localA, 'A TITLE received while B dirty');
      if (await field(b, 'title').inputValue() !== localB) throw new Error('Remote title overwrote dirty input');
      releaseB();
      await saved(b, 'title');
      await wait(async () => {
        const left = await snapshot(a), right = await snapshot(b);
        return left.title === right.title && left.title.includes('우리') && left.title.includes('1일차');
      }, 'Automerge concurrent edits converge');
    } finally {
      releaseB();
      await page.unroute(route);
    }
    await click(b, 'replay');
    await wait(() => logHas(b, '중복/이전 내용 무시'), 'duplicate content ignored');

    await field(a, 'comment').fill('A 작성 중 preview');
    await wait(async () => (await b.locator('.presence').textContent()).includes('A 작성 중 preview'), 'COMMENT TYPE preview');
    await wait(async () => (await b.locator('[data-field="comment"] .field-presence').textContent()).includes('A 작성 중 preview'), 'COMMENT field preview');
    if (await b.locator('[data-field="title"] .field-presence').getAttribute('data-active') === 'true') throw new Error('Comment typing leaked into title field');
    await field(a, 'comment').blur();
    await wait(async () => !(await b.locator('.presence').textContent()).includes('A 작성 중 preview'), 'BLUR clears typing');
    await wait(async () => await b.locator('[data-field="comment"] .field-presence').getAttribute('data-active') === 'false', 'BLUR clears field preview');
    await field(a, 'comment').fill('3초 뒤 종료 preview');
    await wait(async () => (await b.locator('.presence').textContent()).includes('3초 뒤 종료 preview'), 'typing starts');
    await wait(async () => !(await b.locator('.presence').textContent()).includes('3초 뒤 종료 preview'), '3s typing expiry', 6000);
    if ((await b.locator('[data-field="comment"] .field-presence').textContent()).includes('3초 뒤 종료 preview')) throw new Error('Expired text preview remains');
    await field(a, 'comment').blur();
    await wait(async () => await b.locator('[data-field="comment"] .field-presence').getAttribute('data-active') === 'false', 'BLUR clears field interaction');
    await save(a, 'comment', 'A의 저장된 코멘트');
    await save(b, 'comment', 'B의 저장된 코멘트');
    await wait(async () => (await snapshot(a)).comments.length === 2 && (await snapshot(b)).comments.length === 2, 'own REST comments');

    const addLocation = async (client, name) => {
      await client.locator('.location-intent').selectOption('ADD');
      await client.locator('.location-name').fill(name);
      await client.locator('.location-address').fill('서울 성동구 테스트로 1');
      await click(client, 'location');
      await wait(async () => (await snapshot(a)).locations.some(p => p.name === name) &&
        (await snapshot(b)).locations.some(p => p.name === name), 'location shared with peer');
    };
    await addLocation(a, 'A 테스트 장소');
    await addLocation(b, 'B 테스트 장소');
    const firstLocation = (await snapshot(a)).locations.find(p => p.name === 'A 테스트 장소');
    await a.locator('.location-intent').selectOption('REMOVE');
    await a.locator('.location-target').selectOption(String(firstLocation.id));
    await click(a, 'location');
    await wait(async () => !(await snapshot(b)).locations.some(p => p.id === firstLocation.id), 'location removal reaches peer');

    const dateBeforeInvalid = (await snapshot(a)).date;
    await field(a, 'date').fill('2099-12-02');
    await a.locator('[data-save="date"]').click();
    await wait(async () => (await a.locator('[data-field="date"] .field-hint').textContent()).includes('미래 날짜는 저장할 수 없어요'), 'future date explanation');
    if ((await snapshot(a)).date !== dateBeforeInvalid) throw new Error('Future date changed stored value');
    await save(a, 'date', '2026-10-20');
    await save(b, 'date', '2026-10-21');
    await wait(async () => (await snapshot(a)).date === '2026-10-21', 'last date save wins');
    if (await a.locator('[data-field="date"] .version').count()) throw new Error('Date version gate remains');

    const photos = (await snapshot(a)).photos;
    if (photos.length !== 6) throw new Error('Expected six fixture photos');
    const target = String(photos[5].id);
    await a.locator('.move-target').selectOption(target);
    await a.locator('.move-prev').selectOption('');
    await a.locator('.move-next').selectOption(String(photos[0].id));
    await click(a, 'move');
    await wait(async () => String((await snapshot(b)).photos[0].id) === target, 'ID move reaches peer');
    const ids = (await snapshot(b)).photos.map(p => p.id);
    if (new Set(ids).size !== 6) throw new Error('Photo duplicates after move');

    await b.locator('.drop').check();
    await save(a, 'date', '2026-10-22');
    await wait(() => logHas(b, '테스트 모드 · 내용 이벤트 버림'), 'drop content');
    await wait(async () => (await snapshot(b)).date === '2026-10-22', 'snapshot polling recovers missing content');

    // input dispatch + disconnect in one task avoids a blur-triggered online save.
    await page.evaluate(() => {
      const root = document.querySelector('#client-B');
      const title = root.querySelector('[data-field="title"] .value');
      title.value = '폐기해야 할 미전송 입력';
      title.dispatchEvent(new Event('input', {bubbles:true}));
      root.querySelector('[data-action="disconnect"]').click();
    });
    if (!await field(b, 'title').isDisabled()) throw new Error('Disconnected editor writable');
    await save(a, 'title', '재연결 서버 제목');
    await connect(b);
    await wait(async () => await field(b, 'title').inputValue() === '재연결 서버 제목', 'reconnect replaces pending document');
    await page.waitForTimeout(1500);
    if ((await snapshot(a)).title !== '재연결 서버 제목') throw new Error('Offline edits replayed');

    await field(a, 'visibility').selectOption('PUBLIC');
    if ((await snapshot(a)).visibility !== null) throw new Error('Visibility persisted before publish');
    await click(a, 'publish');
    await wait(async () => (await snapshot(a)).status === 'PUBLISHED' && (await snapshot(b)).status === 'PUBLISHED', 'final publish');
    for (const client of [a, b]) {
      const state = await snapshot(client);
      if (state.visibility !== 'PUBLIC' || state.publishedBy !== 1) throw new Error('Published visibility/actor mismatch');
      if (!await field(client, 'title').isDisabled() || !await field(client, 'comment').isDisabled()) throw new Error('Published editor writable');
      if (!(await client.locator('.publish-status').textContent()).includes('publishedBy 1')) throw new Error('Publisher missing');
    }
    const privacy = await page.evaluate(() => {
      const tokens = [...document.querySelectorAll('.jwt')].map(input => input.value).filter(Boolean);
      const logs = [...document.querySelectorAll('.log')].map(log => log.textContent).join('');
      return {stored:localStorage.length + sessionStorage.length, tokenInLog:tokens.some(token => logs.includes(token)), query:location.search};
    });
    if (privacy.stored || privacy.tokenInLog || privacy.query) throw new Error('Token storage/log policy failed');
    if (errors.length) throw new Error(`Browser errors: ${errors.length}`);
    return {passed:['existing A/B UI without leases', 'PRESENCE outside revision filtering', 'CRDT concurrent merge and dirty input preservation',
      '1s autosave and own save status', 'duplicate content ignored', 'COMMENT TYPE/BLUR/3s expiry', 'own REST comments', 'location add/remove and peer update',
      'date last save', 'six photo ID move', 'missed content snapshot recovery', 'disconnect readonly / no offline replay',
      'final visibility + publisher + readonly', 'token memory/log policy'], errors:errors.length};
  } finally { page.off('pageerror', onError); }
}
