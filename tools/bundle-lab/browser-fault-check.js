// Run BEFORE browser-check.js against a DRAFT fixture; browser-check publishes at its end.
// Delays real server responses. Never record traces, headers or populated DOM snapshots.
async page => {
  const a = page.locator('#client-A');
  const wait = async (condition, label, timeout = 18000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      if (await condition()) return;
      await page.waitForTimeout(100);
    }
    throw new Error(`검증 실패: ${label}`);
  };
  const click = action => a.locator(`[data-action="${action}"]`).click();
  const logHas = text => a.locator('.log').textContent().then(log => log.includes(text));
  const title = a.locator('[data-field="title"] .value');
  const snapshot = () => a.locator('.snapshot').textContent().then(JSON.parse);
  const titleRoute = '**/title/crdt';
  await page.reload();
  await page.locator('#fixture').click();
  await wait(async () => (await page.locator('#notice').textContent()).includes('준비 완료'), 'fixture');
  if ((await snapshot()).status !== 'DRAFT') throw new Error('Fresh DRAFT fixture required; reset lab before fault checks.');
  await click('connect');
  await wait(async () => (await a.locator('.connection').textContent()).includes('presence 준비'), 'connect');
  await a.locator('.heartbeat').uncheck();
  let count = 0;
  let active = 0;
  let maxActive = 0;
  await page.route(titleRoute, async route => {
    count++;
    active++;
    maxActive = Math.max(maxActive, active);
    const response = await route.fetch();
    await page.waitForTimeout(1800);
    active--;
    await route.fulfill({response});
  });
  try {
    await title.fill('응답 대기 첫 입력');
    await wait(() => count === 1, 'first real PATCH');
    if (!await a.locator('[data-save="title"]').isDisabled()) throw new Error('Duplicate manual save enabled');
    await title.fill('응답 대기 중 추가 입력');
    await page.waitForTimeout(400);
    if (await title.inputValue() !== '응답 대기 중 추가 입력') throw new Error('Input lost during response wait');
    await wait(async () => count >= 2 && (await snapshot()).title === '응답 대기 중 추가 입력' &&
      await a.locator('[data-field="title"]').getAttribute('data-save-state') === 'saved', 'second serialized save');
    if (maxActive !== 1) throw new Error('Concurrent title requests');
  } finally { await page.unroute(titleRoute); }

  await click('clear');
  let timeoutRequest = false;
  await page.route(titleRoute, async route => {
    timeoutRequest = true;
    const response = await route.fetch();
    await page.waitForTimeout(9500);
    try { await route.fulfill({response}); } catch { /* Browser aborted after the real server committed. */ }
  });
  try {
    await title.fill('timeout 전 실제 저장 제목');
    await wait(() => timeoutRequest, 'timeout request reached server');
    await wait(() => logHas('REST timeout · 커밋 여부 불확실'), 'real REST timeout');
    await wait(() => logHas('REST timeout 이후 저장 상태 복구'), 'timeout snapshot recovery');
    if ((await snapshot()).title !== 'timeout 전 실제 저장 제목') throw new Error('Committed title not recovered');
    await a.locator('[data-save="title"]').click();
    await wait(() => logHas('이전 저장 결과가 불확실'), 'ambiguous repeat blocked');
    await page.waitForTimeout(1200);
  } finally { await page.unroute(titleRoute); }
  await click('discard');
  await click('disconnect');
  await a.locator('.jwt').fill('invalid-local-test-token');
  await a.locator('.jwt').dispatchEvent('change');
  await click('refresh');
  await wait(() => logHas('인증 실패'), 'real 401');
  await page.locator('#fixture').click();
  await wait(async () => (await page.locator('#notice').textContent()).includes('준비 완료'), 'restore fixture');
  await page.locator('#bunch').fill('2');
  await page.locator('#apply').click();
  await click('refresh');
  await wait(() => logHas('권한 거절'), 'real 403');
  await page.locator('#fixture').click();
  await wait(async () => (await page.locator('#notice').textContent()).includes('준비 완료'), 'final fixture restore');
  return {passed:['real delayed PATCH input preservation', 'serial title requests', 'duplicate manual save blocked',
    'committed request timeout + snapshot recovery', 'uncertain repeat blocked', 'real 401', 'real 403']};
}
