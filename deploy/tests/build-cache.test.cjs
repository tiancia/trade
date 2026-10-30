// Offline: the real build script runs against a strict Docker mock and temporary OCI caches.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { test, after } = require('node:test');

const windows = process.platform === 'win32';
const bash = process.env.TEST_BASH || (windows ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const buildScript = path.resolve(__dirname, '../scripts/build-images.sh');
const toBash = (file) => windows
  ? cp.execFileSync(bash, ['-c', 'cygpath -u "$1"', '--', file], { encoding: 'utf8' }).trim() : file;
let root;
let bashRoot;
if (windows) {
  [bashRoot, root] = cp.execFileSync(bash, ['-c',
    'task_root=$(mktemp -d /tmp/trade-build-cache-tests.XXXXXX)\nprintf "%s\\n" "$task_root"\ncygpath -w "$task_root"',
  ], { encoding: 'utf8' }).trim().split(/\r?\n/);
} else {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-build-cache-tests.'));
  bashRoot = root;
}
const verifiedRoot = fs.realpathSync(root);
const node = toBash(process.execPath);
const script = toBash(buildScript);
const registry = 'registry.example.com/trade';
let sequence = 0;
const write = (file, text) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text, { mode: 0o755 });
};
const baseline = ['BACKEND', 'COCKPIT', 'X_CONSOLE', 'EGRESS'].map((key) =>
  `${key}_IMAGE=${registry}/${key.toLowerCase().replace('_', '-')}:previous`).join('\n') + '\n';

test('only a successful current build marks its cache eligible for workflow persistence', () => {
  const current = fixture();
  const success = run(current, { tag: 'first-export' });
  assert.equal(success.status, 0, success.stdout + success.stderr);
  const marker = current.local('cache/.exported-cockpit');
  assert.equal(fs.readFileSync(marker, 'utf8').trim(), 'first-export');

  const failure = run(current, { tag: 'next-export', failService: 'cockpit' });
  assert.equal(failure.status, 17, failure.stdout + failure.stderr);
  assert.equal(fs.existsSync(marker), false, 'a restored old index must not become an exact cache for a failed build');
  assert.equal(fs.existsSync(current.local('cache/cockpit/index.json')), true, 'retain the previous working cache');
});

function dockerMock() {
  const fs = require('node:fs');
  const path = require('node:path');
  const { createHash } = require('node:crypto');
  const args = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_CACHE_STATE, 'utf8'));
  fs.appendFileSync(process.env.TEST_CACHE_AUDIT, JSON.stringify(args) + '\n');
  const reject = (message) => { process.stderr.write(message + '\n'); process.exit(91); };
  const hostPath = (file) => {
    if (!file.startsWith(state.fixtureBashRoot + '/')) reject('Cache path escaped the test fixture');
    return path.join(state.fixtureLocalRoot, file.slice(state.fixtureBashRoot.length + 1));
  };
  if (args[0] === 'push') {
    if (args.length !== 2 || !args[1].startsWith('registry.example.com/trade/')) reject('Unexpected push');
    process.stdout.write('mock registry upload\n');
    process.exit(state.pushExit || 0);
  }
  const cached = args[0] === 'buildx' && args[1] === 'build';
  if (!cached && args[0] !== 'build') reject('Only selected builds and pushes may reach Docker');
  if (cached && (!args.includes('--load') || args[args.indexOf('--progress') + 1] !== 'plain')) {
    reject('Cached builds must load their image locally and retain readable progress');
  }
  if (args[args.indexOf('--platform') + 1] !== 'linux/amd64') reject('Unexpected platform');
  const image = args[args.indexOf('--tag') + 1];
  const service = image.split('/').at(-1).split(':')[0];
  if (!['backend', 'cockpit', 'x-console', 'egress'].includes(service)) reject('Unexpected image service');
  if (state.failService === service) {
    process.stderr.write('mock build failure\n');
    process.exit(state.buildExit || 17);
  }
  if (cached) {
    const exported = args[args.indexOf('--cache-to') + 1]?.match(/^type=local,dest=(.+),mode=max$/);
    if (!exported) reject('Cache export must use a fresh local directory with mode=max');
    const destination = hostPath(exported[1]);
    if (args.includes('--cache-from')) {
      const imported = args[args.indexOf('--cache-from') + 1]?.match(/^type=local,src=(.+)$/);
      if (!imported || !fs.existsSync(path.join(hostPath(imported[1]), 'index.json'))) reject('Invalid cache import');
      if (imported[1] === exported[1]) reject('Cache import and export must use different directories');
    }
    fs.mkdirSync(path.join(destination, 'blobs', 'sha256'), { recursive: true });
    fs.writeFileSync(path.join(destination, 'index.json'), JSON.stringify({ schemaVersion: 2, manifests: [] }));
    fs.writeFileSync(path.join(destination, 'oci-layout'), JSON.stringify({ imageLayoutVersion: '1.0.0' }));
    const payload = `${service}:${state.marker || 'first'}`;
    const digest = createHash('sha256').update(payload).digest('hex');
    fs.writeFileSync(path.join(destination, 'blobs', 'sha256', digest), payload);
  }
}

