const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test } = require('node:test');
const { buildRequest, triggerCnb, main } = require('../scripts/trigger-cnb.cjs');

const token = 'synthetic-CNB-token-do-not-log';
const sha = 'a'.repeat(40);
const sn = 'cnb-ah1-1k5phlmac';
const url = `https://cnb.cool/tiancia/trade-ci/-/build/logs/${sn}`;
const base = (changes = {}) => ({ GITHUB_REPOSITORY: 'tiancia/trade', GITHUB_REF: 'refs/heads/main',
  GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_SHA: sha, GITHUB_RUN_ID: '123', CNB_TOKEN: token,
  SERVICE: 'all', MODE: 'build', EXISTING_RELEASE_MANIFEST: '', ...changes });
const response = (changes = {}) => ({ ok: true, status: 200,
  json: async () => ({ success: true, sn, buildLogUrl: url, message: token }), ...changes });

test('manual build submits the fixed main source with safe API options and no token in its payload', async () => {
  let request;
  const result = await triggerCnb({ env: base(),
  fetchImpl: async (...args) => { request = args; return response(); } });
  assert.equal(request[0], 'https://api.cnb.cool/tiancia/trade-ci/-/build/start');
  assert.equal(request[1].method, 'POST');
  assert.equal(request[1].redirect, 'error');
  assert.equal(request[1].headers.Authorization, `Bearer ${token}`);
  assert.equal(request[1].headers.Accept, 'application/vnd.cnb.api+json');
  assert.ok(request[1].signal instanceof AbortSignal);
  const body = JSON.parse(request[1].body);
  assert.deepEqual(body.env, { SOURCE_SHA: sha, CI_RUN_ID: '123', SERVICE: 'all', BUILD_REQUESTED: 'true',
    DEPLOY_REQUESTED: 'false', CI_ACTION: 'release', EXISTING_RELEASE_MANIFEST: '',
    DEPLOY_ROOT: '/opt/trade', ACCESS_MODE: 'private' });
  assert.equal(body.branch, 'main');
  assert.equal(body.event, 'api_trigger_trade');
  assert.equal(body.sync, 'false');
  assert.ok(!JSON.stringify(body).includes(token));
  assert.equal(result.sn, sn);
});

test('four modes determine CNB action and build/deploy flags independently of legacy checkboxes', () => {
  for (const [mode, build, deploy, action] of [
    ['build', 'true', 'false', 'release'],
    ['build_deploy', 'true', 'true', 'release'],
    ['deploy', 'false', 'true', 'release'],
    ['rollback', 'false', 'true', 'rollback'],
  ]) {
    const request = buildRequest(base({ MODE: mode, BUILD_REQUESTED: 'obsolete', DEPLOY_REQUESTED: 'obsolete',
      EXISTING_RELEASE_MANIFEST: mode === 'deploy' ? 'RELEASE_VERSION=historic\n' : '' }));
    assert.equal(request.env.BUILD_REQUESTED, build, mode);
    assert.equal(request.env.DEPLOY_REQUESTED, deploy, mode);
    assert.equal(request.env.CI_ACTION, action, mode);
    assert.ok(request.title.includes(`: ${mode} all `));
  }
});

test('rollback needs no manifest and ignores stale build flags and historical manifest input', () => {
  for (const changes of [{}, { BUILD_REQUESTED: 'true', DEPLOY_REQUESTED: 'false',
    EXISTING_RELEASE_MANIFEST: 'RELEASE_VERSION=old\n' }]) {
    const request = buildRequest(base({ ...changes, MODE: 'rollback' }));
    assert.equal(request.env.BUILD_REQUESTED, 'false');
    assert.equal(request.env.DEPLOY_REQUESTED, 'true');
    assert.equal(request.env.CI_ACTION, 'rollback');
    assert.equal(request.env.EXISTING_RELEASE_MANIFEST, '');
  }
});

test('manual selections and multiline history are transported as JSON without shell evaluation', () => {
  const manifest = 'RELEASE_VERSION=historic\nBACKEND_IMAGE=registry/backend:historic\n';
  const request = buildRequest(base({ SERVICE: 'backend', MODE: 'deploy',
    EXISTING_RELEASE_MANIFEST: manifest }));
  assert.equal(request.env.EXISTING_RELEASE_MANIFEST, manifest);
  assert.equal(request.env.SERVICE, 'backend');
  assert.equal(request.env.BUILD_REQUESTED, 'false');
  assert.equal(request.env.DEPLOY_REQUESTED, 'true');
  assert.equal(JSON.parse(JSON.stringify(request)).env.EXISTING_RELEASE_MANIFEST, manifest);
  for (const service of ['all', 'backend', 'cockpit', 'x-console', 'frontends', 'egress']) {
    assert.equal(buildRequest(base({ SERVICE: service })).env.SERVICE, service);
  }
});

