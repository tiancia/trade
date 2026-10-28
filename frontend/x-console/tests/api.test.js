import test from 'node:test';
import assert from 'node:assert/strict';
import { fetchRecent, fetchPost, fetchHistory, fetchXTask, setXTaskRunning } from '../src/api.js';

const postFixture = { id: 'a', status: 'PENDING_REVIEW', contentVersion: 1, revision: 0 };
const historyFixture = { status: 'GENERATING', contentVersion: 1, revision: 0 };

test('X task control reads status and sends protected start and stop requests', async () => {
  const calls = [];
  await withFetch(async (url, options) => {
    calls.push({ url, options });
    return Response.json({ id: 'x', running: url.endsWith('/start'), autoStart: false });
  }, async () => {
    assert.equal((await fetchXTask()).running, false);
    assert.equal((await setXTaskRunning(true)).running, true);
    assert.equal((await setXTaskRunning(false)).running, false);
    await assert.rejects(setXTaskRunning('true'), { code: 'STATE_INVALID' });
  });
  assert.deepEqual(calls.map(call => [call.url, call.options.method]), [
    ['/api/automation/tasks/x', 'GET'], ['/api/automation/tasks/x/start', 'POST'], ['/api/automation/tasks/x/stop', 'POST'],
  ]);
  for (const call of calls.slice(1)) {
    assert.equal(call.options.headers['X-Trade-CSRF'], '1');
    assert.equal(call.options.credentials, 'same-origin');
  }
});

test('X task status must identify the task and contain boolean state', async () => {
  for (const data of [null, {}, { id: 'weibo', running: true, autoStart: false }, { id: 'x', running: 'true', autoStart: false }]) {
    await withFetch(async () => Response.json(data), async () => {
      await assert.rejects(fetchXTask(), { code: 'INVALID_RESPONSE' });
    });
  }
});

function withFetch(mock, action) {
  const original = globalThis.fetch;
  globalThis.fetch = mock;
  return Promise.resolve().then(action).finally(() => { globalThis.fetch = original; });
}

test('all endpoints work without a token using same-origin GET and no cache', async () => {
  const calls = [];
  await withFetch(async (url, options) => {
    calls.push({ url, options });
    return Response.json(url.endsWith('/history') ? [historyFixture] : url.includes('?') ? [postFixture] : postFixture);
  }, async () => {
    await fetchRecent();
    await fetchPost({ id: 'a/b?c' });
    await fetchHistory({ id: 'a/b?c' });
  });
  assert.deepEqual(calls.map(call => call.url), [
    '/api/x/posts?limit=30', '/api/x/posts/a%2Fb%3Fc', '/api/x/posts/a%2Fb%3Fc/history',
  ]);
  for (const { url, options } of calls) {
    assert.equal(options.method, 'GET');
    assert.deepEqual(options.headers, { Accept: 'application/json' });
    assert.equal(options.cache, 'no-store');
    assert.equal(options.credentials, 'same-origin');
    assert.ok(options.signal instanceof AbortSignal);
    assert.ok(!('body' in options));
  }
});

test('invalid IDs and limits fail before any request', async () => {
  await withFetch(() => { assert.fail('request must not start'); }, async () => {
    await assert.rejects(fetchRecent({ limit: 101 }), { code: 'LIMIT_INVALID' });
    await assert.rejects(fetchRecent({ limit: 1.5 }), { code: 'LIMIT_INVALID' });
    await assert.rejects(fetchPost({ id: ' ' }), { code: 'ID_REQUIRED' });
  });
});

test('HTTP failures give clear messages without echoing response bodies', async () => {
  for (const [status, code] of [[401, 'UNAUTHORIZED'], [403, 'UNAUTHORIZED'], [404, 'NOT_FOUND'], [500, 'HTTP_ERROR']]) {
    await withFetch(async () => new Response('private-token', { status }), async () => {
      await assert.rejects(fetchRecent(), error => {
        assert.equal(error.code, code);
        assert.ok(!error.message.includes('private-token'));
        return true;
      });
    });
  }
  await withFetch(async () => Response.json({ error: 'X post not found' }, { status: 400 }), async () => {
    await assert.rejects(fetchPost({ id: 'missing' }), { code: 'POST_NOT_FOUND' });
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
      await assert.rejects(fetchRecent(), { code: 'INVALID_RESPONSE' });
    });
  }
  await withFetch(async () => Response.json([]), async () => {
    await assert.rejects(fetchPost({ id: 'a' }), { code: 'INVALID_RESPONSE' });
  });
  await withFetch(async () => Response.json({}), async () => {
    await assert.rejects(fetchHistory({ id: 'a' }), { code: 'INVALID_RESPONSE' });
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
      await assert.rejects(fetchPost({ id: 'a' }), { code: 'INVALID_RESPONSE' });
    });
    await withFetch(async () => Response.json([record]), async () => {
      await assert.rejects(fetchRecent(), { code: 'INVALID_RESPONSE' });
    });
  }
  for (const record of [null, [], {}, { ...historyFixture, revision: -1 }, { ...historyFixture, contentVersion: 0 }]) {
    await withFetch(async () => Response.json([record]), async () => {
      await assert.rejects(fetchHistory({ id: 'a' }), { code: 'INVALID_RESPONSE' });
    });
  }
  const futurePost = { ...postFixture, status: 'NEW_BACKEND_STATE' };
  await withFetch(async () => Response.json(futurePost), async () => {
    assert.deepEqual(await fetchPost({ id: 'a' }), futurePost);
  });
  await withFetch(async () => Response.json([historyFixture]), async () => {
    assert.deepEqual(await fetchHistory({ id: 'a' }), [historyFixture]);
  });
});

test('network errors are readable and do not echo transport diagnostics', async () => {
  await withFetch(async () => { throw new TypeError('private-token is invalid'); }, async () => {
    await assert.rejects(fetchRecent(), error => {
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
    const pending = fetchRecent({ signal: controller.signal });
    controller.abort();
    await assert.rejects(pending, { name: 'AbortError' });
  });
});

test('request timeout aborts transport, reports timeout, and removes timers', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  await withFetch((_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  }), async () => {
    const pending = fetchRecent();
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
      const pending = fetchRecent({ signal: controller.signal });
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
    const pending = fetchRecent();
    await started;
    t.mock.timers.tick(15_000);
    await assert.rejects(pending, { code: 'TIMEOUT' });
  });
});