function fixture() {
  const name = 'case-' + (++sequence);
  const local = (file) => path.join(root, name, file);
  const posix = (file) => bashRoot + '/' + name + '/' + file;
  write(local('base.env'), baseline);
  write(local('mock.cjs'), '(' + dockerMock.toString() + ')();\n');
  write(local('bin/docker'), '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" exec "$TEST_NODE" "$TEST_CACHE_MOCK" "$@"\n');
  return { name, local, posix };
}

function run(current, options = {}) {
  write(current.local('state.json'), JSON.stringify({ ...options, fixtureBashRoot: bashRoot, fixtureLocalRoot: root }));
  write(current.local('audit.jsonl'), '');
  const args = ['--registry', registry, '--tag', options.tag || 'cache-test', '--base', current.posix('base.env'),
    '--output', current.posix('output/release.env')];
  if (options.cache !== false) args.push('--cache-dir', options.cacheDir || current.posix('cache'));
  if (options.push) args.push('--push');
  args.push(...(options.services || ['cockpit']));
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$TEST_CACHE_BIN:/usr/bin:/bin"\nexec bash "$TEST_BUILD_SCRIPT" "$@"', '--', ...args,
  ], {
    env: { ...process.env, BASE_IMAGE_SOURCE: options.source ?? "dockerhub", TEST_NODE: node, TEST_BUILD_SCRIPT: script,
      TEST_CACHE_BIN: current.posix('bin'), TEST_CACHE_MOCK: current.local('mock.cjs'),
      TEST_CACHE_STATE: current.local('state.json'), TEST_CACHE_AUDIT: current.local('audit.jsonl'),
    },
    encoding: 'utf8', timeout: 20_000,
  });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(current.local('audit.jsonl'), 'utf8').split('\n').filter(Boolean).map(JSON.parse);
  return { ...result, calls, diagnostic: result.stdout + result.stderr };
}

const blob = (service, marker) => createHash('sha256').update(`${service}:${marker}`).digest('hex');

test('without cache-dir the original Docker commands and build-before-push ordering remain intact', () => {
  const current = fixture();
  const result = run(current, { cache: false, services: ['cockpit', 'x-console'], push: true });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call[0]), ['build', 'build', 'push', 'push']);
  assert.deepEqual(result.calls[0].slice(0, 5), ['build', '--platform', 'linux/amd64', '--tag', registry + '/cockpit:cache-test']);
  assert.equal(result.calls[0].length, 6);
  assert.match(result.calls[0][5], /\/frontend\/trading-cockpit$/);
  assert.match(result.stdout, /phase=build service=cockpit elapsed_seconds=\d+ status=success exit_code=0/);
  assert.match(result.stdout, /phase=push service=x-console elapsed_seconds=\d+ status=success exit_code=0/);
  assert.ok(!fs.existsSync(current.local('cache')));
});

test('pre-seeded TCR base mirror overrides all four services without changing output image tags', () => {
  const current = fixture();
  const result = run(current, {
    cache: false, source: 'tcr', services: ['backend', 'cockpit', 'x-console', 'egress'],
  });
  assert.equal(result.status, 0, result.diagnostic);
  const expected = {
    backend: ['BUILD_JAVA_IMAGE=' + registry + '/base-maven:3.9.14-eclipse-temurin-21',
      'RUNTIME_JAVA_IMAGE=' + registry + '/base-eclipse-temurin:21-jre-jammy'],
    cockpit: ['NODE_IMAGE=' + registry + '/base-node:22.23.3-bookworm-slim'],
    'x-console': ['NODE_IMAGE=' + registry + '/base-node:22.23.3-bookworm-slim',
      'NGINX_IMAGE=' + registry + '/base-nginx:1.30.6-alpine'],
    egress: ['ALPINE_IMAGE=' + registry + '/base-alpine:3.22'],
  };
  for (const call of result.calls) {
    const service = call[call.indexOf('--tag') + 1].split('/').at(-1).split(':')[0];
    const args = [];
    for (let i = 0; i < call.length; i++) if (call[i] === '--build-arg') args.push(call[i + 1]);
    assert.deepEqual(args, expected[service]);
  }
  const invalid = run(fixture(), { cache: false, source: 'sometimes' });
  assert.notEqual(invalid.status, 0);
  assert.deepEqual(invalid.calls, []);
});

