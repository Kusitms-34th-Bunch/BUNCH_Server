import test from 'node:test';
import assert from 'node:assert/strict';
import {EditingSessions, DragLists, dropIntent, otherEditors, locationFailure} from './interaction.mjs';

test('closing a picker retains upload and another field until each own lifecycle ends', () => {
  const signals = [];
  const editing = new EditingSessions((action, target) => signals.push({action, target}));
  editing.start('photo-sheet', 'PHOTOS');
  editing.start('gallery-picker', 'PHOTOS');
  editing.start('upload', 'PHOTOS');
  editing.start('date-picker', 'DATE');
  editing.end('gallery-picker');
  editing.end('photo-sheet');
  assert.deepEqual(editing.targets(), ['PHOTOS', 'DATE']);
  assert.deepEqual(signals, [{action:'BEGIN', target:'PHOTOS'}, {action:'BEGIN', target:'DATE'}]);
  editing.end('date-picker');
  assert.deepEqual(editing.targets(), ['PHOTOS']);
  editing.end('upload');
  assert.deepEqual(signals.slice(-2), [{action:'END', target:'DATE'}, {action:'END', target:'PHOTOS'}]);
});

test('focus and picks cannot produce editing, replacement changes only its own target', () => {
  const signals = [];
  const editing = new EditingSessions((action, target) => signals.push({action,target}));
  for (const target of ['TITLE', 'COMMENT', 'PICK:9']) editing.start('focus', target);
  assert.deepEqual(editing.targets(), []);
  editing.start('upload', 'PHOTOS');
  editing.start('location-sheet', 'LOCATION:7');
  editing.start('location-sheet', 'LOCATION:8');
  assert.deepEqual(editing.targets(), ['PHOTOS', 'LOCATION:8']);
  editing.clear();
  assert.deepEqual(editing.targets(), []);
  assert.deepEqual(signals.slice(-2), [{action:'END',target:'PHOTOS'}, {action:'END',target:'LOCATION:8'}]);
});

test('drag buffers only its list through content and snapshot recovery; release gets latest rows', () => {
  const lists = new DragLists();
  const original = [{id:1,name:'old'}, {id:2,name:'two'}];
  lists.start('locations', original);
  const remote = [{id:2,name:'two'}, {id:1,name:'new'}];
  assert.deepEqual(lists.visible('locations', remote), original);
  assert.deepEqual(lists.visible('photos', [{id:9}]), [{id:9}]);
  assert.deepEqual(dropIntent('locations', 1, 2, original), {
    targetLocationId:1, previousLocationId:null, nextLocationId:2
  });
  lists.finish('locations');
  assert.deepEqual(lists.visible('locations', remote), remote);
});

test('a drop keeps identity and original anchors and reports a deleted target without resurrecting it', () => {
  const rows = [{id:1}, {id:2}, {id:3}];
  assert.deepEqual(dropIntent('photos', 3, 1, rows), {
    targetBundlePhotoId:3, previousBundlePhotoId:null, nextBundlePhotoId:1
  });
  assert.equal(dropIntent('locations', 7, 1, rows), null);
  assert.equal(dropIntent('locations', 1, 1, rows), null);
});

test('all active other editors are deduplicated; typing expires independently and picks are excluded', () => {
  const presence = {editing:[{memberId:1,target:'PHOTOS',expiresAt:'2026-11-12T00:00:15Z'},
    {memberId:2,target:'PHOTOS',expiresAt:'2026-11-12T00:00:15Z'},
    {memberId:2,target:'DATE',expiresAt:'2026-11-12T00:00:15Z'},
    {memberId:3,target:'PICK:1',expiresAt:'2026-11-12T00:00:15Z'}],
    typing:[{memberId:4,target:'TITLE',expiresAt:'2026-11-12T00:00:03Z'}]};
  assert.deepEqual(otherEditors(presence, 1, Date.parse('2026-11-12T00:00:01Z')).map(p => p.memberId), [2,4]);
  assert.deepEqual(otherEditors(presence, 1, Date.parse('2026-11-12T00:00:04Z')).map(p => p.memberId), [2]);
  assert.equal(otherEditors(presence, 1, Date.parse('2026-11-12T00:00:16Z')).length, 0);
});

test('location rejection reasons have distinct user messages', () => {
  assert.equal(locationFailure({reason:'LOCATION_REMOVED'}), '다른 멤버가 삭제한 위치예요.');
  assert.notEqual(locationFailure({reason:'LOCATION_DUPLICATE'}), locationFailure({reason:'LOCATION_CAPACITY_EXCEEDED'}));
});

test('a deleted edited row falls back to add presence and deleted drag stops renewing without closing the search', () => {
  const signals = [];
  const editing = new EditingSessions((action, target) => signals.push({action,target}));
  editing.start('upload', 'PHOTOS');
  editing.start('location-sheet', 'LOCATION:7');
  editing.start('drag', 'PHOTO:9');
  editing.reconcile({locations:[], photos:[]});
  assert.deepEqual(editing.targets(), ['PHOTOS', 'LOCATION']);
  assert.deepEqual(signals.slice(-3), [{action:'END',target:'LOCATION:7'},
    {action:'BEGIN',target:'LOCATION'}, {action:'END',target:'PHOTO:9'}]);
});
