async page => {
  return page.evaluate(async () => {
    const A = await import('/vendor/automerge/entrypoints/fullfat_base64.js');
    const fixture = await (await fetch('/__lab/fixture', {method:'POST'})).json();
    const path = `/api/v1/bunches/${fixture.bunchId}/bundles/${fixture.bundleId}`;
    const call = async (name, suffix = '', body, method = body ? 'PUT' : 'GET') => {
      const response = await fetch(path + suffix, {method,
        headers:{Authorization:`Bearer ${fixture.clients[name].token}`, 'Content-Type':'application/json',
          'X-Request-Id':crypto.randomUUID()},
        body:body ? JSON.stringify(body) : undefined});
      const result = await response.json();
      if (!result.success) throw new Error(`${suffix}: ${result.error?.code}`);
      return result.data;
    };
    const decode = value => Uint8Array.from(atob(value), c => c.charCodeAt(0));
    const encode = value => btoa(String.fromCharCode(...value));
    const initial = await call('A');
    if (initial.title !== '제주 여행') throw new Error('fresh fixture required');
    const base = A.load(decode(initial.titleCrdtState));
    const one = A.change(A.clone(base), d => A.splice(d, ['title'], 0, 2, '부산'));
    const two = A.change(A.clone(base), d => A.splice(d, ['title'], 5, 0, ' 1일차'));
    await Promise.all([
      call('A','/title/crdt',{state:encode(A.save(one))},'PATCH'),
      call('B','/title/crdt',{state:encode(A.save(two))},'PATCH')
    ]);
    const merged = await call('A');
    if (merged.title !== '부산 여행 1일차') throw new Error('concurrent merge did not converge');
    await call('A','/date',{value:'2026-10-01',expectedVersion:0,mode:'DRAFT'});
    const date = await call('B','/date',{value:'2026-10-02',expectedVersion:0,mode:'DRAFT'});
    if (date.date !== '2026-10-02') throw new Error('last date save failed');
    const target = initial.photos.at(-1).id;
    const moved = await call('A',`/photos/${target}/move`,{
      nextBundlePhotoId:initial.photos[0].id, mode:'DRAFT'},'POST');
    if (moved.photos[0].id !== target) throw new Error('ID move failed');
    return {crdt:'JVM/JS browser concurrent merge passed', title:merged.title,
      lastStoredDate:date.date,photoMove:true,photoCount:moved.photos.length};
  });
}