test('selected base image overrides also work with Buildx local cache', () => {
  const result = run(fixture(), { source: 'tcr', services: ['backend'] });
  assert.equal(result.status, 0, result.diagnostic);
  const call = result.calls[0];
  assert.ok(call.includes('BUILD_JAVA_IMAGE=' + registry + '/base-maven:3.9.14-eclipse-temurin-21'));
  assert.ok(call.includes('RUNTIME_JAVA_IMAGE=' + registry + '/base-eclipse-temurin:21-jre-jammy'));
});

test('DaoCloud base mirror resolves all four services without any local image upload', () => {
  const result = run(fixture(), { source: 'daocloud', cache: false,
    services: ['backend', 'cockpit', 'x-console', 'egress'] });
  assert.equal(result.status, 0, result.diagnostic);
  const prefix = 'm.daocloud.io/docker.io/library/';
  const expected = {
    backend: ['BUILD_JAVA_IMAGE=' + prefix + 'maven:3.9.14-eclipse-temurin-21',
      'RUNTIME_JAVA_IMAGE=' + prefix + 'eclipse-temurin:21-jre-jammy'],
    cockpit: ['NODE_IMAGE=' + prefix + 'node:22.23.3-bookworm-slim'],
    'x-console': ['NODE_IMAGE=' + prefix + 'node:22.23.3-bookworm-slim',
      'NGINX_IMAGE=' + prefix + 'nginx:1.30.6-alpine'],
    egress: ['ALPINE_IMAGE=' + prefix + 'alpine:3.22'],
  };
  for (const call of result.calls) {
    const service = call[call.indexOf('--tag') + 1].split('/').at(-1).split(':')[0];
    const args = [];
    for (let i = 0; i < call.length; i++) if (call[i] === '--build-arg') args.push(call[i + 1]);
    assert.deepEqual(args, expected[service]);
  }
});

test('first cached build exports fresh isolated caches for each selected service without importing absent caches', () => {
  const current = fixture();
  const result = run(current, { services: ['backend', 'cockpit'], push: true });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call.slice(0, 2)), [
    ['buildx', 'build'], ['buildx', 'build'], ['push', registry + '/backend:cache-test'], ['push', registry + '/cockpit:cache-test'],
  ]);
  for (const service of ['backend', 'cockpit']) {
    const call = result.calls.find((entry) => entry.includes(registry + '/' + service + ':cache-test') && entry[0] === 'buildx');
    assert.ok(!call.includes('--cache-from'));
    assert.ok(call.includes('--load'));
    assert.match(call[call.indexOf('--cache-to') + 1], new RegExp('/\\.trade-cache-' + service + '-next\\.[^,]+,mode=max$'));
    assert.ok(fs.existsSync(current.local('cache/' + service + '/index.json')));
    assert.equal(fs.readFileSync(current.local('cache/.exported-' + service), 'utf8').trim(), 'cache-test');
    assert.equal(fs.readFileSync(current.local('cache/' + service + '/blobs/sha256/' + blob(service, 'first')), 'utf8'), service + ':first');
  }
  assert.ok(result.calls[0].includes('--file'), 'the backend must keep its repository-root Dockerfile');
  assert.deepEqual(fs.readdirSync(current.local('cache')).sort(), ['.exported-backend', '.exported-cockpit', 'backend', 'cockpit']);
});

test('second build imports its own service cache and replaces obsolete blobs without changing another service cache', () => {
  const current = fixture();
  assert.equal(run(current, { services: ['backend', 'cockpit'], marker: 'first' }).status, 0);
  const result = run(current, { services: ['backend'], marker: 'second' });
  assert.equal(result.status, 0, result.diagnostic);
  const call = result.calls[0];
  assert.equal(call[call.indexOf('--cache-from') + 1], 'type=local,src=' + current.posix('cache/backend'));
  assert.ok(!fs.existsSync(current.local('cache/backend/blobs/sha256/' + blob('backend', 'first'))));
  assert.ok(fs.existsSync(current.local('cache/backend/blobs/sha256/' + blob('backend', 'second'))));
  assert.ok(fs.existsSync(current.local('cache/cockpit/blobs/sha256/' + blob('cockpit', 'first'))));
  assert.deepEqual(fs.readdirSync(current.local('cache')).sort(), ['.exported-backend', '.exported-cockpit', 'backend', 'cockpit']);
});

