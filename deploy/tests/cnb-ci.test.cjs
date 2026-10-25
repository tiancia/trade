// Offline: only the actual CNB adapter runs; git, managed CI and network tools are local mocks.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test, after } = require('node:test');

const windows = process.platform === 'win32';
const bash = process.env.TEST_BASH || (windows ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const toBash = (file) => windows
  ? cp.execFileSync(bash, ['-c', 'cygpath -u "$1"', '--', file], { encoding: 'utf8' }).trim() : file;
let root, unixRoot;
if (windows) {
  [unixRoot, root] = cp.execFileSync(bash, ['-c',
    'task_root=$(mktemp -d /tmp/trade-cnb-tests.XXXXXX)\nprintf "%s\\n" "$task_root"\ncygpath -w "$task_root"',
  ], { encoding: 'utf8' }).trim().split(/\r?\n/);
} else {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-cnb-tests.'));
  unixRoot = root;
}
const ownedRoot = fs.realpathSync(root);
const node = toBash(process.execPath);
const adapter = fs.readFileSync(path.resolve(__dirname, '../scripts/cnb-ci.sh'), 'utf8').replace(/\r\n/g, '\n');
const sourceSha = 'a'.repeat(40);
const registry = 'ccr.ccs.tencentyun.com/tiancia-trade';
const username = 'synthetic-cnb-tcr-user';
const password = 'synthetic-cnb-password $with spaces';
const privateKey = '-----BEGIN PRIVATE KEY-----\nSYNTHETIC-CNB-PRIVATE-KEY\n-----END PRIVATE KEY-----';
const knownHosts = '[fixture.invalid]:2222 ssh-ed25519 SYNTHETIC-CNB-HOST-KEY';
const write = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, value, { mode: 0o755 });
};
let sequence = 0;

function mockTool() {
  const fs = require('node:fs');
  const path = require('node:path');
  const cp = require('node:child_process');
  const [tool, ...args] = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_CNB_STATE, 'utf8'));
  const record = (extra) => fs.appendFileSync(process.env.TEST_CNB_AUDIT, JSON.stringify({ tool, args, ...extra }) + '\n');
  const reject = (message) => { process.stderr.write(message + '\n'); process.exit(91); };
  const local = (file) => {
    if (!file.startsWith(state.unixRoot + '/')) reject('Mock file access escaped its owned fixture');
    return path.join(state.localRoot, file.slice(state.unixRoot.length + 1));
  };
  if (tool === 'git') {
    record({});
    if (!args.includes('rev-parse') || args.at(-1) !== 'HEAD') reject('Only the checked-out source SHA may be read');
    process.stdout.write(state.actualSha + '\n');
    return;
  }
  if (tool === 'chmod') {
    record({});
    const result = cp.spawnSync(state.bash, ['-c', '/usr/bin/chmod "$@"', '--', ...args], {
      env: process.env, stdio: 'inherit', timeout: 3000,
    });
    if (result.error) throw result.error;
    process.exit(result.status ?? 92);
  }
  if (tool !== 'managed') {
    record({});
    return reject('Docker and SSH are forbidden in adapter contract tests');
  }
  const environment = Object.fromEntries(['SOURCE_SHA', 'SERVICE', 'BUILD_REQUESTED', 'DEPLOY_REQUESTED', 'CI_ACTION',
    'CI_RUN_ID', 'CI_BUILDER_MODE', 'OUTPUT_DIR', 'BUILD_CACHE_DIR', 'DEPLOY_SSH_KEY_FILE', 'DEPLOY_KNOWN_HOSTS_FILE']
    .map((key) => [key, process.env[key] || '']));
  const needsSsh = environment.DEPLOY_REQUESTED === 'true'
    || environment.BUILD_REQUESTED === 'true' && environment.SERVICE !== 'all';
  const credentials = { usernameMatches: process.env.TCR_USERNAME === state.username,
    passwordMatches: process.env.TCR_PASSWORD === state.password,
    rawKeyRemoved: process.env.DEPLOY_SSH_PRIVATE_KEY === undefined,
    rawKnownHostsRemoved: process.env.DEPLOY_KNOWN_HOSTS === undefined };
  if (needsSsh) {
    for (const [fileVariable, expected] of [['DEPLOY_SSH_KEY_FILE', state.privateKey],
      ['DEPLOY_KNOWN_HOSTS_FILE', state.knownHosts]]) {
      const file = local(environment[fileVariable]);
      if (file.startsWith(state.repository + path.sep)) reject('Credential files entered the source checkout');
      if (fs.readFileSync(file, 'utf8').replace(/\r/g, '').replace(/\n$/, '') !== expected) reject('Incorrect secret-file transport');
      if (process.platform !== 'win32' && (fs.statSync(file).mode & 0o777) !== 0o600) reject('Credential permissions must be 0600');
    }
  }
  const version = environment.BUILD_REQUESTED === 'false' ? 'historical-release.1'
    : `sha-${environment.SOURCE_SHA}-cn-${environment.CI_RUN_ID}-123456abcdef`;
  record({ environment, credentials, version });
  if (state.produceManifest !== false && environment.CI_ACTION !== 'rollback') {
    let manifest = 'RELEASE_VERSION=' + version + '\n'
      + Object.entries({ BACKEND_IMAGE: 'backend', COCKPIT_IMAGE: 'cockpit', X_CONSOLE_IMAGE: 'x-console', EGRESS_IMAGE: 'egress' })
        .map(([key, service]) => `${key}=${state.registry}/${service}:${version}`).join('\n') + '\n';
    if (state.malformed === 'unknown-key') manifest += 'SECRET=' + state.password + '\n';
    if (state.malformed === 'missing-key') manifest = manifest.replace(/^EGRESS_IMAGE=.*\n/m, '');
    if (state.malformed === 'duplicate-key') manifest += 'BACKEND_IMAGE=duplicate\n';
    if (state.malformed === 'wrong-version') manifest = manifest.replace('RELEASE_VERSION=' + version, 'RELEASE_VERSION=other-version');
    fs.mkdirSync(local(environment.OUTPUT_DIR), { recursive: true });
    fs.writeFileSync(path.join(local(environment.OUTPUT_DIR), 'release.env'), manifest);
  }
  process.exit(state.managedStatus || 0);
}

