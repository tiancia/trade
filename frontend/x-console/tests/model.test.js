import test from 'node:test';
import assert from 'node:assert/strict';
import { STATUS_META, formatTime, reviewSummary, publishSummary, filterPosts, getStats, publishedUrl } from '../src/model.js';

test('every backend workflow status is mapped to a readable label and stage', () => {
  assert.deepEqual(Object.keys(STATUS_META), [
    'GENERATING', 'GENERATION_FAILED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED',
    'EXPIRED', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'UNKNOWN',
  ]);
  assert.equal(STATUS_META.UNKNOWN.label, '发布结果待核对');
  assert.equal(STATUS_META.APPROVED.stage, 'review');
  assert.equal(STATUS_META.PUBLISHED.stage, 'publish');
});

test('review outcomes remain independent of publishing outcomes and expiration', () => {
  const audit = { reviewer: 'operator', reviewedAt: '2026-10-04T01:00:00Z' };
  for (const status of ['APPROVED', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'UNKNOWN', 'EXPIRED']) {
    assert.deepEqual(reviewSummary({ ...audit, status }), { label: '已通过', tone: 'success' });
  }
  assert.equal(reviewSummary({ ...audit, status: 'REJECTED' }).label, '已拒绝');
  assert.equal(reviewSummary({ status: 'EXPIRED' }).label, '未审核 · 已过期');
  assert.equal(reviewSummary({ status: 'PENDING_REVIEW' }).label, '等待审核');
  assert.equal(reviewSummary({ status: 'GENERATION_FAILED' }).label, '尚未送审');
  assert.equal(reviewSummary({ reviewer: 'previous-reviewer', status: 'PENDING_REVIEW' }).label, '等待审核');
  assert.equal(publishSummary({ status: 'APPROVED' }).label, '等待发布');
  assert.equal(publishSummary({ status: 'UNKNOWN' }).label, '结果待核对');
  assert.equal(publishSummary({ status: 'PUBLISHED' }).label, '发布成功');
});

test('time is always shown in China timezone and invalid timestamps have a placeholder', () => {
  assert.equal(formatTime('2026-10-03T16:00:00Z'), '2026/10/04 00:00');
  assert.equal(formatTime(null), '—');
  assert.equal(formatTime(''), '—');
  assert.equal(formatTime('not a date'), '—');
});

test('search and status filtering preserve input and sort newest deterministically', () => {
  const posts = [
    { id: 'b', body: 'Alpha', status: 'PUBLISHED', createdAt: '2026-10-04T00:00:00Z', postId: '123' },
    { id: 'a', reviewer: 'Beta', status: 'PENDING_REVIEW', createdAt: '2026-10-04T00:00:00Z' },
    { id: 'c', reviewReason: 'Gamma', status: 'FAILED', createdAt: '2026-10-03T00:00:00Z' },
    { id: 'd', status: 'UNKNOWN', createdAt: 'bad date' },
  ];
  assert.deepEqual(filterPosts(posts).map(post => post.id), ['a', 'b', 'c', 'd']);
  assert.deepEqual(posts.map(post => post.id), ['b', 'a', 'c', 'd']);
  assert.deepEqual(filterPosts(posts, { query: ' ALPHA ' }).map(post => post.id), ['b']);
  assert.equal(filterPosts(posts, { query: 'beta' })[0].id, 'a');
  assert.equal(filterPosts(posts, { query: 'gamma' })[0].id, 'c');
  assert.equal(filterPosts(posts, { query: '123' })[0].id, 'b');
  assert.equal(filterPosts(posts, { query: 'b' }).length, 2);
  assert.deepEqual(filterPosts(posts, { status: 'FAILED' }).map(post => post.id), ['c']);
});

test('stats count confirmed publishing and operational failures separately', () => {
  const posts = Object.keys(STATUS_META).map(status => ({ status }));
  assert.deepEqual(getStats(posts), { total: 10, pendingReview: 1, published: 1, attention: 3 });
  assert.deepEqual(getStats([]), { total: 0, pendingReview: 0, published: 0, attention: 0 });
});

test('external link requires successful publication and backend-safe string ID', () => {
  assert.equal(publishedUrl({ status: 'PUBLISHED', postId: '1234567890123456789' }), 'https://x.com/i/status/1234567890123456789');
  for (const post of [
    { status: 'APPROVED', postId: '123' }, { status: 'UNKNOWN', postId: '123' },
    { status: 'PUBLISHED', postId: 'javascript:alert(1)' }, { status: 'PUBLISHED', postId: '0' },
    { status: 'PUBLISHED', postId: '12345678901234567890' }, { status: 'PUBLISHED', postId: 123 },
    { status: 'PUBLISHED', postId: '123/evil' }, { status: 'PUBLISHED', postId: '00123' },
  ]) assert.equal(publishedUrl(post), null);
});
