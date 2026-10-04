import test from 'node:test';
import assert from 'node:assert/strict';
import { fetchRecent, fetchPost, fetchHistory } from '../src/api.js';

const postFixture = { id: 'a', status: 'PENDING_REVIEW', contentVersion: 1, revision: 0 };
const historyFixture = { status: 'GENERATING', contentVersion: 1, revision: 0 };

function withFetch(mock, action) {
  const original = globalThis.fetch;
  globalThis.fetch = mock;
  return Promise.resolve().then(action).finally(() => { globalThis.fetch = original; });
}

test('all endpoints use only same-origin GET with token in header and no cache', async () => {
  const calls = [];
  await withFetch(async (url, options) => {
    calls.push({ url, options });
    return Response.json(url.endsWith('/history') ? [historyFixture] : url.includes('?') ? [postFixture] : postFixture);
  }, async () => {
    await fetchRecent({ token: 'private-token' });
    await fetchPost({ token: 'private-token', id: 'a/b?c' });
    await fetchHistory({ token: 'private-token', id: 'a/b?c' });
  });
  assert.deepEqual(calls.map(call => call.url), [
    '/api/x/posts?limit=30', '/api/x/posts/a%2Fb%3Fc', '/api/x/posts/a%2Fb%3Fc/history',
  ]);
  for (const { url, options } of calls) {
    assert.equal(options.method, 'GET');
    assert.equal(options.headers['X-X-Admin-Token'], 'private-token');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.credentials, 'same-origin');
    assert.ok(options.signal instanceof AbortSignal);
    assert.ok(!url.includes('private-token'));
    assert.ok(!('body' in options));
  }
});

test('missing token and invalid IDs/limits fail before any request', async () => {
  await withFetch(() => { assert.fail('request must not start'); }, async () => {
    await assert.rejects(fetchRecent(), { code: 'TOKEN_REQUIRED' });
    await assert.rejects(fetchRecent({ token: 'x', limit: 101 }), { code: 'LIMIT_INVALID' });
    await assert.rejects(fetchRecent({ token: 'x', limit: 1.5 }), { code: 'LIMIT_INVALID' });
    await assert.rejects(fetchPost({ token: 'x', id: ' ' }), { code: 'ID_REQUIRED' });
    await assert.rejects(fetchRecent({ token: 'x\r\ny' }), { code: 'TOKEN_INVALID' });
  });
});

test('HTTP failures give clear messages without exposing the admin token', async () => {
  for (const [status, code] of [[401, 'UNAUTHORIZED'], [403, 'UNAUTHORIZED'], [404, 'NOT_FOUND'], [500, 'HTTP_ERROR']]) {
    await withFetch(async () => new Response('private-token', { status }), async () => {
      await assert.rejects(fetchRecent({ token: 'private-token' }), error => {
        assert.equal(error.code, code);
        assert.ok(!error.message.includes('private-token'));
        return true;
      });
    });
  }
  await withFetch(async () => Response.json({ error: 'X post not found' }, { status: 400 }), async () => {
    await assert.rejects(fetchPost({ token: 'x', id: 'missing' }), { code: 'POST_NOT_FOUND' });
  });
});

test('HTML, malformed JSON, and unexpected shapes are rejected', async () => {
  for (const response of [
    new Response('<html>fallback</html>', { headers: { 'content-type': 'text/html' } }),
    new Response('{bad}', { headers: { 'content-type': 'application/json' } }),
    Response.json({ items: [] }),
    Response.json(null),
  ]) {
    await withFetch(async () => response, async () => {
      await assert.rejects(fetchRecent({ token: 'x' }), { code: 'INVALID_RESPONSE' });
    });
  }
  await withFetch(async () => Response.json([]), async () => {
    await assert.rejects(fetchPost({ token: 'x', id: 'a' }), { code: 'INVALID_RESPONSE' });
  });
  await withFetch(async () => Response.json({}), async () => {
    await assert.rejects(fetchHistory({ token: 'x', id: 'a' }), { code: 'INVALID_RESPONSE' });
  });
});