function run(options = {}) {
  const name = 'case-' + (++sequence);
  const local = (file) => path.join(root, name, file);
  const posix = (file) => unixRoot + '/' + name + '/' + file;
  write(local('repository/deploy/scripts/cnb-ci.sh'), adapter);
  write(local('repository/deploy/scripts/managed-ci.sh'),
    '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" MSYS2_ENV_CONV_EXCL="*" exec "$TEST_NODE" "$TEST_CNB_MOCK" managed "$@"\n');
  write(local('mock.cjs'), '(' + mockTool.toString() + ')();\n');
  write(local('audit.jsonl'), '');
  write(local('state.json'), JSON.stringify({ ...options, unixRoot, localRoot: root, bash,
    actualSha: options.actualSha || sourceSha, repository: local('repository'), registry, username, password, privateKey, knownHosts }));
  for (const tool of ['git', 'docker', 'ssh', 'scp', 'chmod']) {
    write(local('bin/' + tool), '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" MSYS2_ENV_CONV_EXCL="*" exec "$TEST_NODE" "$TEST_CNB_MOCK" ' + tool + ' "$@"\n');
  }
  fs.mkdirSync(local('temporary'), { recursive: true });
  if (options.sourceTemp) fs.mkdirSync(local('repository/temporary'), { recursive: true });
  write(local('repository/.ci-output/release.env'), 'STALE-MANIFEST-SENTINEL\n');
  write(local('repository/.ci-output/release-previous.env'), 'STALE-ATTACHMENT-SENTINEL\n');
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (/^(DEPLOY_|TCR_|SOURCE_SHA$|SERVICE$|BUILD_REQUESTED$|BUILD_NUMBER$|CI_RUN_ID$|CI_BUILDER_MODE$|BUILD_CACHE_DIR$|OUTPUT_DIR$|CNB_BUILD_ID$)/.test(key)) delete env[key];
  }
  Object.assign(env, { TEST_NODE: node, TEST_CNB_MOCK: local('mock.cjs'), TEST_CNB_STATE: local('state.json'),
    TEST_CNB_AUDIT: local('audit.jsonl'), TEST_CNB_BIN: posix('bin'), TEST_CNB_SCRIPT: posix('repository/deploy/scripts/cnb-ci.sh'),
    SOURCE_SHA: options.sourceSha ?? sourceSha, TCR_USERNAME: username, TCR_PASSWORD: password,
    CNB_BUILD_ID: 'cnb-nonnumeric-serial', TMPDIR: options.sourceTemp ? posix('repository/temporary') : posix('temporary'),
    OUTPUT_DIR: posix('untrusted-output-override'),
    ...(options.environment || {}) });
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$TEST_CNB_BIN:/usr/bin:/bin"\nexec bash "$TEST_CNB_SCRIPT" "$@"', '--', ...(options.arguments || []),
  ], { env, encoding: 'utf8', timeout: 15000 });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(local('audit.jsonl'), 'utf8').split('\n').filter(Boolean).map(JSON.parse);
  return { ...result, local, posix, calls, diagnostic: result.stdout + result.stderr,
    outputs: fs.readdirSync(local('repository/.ci-output')).sort() };
}

