// A target stays active while any of its UI flows (including an upload) is active.
export class EditingSessions {
  constructor(signal) { this.flows = new Map(); this.signal = signal; }
  targets() { return [...new Set(this.flows.values())]; }
  start(flow, target) {
    if (!/^(PHOTOS|DATE|LOCATION(?::[1-9]\d*)?|PHOTO:[1-9]\d*)$/.test(target)) return;
    if (this.flows.get(flow) === target) return;
    this.end(flow);
    const active = this.targets().includes(target);
    this.flows.set(flow, target);
    if (!active) this.signal('BEGIN', target);
  }
  end(flow) {
    const target = this.flows.get(flow);
    this.flows.delete(flow);
    if (target && !this.targets().includes(target)) this.signal('END', target);
  }
  reconcile(snapshot) {
    for (const [flow, target] of [...this.flows]) {
      const item = /^(PHOTO|LOCATION):([1-9]\d*)$/.exec(target);
      if (!item) continue;
      const list = item[1] === 'PHOTO' ? 'photos' : 'locations';
      if ((snapshot?.[list] ?? []).some(row => String(row.id) === item[2])) continue;
      if (flow === 'location-sheet') this.start(flow, 'LOCATION');
      else this.end(flow);
    }
  }
  clear() { for (const flow of [...this.flows.keys()]) this.end(flow); }
}

// Content revisions always advance. Only the list being manipulated stays visually frozen.
export class DragLists {
  frozen = new Map();
  start(list, rows) { this.frozen.set(list, structuredClone(rows)); }
  visible(list, latest) { return this.frozen.get(list) ?? latest; }
  finish(list) { this.frozen.delete(list); }
  clear() { this.frozen.clear(); }
}

export function dropIntent(list, target, before, rows) {
  if (String(target) === String(before) || !rows.some(row => String(row.id) === String(target))) return null;
  const rest = rows.filter(row => String(row.id) !== String(target));
  const index = before == null ? rest.length : rest.findIndex(row => String(row.id) === String(before));
  if (index < 0) return null;
  const names = list === 'photos'
    ? ['targetBundlePhotoId','previousBundlePhotoId','nextBundlePhotoId']
    : ['targetLocationId','previousLocationId','nextLocationId'];
  return Object.fromEntries([[names[0], Number(target)],
    [names[1], rest[index - 1]?.id ?? null], [names[2], rest[index]?.id ?? null]]);
}

export function otherEditors(presence, ownId, now = Date.now()) {
  const editing = (presence?.editing ?? []).filter(row => !/^(PICK:|TITLE$|COMMENT$)/.test(row.target));
  const active = [...editing, ...(presence?.typing ?? [])].filter(row =>
    String(row.memberId) !== String(ownId) && Date.parse(row.expiresAt) > now);
  return [...new Map(active.map(row => [String(row.memberId), row])).values()];
}

export function locationFailure(detail) {
  return {
    LOCATION_REMOVED:'다른 멤버가 삭제한 위치예요.',
    LOCATION_DUPLICATE:'이미 추가한 위치예요.',
    LOCATION_CAPACITY_EXCEEDED:'위치는 최대 3개까지 추가할 수 있어요.'
  }[detail?.reason] ?? '위치 저장에 실패했어요. 최신 상태를 확인해 주세요.';
}