test('failed build keeps its previous OCI cache and manifest and never starts an upload', () => {
  const current = fixture();
  assert.equal(run(current, { marker: 'first' }).status, 0);
  const previousManifest = fs.readFileSync(current.local('output/release.env'), 'utf8');
  const result = run(current, { failService: 'cockpit', buildExit: 23, marker: 'failed', tag: 'broken', push: true });
  assert.equal(result.status, 23, result.diagnostic);
  assert.equal(result.calls.length, 1);
  assert.equal(result.calls[0][0], 'buildx');
  assert.equal(fs.readFileSync(current.local('output/release.env'), 'utf8'), previousManifest);
  assert.ok(fs.existsSync(current.local('cache/cockpit/blobs/sha256/' + blob('cockpit', 'first'))));
  assert.deepEqual(fs.readdirSync(current.local('cache')), ['cockpit']);
  assert.match(result.stdout, /phase=build service=cockpit elapsed_seconds=\d+ status=failure exit_code=23/);
});

test('a later selected build failure still prevents every push and prevents publishing a new manifest', () => {
  const current = fixture();
  const result = run(current, { services: ['backend', 'cockpit'], failService: 'cockpit', buildExit: 19, push: true });
  assert.equal(result.status, 19, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call[0]), ['buildx', 'buildx']);
  assert.ok(!fs.existsSync(current.local('output/release.env')));
  assert.ok(fs.existsSync(current.local('cache/backend/index.json')));
  assert.deepEqual(fs.readdirSync(current.local('cache')).sort(), ['.exported-backend', 'backend']);
});

test('push failure preserves its exit code and records its timing without publishing a manifest', () => {
  const current = fixture();
  const result = run(current, { cache: false, push: true, pushExit: 7 });
  assert.equal(result.status, 7, result.diagnostic);
  assert.match(result.stdout, /phase=push service=cockpit elapsed_seconds=\d+ status=failure exit_code=7/);
  assert.ok(!fs.existsSync(current.local('output/release.env')));
});

test('unsafe roots, dot traversal and non-OCI service directories are rejected before Docker runs', () => {
  for (const cacheDir of ['/', '/tmp', '/var/tmp', script.replace(/\/deploy\/scripts\/build-images\.sh$/, ''), bashRoot + '/child/../cache']) {
    const current = fixture();
    const result = run(current, { cacheDir });
    assert.notEqual(result.status, 0, cacheDir + '\n' + result.diagnostic);
    assert.deepEqual(result.calls, []);
  }
  const current = fixture();
  write(current.local('cache/cockpit/important.txt'), 'preserve user data');
  const result = run(current);
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls, []);
  assert.equal(fs.readFileSync(current.local('cache/cockpit/important.txt'), 'utf8'), 'preserve user data');
});

test('symlink cache roots and symlink entries inside OCI caches are rejected', (context) => {
  const current = fixture();
  fs.mkdirSync(current.local('target'), { recursive: true });
  try {
    fs.symlinkSync(current.local('target'), current.local('linked-cache'), windows ? 'junction' : 'dir');
  } catch (error) {
    if (windows && error.code === 'EPERM') return context.skip('Windows does not permit creating test symlinks');
    throw error;
  }
  const supported = cp.spawnSync(bash, ['-c', 'test -L "$1"', '--', current.posix('linked-cache')]);
  if (supported.status !== 0 && windows) return context.skip('Git Bash does not expose Windows junctions as POSIX symlinks');
  const result = run(current, { cacheDir: current.posix('linked-cache') });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls, []);
  assert.equal(run(current).status, 0);
  fs.symlinkSync(current.local('target'), current.local('cache/cockpit/blobs/linked'), windows ? 'junction' : 'dir');
  const nested = run(current);
  assert.notEqual(nested.status, 0, nested.diagnostic);
  assert.deepEqual(nested.calls, []);
  assert.ok(fs.existsSync(current.local('cache/cockpit/index.json')));
});

after(() => {
  assert.equal(fs.realpathSync(root), verifiedRoot, 'refuse cleanup if the exact temporary fixture root changed');
  assert.ok(path.basename(root).startsWith('trade-build-cache-tests.'));
  fs.rmSync(root, { recursive: true, force: true });
});