test('post and list records require safe minimum fields without rejecting future states', async () => {
  const invalidPosts = [
    null, [], {}, { ...postFixture, id: '' }, { ...postFixture, id: ' ' },
    { ...postFixture, status: '' }, { ...postFixture, status: ' ' },
    { ...postFixture, contentVersion: 0 }, { ...postFixture, contentVersion: 1.5 },
    { ...postFixture, revision: -1 }, { ...postFixture, revision: '0' },
  ];
  for (const record of invalidPosts) {
    await withFetch(async () => Response.json(record), async () => {
      await assert.rejects(fetchPost({ token: 'x', id: 'a' }), { code: 'INVALID_RESPONSE' });
    });
    await withFetch(async () => Response.json([record]), async () => {
      await assert.rejects(fetchRecent({ token: 'x' }), { code: 'INVALID_RESPONSE' });
    });
  }
  for (const record of [null, [], {}, { ...historyFixture, revision: -1 }, { ...historyFixture, contentVersion: 0 }]) {
    await withFetch(async () => Response.json([record]), async () => {
      await assert.rejects(fetchHistory({ token: 'x', id: 'a' }), { code: 'INVALID_RESPONSE' });
    });
  }
  const futurePost = { ...postFixture, status: 'NEW_BACKEND_STATE' };
  await withFetch(async () => Response.json(futurePost), async () => {
    assert.deepEqual(await fetchPost({ token: 'x', id: 'a' }), futurePost);
  });
  await withFetch(async () => Response.json([historyFixture]), async () => {
    assert.deepEqual(await fetchHistory({ token: 'x', id: 'a' }), [historyFixture]);
  });
});

test('network errors are readable and do not echo transport diagnostics', async () => {
  await withFetch(async () => { throw new TypeError('private-token is invalid'); }, async () => {
    await assert.rejects(fetchRecent({ token: 'private-token' }), error => {
      assert.equal(error.code, 'NETWORK_ERROR');
      assert.ok(!error.message.includes('private-token'));
      return true;
    });
  });
});

test('caller cancellation reaches transport and is distinguishable from failure', async () => {
  const controller = new AbortController();
  await withFetch((_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  }), async () => {
    const pending = fetchRecent({ token: 'x', signal: controller.signal });
    controller.abort();
    await assert.rejects(pending, { name: 'AbortError' });
  });
});

test('request timeout aborts transport, reports timeout, and removes timers', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  await withFetch((_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  }), async () => {
    const pending = fetchRecent({ token: 'x' });
    t.mock.timers.tick(15_000);
    await assert.rejects(pending, { code: 'TIMEOUT' });
  });
});

test('cancellation while parsing JSON remains cancellation for success and error bodies', async () => {
  for (const status of [200, 500]) {
    const controller = new AbortController();
    let parsingStarted;
    const started = new Promise(resolve => { parsingStarted = resolve; });
    await withFetch(async (_url, { signal }) => ({
      status, ok: status === 200, headers: new Headers({ 'content-type': 'application/json' }),
      json: () => {
        parsingStarted();
        return new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
        });
      },
    }), async () => {
      const pending = fetchRecent({ token: 'x', signal: controller.signal });
      await started;
      controller.abort();
      await assert.rejects(pending, { name: 'AbortError' });
    });
  }
});

test('timeout while parsing JSON is not masked by a response format error', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let parsingStarted;
  const started = new Promise(resolve => { parsingStarted = resolve; });
  await withFetch(async (_url, { signal }) => ({
    status: 200, ok: true, headers: new Headers({ 'content-type': 'application/json' }),
    json: () => {
      parsingStarted();
      return new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
      });
    },
  }), async () => {
    const pending = fetchRecent({ token: 'x' });
    await started;
    t.mock.timers.tick(15_000);
    await assert.rejects(pending, { code: 'TIMEOUT' });
  });
});
