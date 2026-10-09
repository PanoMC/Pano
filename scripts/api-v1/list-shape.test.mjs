// node --test scripts/api-v1/list-shape.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { declaresPaging, pageProblems } from './list-shape.mjs';

const page = { number: 1, size: 20, totalItems: 1, totalPages: 1 };

test('unpaged endpoint: items without page passes (decision 80)', () => {
  assert.deepEqual(pageProblems({ items: [1] }, false), []);
});
test('paged endpoint: items without page fails', () => {
  assert.deepEqual(pageProblems({ items: [1] }, true), ['no `page` object']);
  assert.deepEqual(pageProblems({ items: [1] }), ['no `page` object']);
});
test('paged endpoint with a valid page passes; a broken page fails even when unpaged', () => {
  assert.deepEqual(pageProblems({ items: [1], page }, true), []);
  assert.ok(pageProblems({ items: [1], page: { number: 1 } }, false).length > 0);
});
test('a list under another key fails, paged or not', () => {
  for (const key of ['posts', 'bans', 'sessions', 'data']) {
    assert.ok(pageProblems({ [key]: [1] }, false).some((p) => /no `items` array/.test(p)), key);
    assert.ok(pageProblems({ [key]: [1] }, true).length > 0, key);
  }
});
test('legacy paging keys and bare arrays still fail', () => {
  assert.ok(pageProblems({ items: [], totalPage: 1 }, false).length > 0);
  assert.ok(pageProblems([1], false).length > 0);
});
test('non-list objects are not lists', () => {
  assert.deepEqual(pageProblems({ a: [1], b: [2] }, false), []);
});
test('declaresPaging', () => {
  for (const n of ['page', 'pageSize', 'limit', 'cursor', 'offset', 'ticketsPage']) assert.equal(declaresPaging([n]), true, n);
  for (const n of ['search', 'status', 'type']) assert.equal(declaresPaging([n]), false, n);
});