const managedCall = (result) => result.calls.find((call) => call.tool === 'managed');
function assertNoSecrets(result) {
  for (const secret of [username, password, privateKey, knownHosts]) {
    assert.ok(!result.diagnostic.includes(secret), 'Credential material appeared in logs');
    assert.ok(!result.calls.some((call) => call.args.some((arg) => arg.includes(secret))), 'Credential material appeared in argv');
  }
}
function assertManifest(result) {
  const call = managedCall(result);
  const file = result.local('repository/.ci-output/release.env');
  const manifest = fs.readFileSync(file, 'utf8');
  assert.ok(result.stdout.includes('IMAGE_MANIFEST_BEGIN\n' + manifest + '\nIMAGE_MANIFEST_END'));
  assert.equal(fs.readFileSync(result.local('repository/.ci-output/release-' + call.version + '.env'), 'utf8'), manifest);
  assert.deepEqual(result.outputs, ['release-' + call.version + '.env', 'release.env']);
  assert.ok(!result.stdout.includes('STALE-'));
}

test('default CNB run builds all services with a numeric manual ID and produces one fresh unique manifest', () => {
  const result = run();
  assert.equal(result.status, 0, result.diagnostic);
  const call = managedCall(result);
  assert.deepEqual(call.args, []);
  assert.equal(call.environment.SERVICE, 'all');
  assert.equal(call.environment.BUILD_REQUESTED, 'true');
  assert.equal(call.environment.DEPLOY_REQUESTED, 'false');
  assert.equal(call.environment.CI_BUILDER_MODE, 'platform');
  assert.match(call.environment.CI_RUN_ID, /^[1-9][0-9]{0,19}$/);
  assert.equal(call.environment.BUILD_CACHE_DIR, '/root/.cache/trade-build-cache');
  assert.equal(call.environment.OUTPUT_DIR, result.posix('repository/.ci-output'));
  assert.deepEqual(call.credentials, { usernameMatches: true, passwordMatches: true, rawKeyRemoved: true, rawKnownHostsRemoved: true });
  assert.equal(result.calls.filter((entry) => entry.tool === 'chmod').length, 0);
  assertManifest(result);
  assertNoSecrets(result);
});

test('CNB forces platform builder mode even when the caller requests an isolated nested container', () => {
  const result = run({ environment: { CI_BUILDER_MODE: 'isolated', CI_RUN_ID: '42' } });
  assert.equal(result.status, 0, result.diagnostic);
  assert.equal(managedCall(result).environment.CI_BUILDER_MODE, 'platform');
  assertManifest(result);
  assertNoSecrets(result);
});

test('partial builds put injected SSH credentials in cleaned 0600 files outside the source checkout', () => {
  const result = run({ environment: { SERVICE: 'backend', CI_RUN_ID: '987654321',
    DEPLOY_SSH_PRIVATE_KEY: privateKey, DEPLOY_KNOWN_HOSTS: knownHosts } });
  assert.equal(result.status, 0, result.diagnostic);
  const call = managedCall(result);
  assert.equal(call.environment.CI_RUN_ID, '987654321');
  const key = call.environment.DEPLOY_SSH_KEY_FILE;
  const hosts = call.environment.DEPLOY_KNOWN_HOSTS_FILE;
  assert.ok(key.startsWith(result.posix('temporary') + '/trade-cnb.'));
  assert.equal(path.posix.dirname(key), path.posix.dirname(hosts));
  const permissions = result.calls.find((entry) => entry.tool === 'chmod');
  assert.deepEqual(permissions.args, ['600', '--', key, hosts]);
  assert.deepEqual(fs.readdirSync(result.local('temporary')), []);
  assert.equal(call.credentials.rawKeyRemoved, true);
  assertManifest(result);
  assertNoSecrets(result);
});