test('untrusted sources and invalid selections fail before any HTTP request', async () => {
  const invalid = [
    { GITHUB_REPOSITORY: 'another/trade' }, { GITHUB_REF: 'refs/heads/feature' },
    { GITHUB_EVENT_NAME: 'push' }, { GITHUB_EVENT_NAME: 'pull_request' },
    { GITHUB_EVENT_NAME: 'repository_dispatch' }, { GITHUB_SHA: 'main' }, { GITHUB_SHA: 'A'.repeat(40) },
    { GITHUB_RUN_ID: '0' }, { GITHUB_RUN_ID: '1'.repeat(21) }, { CNB_TOKEN: '' }, { CNB_TOKEN: 'bad\r\nheader' },
    { SERVICE: 'backend;echo injected' }, { MODE: 'invalid' }, { MODE: '' }, { MODE: undefined },
    { MODE: 'deploy', EXISTING_RELEASE_MANIFEST: '' },
    { MODE: 'deploy', EXISTING_RELEASE_MANIFEST: '   ' },
    { EXISTING_RELEASE_MANIFEST: 'stale' }, { MODE: 'build_deploy', EXISTING_RELEASE_MANIFEST: 'stale' },
    { EXISTING_RELEASE_MANIFEST: 'x'.repeat(16385) },
    { EXISTING_RELEASE_MANIFEST: 'null\0byte' },
  ];
  for (const changes of invalid) {
    let called = false;
    await assert.rejects(triggerCnb({ env: base(changes), fetchImpl: async () => { called = true; return response(); } }));
    assert.equal(called, false, JSON.stringify(changes));
  }
});

test('HTTP, transport and invalid response failures expose no token or response body and never retry', async () => {
  const cases = [
    async () => response({ ok: false, status: 403, json: async () => ({ token }) }),
    async () => { throw new Error('request Authorization=' + token); },
    async () => response({ json: async () => { throw new Error(token); } }),
    async () => response({ json: async () => ({ success: false, message: token }) }),
    async () => response({ json: async () => ({ success: true, sn, buildLogUrl: 'https://other.example/' + token }) }),
    async () => response({ json: async () => ({ success: true, sn: token, buildLogUrl: url }) }),
    async () => response({ json: async () => ({ success: true }) }),
  ];
  for (const mock of cases) {
    const output = [];
    let requests = 0;
    const status = await main({ env: base(), fetchImpl: async (...args) => { requests++; return mock(...args); },
      log: (text) => output.push(text), error: (text) => output.push(text) });
    assert.equal(status, 1);
    assert.equal(requests, 1);
    assert.ok(!output.join('\n').includes(token));
    assert.ok(!output.join('\n').includes('任务已提交'));
  }
});

test('a stalled POST times out once and reports an uncertain submission without automatic retries', async () => {
  let requests = 0;
  await assert.rejects(triggerCnb({ env: base(), timeoutMs: 10, fetchImpl: async (_url, options) => {
    requests++;
    await new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error(token)), { once: true }));
  } }), /inspect the CNB run list before retrying/);
  assert.equal(requests, 1);
});

test('successful submission logs and summary distinguish acceptance from downstream completion', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-cnb-summary-'));
  const summary = path.join(directory, 'summary.md');
  const output = [];
  try {
    const status = await main({ env: base({ GITHUB_STEP_SUMMARY: summary }), fetchImpl: async () => response(),
      log: (text) => output.push(text), error: (text) => output.push(text) });
    assert.equal(status, 0);
    assert.ok(output.some((text) => text.includes('任务已提交')));
    assert.ok(output.some((text) => text.includes('submission only')));
    const text = fs.readFileSync(summary, 'utf8');
    assert.ok(text.includes(url));
    assert.ok(text.includes(sha));
    assert.ok(text.includes('submission only'));
    assert.ok(!text.includes(token));
    assert.ok(!output.join('\n').includes(token));
  } finally {
    // Only one known file is created; avoid recursive cleanup outside the workspace.
    fs.rmSync(summary, { force: true });
    fs.rmdirSync(directory);
  }
});

test('a summary write failure does not report an accepted CNB task as a failed submission', async () => {
  const output = [];
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-cnb-unwritable-summary-'));
  try {
    const status = await main({ env: base({ GITHUB_STEP_SUMMARY: directory }), fetchImpl: async () => response(),
      log: (text) => output.push(text), error: (text) => output.push(text) });
    assert.equal(status, 0);
    assert.ok(output.some((text) => text.includes('summary could not be written')));
    assert.ok(!output.join('\n').includes(token));
  } finally {
    fs.rmdirSync(directory);
  }
});