test('deployment failure retains its original status and still publishes the newly built image manifest', () => {
  const result = run({ managedStatus: 37, environment: { DEPLOY_REQUESTED: 'true', CI_RUN_ID: '123',
    DEPLOY_SSH_PRIVATE_KEY: privateKey, DEPLOY_KNOWN_HOSTS: knownHosts } });
  assert.equal(result.status, 37, result.diagnostic);
  assertManifest(result);
  assert.deepEqual(fs.readdirSync(result.local('temporary')), []);
  assertNoSecrets(result);
});

test('build failure without a new manifest preserves its status and cannot expose stale artifacts', () => {
  const result = run({ produceManifest: false, managedStatus: 23 });
  assert.equal(result.status, 23, result.diagnostic);
  assert.deepEqual(result.outputs, []);
  assert.ok(!result.stdout.includes('IMAGE_MANIFEST_BEGIN'));
  assert.ok(!result.diagnostic.includes('STALE-'));
  assertNoSecrets(result);
});

test('invalid source SHA, mismatched checkout and invalid run ID fail before managed CI or networking', () => {
  for (const options of [{ sourceSha: 'main' }, { sourceSha: 'b'.repeat(40) },
    { environment: { CI_RUN_ID: 'cnb-not-a-number' } }, { environment: { SERVICE: 'unknown' } }]) {
    const result = run(options);
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.ok(!managedCall(result));
    assert.ok(result.calls.every((call) => call.tool === 'git'));
    assert.deepEqual(result.outputs, []);
    assert.ok(!result.diagnostic.includes('STALE-'));
  }
});

test('missing SSH values and a credential directory inside the checkout fail before managed CI', () => {
  const missing = run({ environment: { SERVICE: 'backend' } });
  assert.notEqual(missing.status, 0, missing.diagnostic);
  assert.ok(!managedCall(missing));
  assert.deepEqual(missing.outputs, []);
  const result = run({ environment: { SERVICE: 'backend', DEPLOY_SSH_PRIVATE_KEY: privateKey,
    DEPLOY_KNOWN_HOSTS: knownHosts }, sourceTemp: true });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.ok(!managedCall(result));
  assert.deepEqual(result.outputs, []);
  assertNoSecrets(result);
});

test('success without a valid complete manifest is rejected and malformed secret-bearing content is never logged', () => {
  for (const options of [{ produceManifest: false }, { malformed: 'missing-key' },
    { malformed: 'duplicate-key' }, { malformed: 'unknown-key' }, { malformed: 'wrong-version' }]) {
    const result = run(options);
    assert.equal(result.status, 1, result.diagnostic);
    assert.ok(!result.stdout.includes('IMAGE_MANIFEST_BEGIN'));
    assert.ok(!result.outputs.some((file) => file.startsWith('release-')));
    assertNoSecrets(result);
  }
});

test('rollback normalizes flags, requires SSH, and succeeds without publishing an image manifest', () => {
  const result = run({ environment: { CI_ACTION: 'rollback', BUILD_REQUESTED: 'true', DEPLOY_REQUESTED: 'false',
    DEPLOY_SSH_PRIVATE_KEY: privateKey, DEPLOY_KNOWN_HOSTS: knownHosts } });
  assert.equal(result.status, 0, result.diagnostic);
  assert.equal(managedCall(result).environment.CI_ACTION, 'rollback');
  assert.equal(managedCall(result).environment.BUILD_REQUESTED, 'false');
  assert.equal(managedCall(result).environment.DEPLOY_REQUESTED, 'true');
  assert.deepEqual(result.outputs, []);
  assert.deepEqual(fs.readdirSync(result.local('temporary')), []);
  assert.ok(!result.stdout.includes('IMAGE_MANIFEST_BEGIN'));
  assertNoSecrets(result);
});

test('deploy-only mode accepts a valid historical version and still cleans ephemeral SSH files', () => {
  const result = run({ environment: { BUILD_REQUESTED: 'false', DEPLOY_REQUESTED: 'true',
    DEPLOY_SSH_PRIVATE_KEY: privateKey, DEPLOY_KNOWN_HOSTS: knownHosts } });
  assert.equal(result.status, 0, result.diagnostic);
  assert.equal(managedCall(result).version, 'historical-release.1');
  assertManifest(result);
  assert.deepEqual(fs.readdirSync(result.local('temporary')), []);
  assertNoSecrets(result);
});

after(() => {
  assert.equal(fs.realpathSync(root), ownedRoot, 'refuse deletion if the exact temporary fixture root changed');
  assert.ok(path.basename(root).startsWith('trade-cnb-tests.'));
  fs.rmSync(root, { recursive: true, force: true });
});
