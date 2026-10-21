// Offline only: Docker and flock are replaced by strict local mocks.
// Run with: node deploy/tests/cleanup-images.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const { test, after } = require('node:test');

const isWindows = process.platform === 'win32';
const bash = process.env.TEST_BASH || (isWindows ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const script = path.resolve(__dirname, '../scripts/cleanup-images.sh');
const registry = 'ghcr.io/tiancia/trade';

function bashPath(file) {
  if (!isWindows) return file;
  return cp.execFileSync(bash, ['-c', 'cygpath -u "$1"', '--', file], { encoding: 'utf8' }).trim();
}

let fixtureRoot;
let fixtureBashRoot;
if (isWindows) {
  const setup = cp.execFileSync(bash, ['-c',
    'root=$(mktemp -d /tmp/trade-cleanup-tests.XXXXXX)\nprintf "%s\\n" "$root"\ncygpath -w "$root"'
  ], { encoding: 'utf8' }).trim().split(/\r?\n/);
  [fixtureBashRoot, fixtureRoot] = setup;
} else {
  fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-cleanup-tests.'));
  fixtureBashRoot = fixtureRoot;
}
const originalFixtureRoot = fs.realpathSync(fixtureRoot);
const nodePath = bashPath(process.execPath);
const scriptPath = bashPath(script);
const local = (relative) => path.join(fixtureRoot, relative);
const posix = (relative) => fixtureBashRoot + '/' + relative.split(path.sep).join('/');
const write = (file, text) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text, { mode: 0o755 });
};

// This function is serialized to a temporary file, never installed on a server.
function mockTool() {
  const fs = require('node:fs');
  const [tool, ...args] = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_STATE, 'utf8'));
  fs.appendFileSync(process.env.TEST_AUDIT, JSON.stringify({ tool, args }) + '\n');
  const reject = (message) => { process.stderr.write(message + '\n'); process.exit(41); };
  if (tool === 'flock') {
    if (!args.includes('-n')) reject('The cleanup must not wait for a deployment lock.');
    process.exit(state.lockBusy ? 1 : 0);
  }
  if (tool !== 'docker') reject('Unexpected tool');
  if (args.some((arg) => arg === '-f' || arg === '--force' || arg === 'prune')) {
    reject('Force removal or prune is forbidden.');
  }
  const command = args.slice(0, 2).join(' ');
  if (state.fail === command) reject('Simulated Docker query failure');
  if (command === 'image ls') {
    if (!args.includes('--no-trunc')) reject('Expected complete image IDs');
    for (const image of state.images) process.stdout.write(image.ref + ' ' + image.id + '\n');
  } else if (command === 'image inspect') {
    const formatIndex = args.indexOf('--format');
    if (formatIndex < 0 || args[formatIndex + 1] !== '{{.Id}}') reject('Unexpected image inspection');
    const refs = args.slice(2).filter((arg, index) => index + 2 !== formatIndex && index + 2 !== formatIndex + 1);
    if (refs.length !== 1) reject('Inspect one image reference at a time');
    if (state.failInspectRef === refs[0]) reject('Simulated missing protected image');
    const image = state.images.find((entry) => entry.ref === refs[0]);
    if (!image) reject('Image is not in the fixture: ' + refs[0]);
    process.stdout.write(image.id + '\n');
  } else if (command === 'container ls') {
    if (!args.includes('-aq') && !(args.includes('--all') && args.includes('--quiet'))) {
      reject('Expected all containers, including stopped containers');
    }
    for (const container of state.containers) process.stdout.write(container.id + '\n');
  } else if (command === 'container inspect') {
    const formatIndex = args.indexOf('--format');
    if (formatIndex < 0 || args[formatIndex + 1] !== '{{.Image}} {{.Config.Image}}') reject('Unexpected container inspection');
    const ids = args.slice(2).filter((arg, index) => index + 2 !== formatIndex && index + 2 !== formatIndex + 1);
    if (ids.length !== 1) reject('Inspect one container at a time');
    const container = state.containers.find((entry) => entry.id === ids[0]);
    if (!container) reject('Container is not in the fixture');
    process.stdout.write(container.image + ' ' + container.ref + '\n');
  } else if (command === 'image rm') {
    if (args.length !== 3 || args[2].startsWith('sha256:')) reject('Remove one explicit tag at a time');
    if (state.failRemoveRef === args[2]) reject('Simulated image removal conflict');
    fs.appendFileSync(process.env.TEST_REMOVED, args[2] + '\n');
  } else {
    reject('Unexpected Docker command: ' + command);
  }
}

write(local('mock-tool.cjs'), '(' + mockTool.toString() + ')();\n');
for (const tool of ['docker', 'flock']) {
  write(local('bin/' + tool), '#!/usr/bin/env bash\nexec "$TEST_NODE" "$TEST_MOCK" ' + tool + ' "$@"\n');
}

const imageId = (n) => 'sha256:' + n.toString(16).padStart(64, '0');
const retained = {
  backend: registry + '/backend:current',
  cockpit: 'trade/cockpit:current',
  'x-console': 'trade/x-console:current',
  egress: 'trade/egress:current',
};
const previousBackend = 'trade/backend:previous';
function manifest(backend = retained.backend) {
  return [
    'RELEASE_VERSION=fixture',
    'BACKEND_IMAGE=' + backend,
    'COCKPIT_IMAGE=' + retained.cockpit,
    'X_CONSOLE_IMAGE=' + retained['x-console'],
    'EGRESS_IMAGE=' + retained.egress,
    '',
  ].join('\n');
}
function baseState() {
  return {
    images: [
      { ref: retained.backend, id: imageId(1) },
      { ref: retained.cockpit, id: imageId(2) },
      { ref: retained['x-console'], id: imageId(3) },
      { ref: retained.egress, id: imageId(4) },
      { ref: previousBackend, id: imageId(5) },
      { ref: registry + '/backend:current-alias', id: imageId(1) },
      { ref: 'trade-local/backend:previous-alias', id: imageId(5) },
      { ref: 'trade-local/egress:running', id: imageId(6) },
      { ref: registry + '/cockpit:stopped', id: imageId(7) },
      { ref: 'trade-local/backend:old', id: imageId(8) },
      { ref: registry + '/cockpit:old', id: imageId(9) },
      { ref: 'trade/x-console:old', id: imageId(10) },
      { ref: 'trade/egress:old', id: imageId(11) },
      { ref: 'mysql:8.0', id: imageId(12) },
      { ref: 'grafana/grafana:13.1.0', id: imageId(13) },
      { ref: 'trade/backend-helper:old', id: imageId(14) },
      { ref: 'trade-local/backend-other:old', id: imageId(15) },
      { ref: registry + '-other/backend:old', id: imageId(16) },
      { ref: 'ghcr.io/another/trade/backend:old', id: imageId(17) },
      { ref: '<none>:<none>', id: imageId(18) },
      { ref: 'ghcr.io/oras-project/oras:v1.3.4', id: imageId(19) },
    ],
    containers: [
      { id: '1'.repeat(64), image: imageId(6), ref: 'trade-local/egress:running', state: 'running' },
      { id: '2'.repeat(64), image: imageId(7), ref: registry + '/cockpit:stopped', state: 'exited' },
    ],
  };
}
const expectedRemoved = [
  'trade-local/backend:old',
  registry + '/cockpit:old',
  'trade/x-console:old',
  'trade/egress:old',
];
let sequence = 0;
function run(options = {}) {
  const name = 'case-' + (++sequence);
  const root = local(name);
  const state = baseState();
  options.changeState?.(state);
  const current = options.current ?? manifest();
  const previous = options.previous ?? manifest(previousBackend);
  if (!options.noCurrent) write(path.join(root, 'deploy/releases/current.env'), current);
  if (!options.noPrevious) write(path.join(root, 'deploy/releases/previous.env'), previous);
  write(path.join(root, 'state.json'), JSON.stringify(state));
  write(path.join(root, 'audit.jsonl'), '');
  write(path.join(root, 'removed.txt'), '');
  const env = {
    ...process.env,
    TEST_NODE: nodePath,
    TEST_MOCK: posix('mock-tool.cjs'),
    TEST_STATE: path.join(root, 'state.json'),
    TEST_AUDIT: path.join(root, 'audit.jsonl'),
    TEST_REMOVED: path.join(root, 'removed.txt'),
    TEST_BIN: posix('bin'),
    TEST_SCRIPT: scriptPath,
    TEST_ROOT: posix(name),
    TEST_REGISTRY: registry,
  };
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$TEST_BIN:/usr/bin:/bin"\nexec bash "$TEST_SCRIPT" --root "$TEST_ROOT" --registry "$TEST_REGISTRY" "$@"',
    '--', ...(options.apply ? ['--apply'] : []),
  ], { env, encoding: 'utf8', timeout: 15_000 });
  if (result.error) throw result.error;
  const audit = fs.readFileSync(env.TEST_AUDIT, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  const removed = fs.readFileSync(env.TEST_REMOVED, 'utf8').trim().split('\n').filter(Boolean);
  if (!options.noCurrent) assert.equal(fs.readFileSync(path.join(root, 'deploy/releases/current.env'), 'utf8'), current);
  if (!options.noPrevious) assert.equal(fs.readFileSync(path.join(root, 'deploy/releases/previous.env'), 'utf8'), previous);
  return { ...result, audit, removed, root, diagnostic: result.stdout + result.stderr };
}

test('default dry-run lists candidates without removing images', () => {
  const result = run();
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed, []);
  for (const ref of expectedRemoved) assert.ok(result.stdout.includes(ref), result.diagnostic);
});

test('apply protects both releases, image aliases, and running/stopped container images', () => {
  const result = run({ apply: true });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed.sort(), [...expectedRemoved].sort());
  assert.ok(result.audit.some((entry) => entry.tool === 'flock'));
  for (const entry of result.audit.filter((entry) => entry.tool === 'docker')) {
    assert.ok(!entry.args.includes('-f') && !entry.args.includes('--force') && !entry.args.includes('prune'));
  }
});

for (const [name, option] of [
  ['missing current manifest', { noCurrent: true }],
  ['missing previous manifest', { noPrevious: true }],
  ['incomplete current manifest', { current: 'BACKEND_IMAGE=trade/backend:bad\n' }],
  ['duplicate manifest key', { current: manifest() + 'BACKEND_IMAGE=trade/backend:duplicate\n' }],
  ['unexpected manifest key', { previous: manifest(previousBackend) + 'MYSQL_IMAGE=mysql:8.0\n' }],
]) {
  test(name + ' aborts before deletion', () => {
    const result = run({ ...option, apply: true });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.deepEqual(result.removed, []);
  });
}

test('manifest content is never executed as shell code', () => {
  const marker = posix('manifest-was-executed');
  const result = run({ apply: true, current: 'BACKEND_IMAGE=$(touch ' + marker + ')\n' + manifest() });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed, []);
  assert.equal(fs.existsSync(local('manifest-was-executed')), false);
});

for (const command of ['image ls', 'image inspect', 'container ls', 'container inspect']) {
  test('failure of ' + command + ' prevents all deletion', () => {
    const result = run({ apply: true, changeState: (state) => { state.fail = command; } });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.deepEqual(result.removed, []);
  });
}

test('a missing protected image aborts rather than guessing its ID', () => {
  const result = run({ apply: true, changeState: (state) => { state.failInspectRef = previousBackend; } });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed, []);
});

test('a failed candidate inspection prevents every deletion, including earlier candidates', () => {
  const result = run({ apply: true, changeState: (state) => { state.failInspectRef = expectedRemoved.at(-1); } });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed, []);
});

test('container source references protect multi-platform images when IDs differ', () => {
  const result = run({ apply: true, changeState: (state) => {
    state.containers[0].image = imageId(99);
    state.containers[1].image = imageId(100);
  } });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed.sort(), [...expectedRemoved].sort());
});

test('a malformed candidate image ID prevents deletion', () => {
  const result = run({ apply: true, changeState: (state) => {
    state.images.find((image) => image.ref === expectedRemoved[0]).id = 'not-an-image-id';
  } });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed, []);
});

test('an active deployment lock prevents all deletion', () => {
  const result = run({ apply: true, changeState: (state) => { state.lockBusy = true; } });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed, []);
});

test('image removal conflict is reported without forcing removal', () => {
  const result = run({ apply: true, changeState: (state) => { state.failRemoveRef = expectedRemoved[0]; } });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.ok(!result.removed.includes(expectedRemoved[0]));
  assert.ok(result.removed.every((ref) => expectedRemoved.includes(ref)));
});

test('no containers is a valid empty protection set', () => {
  const result = run({ apply: true, changeState: (state) => { state.containers = []; } });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.removed.sort(), [
    ...expectedRemoved, 'trade-local/egress:running', registry + '/cockpit:stopped',
  ].sort());
});

const workflowText = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/build-deploy.yml'), 'utf8');
const remoteBlock = workflowText.match(/remote_script=\$\(cat <<'REMOTE'\r?\n([\s\S]*?)\r?\n[ \t]*REMOTE/);
assert.ok(remoteBlock, 'The workflow must contain its remote deployment script');
const remoteScript = remoteBlock[1].replace(/^ {10}/gm, '');

function runWorkflow(options = {}) {
  const name = 'workflow-' + (++sequence);
  const root = posix(name + '/server');
  const manifestPath = root + '/deploy/incoming/version/release.env';
  const audit = posix(name + '/audit');
  const configRecord = posix(name + '/docker-config-path');
  write(local(name + '/audit'), '');
  write(local(name + '/docker-config-path'), '');
  write(local(name + '/scratch/.keep'), '');
  write(local(name + '/server/deploy/incoming/version/release.env'), manifest());
  write(local(name + '/server/deploy/scripts/release.sh'), [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    'printf "release" >> "$WORKFLOW_AUDIT"',
    'printf "\\t%s" "$@" >> "$WORKFLOW_AUDIT"',
    'printf "\\n" >> "$WORKFLOW_AUDIT"',
    'if [[ $WORKFLOW_FAIL == "$1" ]]; then exit 42; fi',
    '',
  ].join('\n'));
  write(local(name + '/server/deploy/incoming/version/cleanup-images.sh'), [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    'printf "cleanup" >> "$WORKFLOW_AUDIT"',
    'printf "\\t%s" "$@" >> "$WORKFLOW_AUDIT"',
    'printf "\\n" >> "$WORKFLOW_AUDIT"',
    'if [[ $WORKFLOW_FAIL == cleanup ]]; then exit 43; fi',
    '',
  ].join('\n'));
  write(local(name + '/bin/docker'), [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    '[[ "$*" == "login ghcr.io --username tiancia --password-stdin" ]] || exit 44',
    'token=$(cat)',
    '[[ $token == dummy-workflow-token ]] || exit 45',
    'printf "{\\"auths\\":{\\"ghcr.io\\":{\\"auth\\":\\"dummy\\"}}}\\n" > "$DOCKER_CONFIG/config.json"',
    'test -f "$DOCKER_CONFIG/config.json"',
    'printf "%s\\n" "$DOCKER_CONFIG" > "$WORKFLOW_CONFIG_RECORD"',
    'printf "login\\n" >> "$WORKFLOW_AUDIT"',
    '',
  ].join('\n'));
  const result = cp.spawnSync(bash, [
    '-c', 'export PATH="$WORKFLOW_BIN:/usr/bin:/bin"\n' + remoteScript,
    '--', root, manifestPath, options.access || 'private', options.service || 'backend', 'tiancia', registry,
  ], {
    env: {
      ...process.env,
      WORKFLOW_BIN: posix(name + '/bin'),
      WORKFLOW_AUDIT: audit,
      WORKFLOW_CONFIG_RECORD: configRecord,
      WORKFLOW_FAIL: options.fail || '',
      TMPDIR: posix(name + '/scratch'),
    },
    input: 'dummy-workflow-token\n',
    encoding: 'utf8',
    timeout: 15_000,
  });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(local(name + '/audit'), 'utf8').trim().split('\n').filter(Boolean)
    .map((line) => line.split('\t'));
  const configPath = fs.readFileSync(local(name + '/docker-config-path'), 'utf8').trim();
  assert.ok(configPath, 'The Docker login mock must create temporary credentials');
  assert.ok(configPath.startsWith(posix(name + '/scratch') + '/'), 'Docker config must use owned temporary fixtures');
  const localConfig = path.join(local(name + '/scratch'), path.posix.basename(configPath));
  assert.equal(fs.existsSync(localConfig), false, 'Temporary Docker credentials and directory must be removed');
  return { ...result, calls, root, manifestPath, diagnostic: result.stdout + result.stderr };
}

test('workflow performs cleanup only after validation and deployment succeed', () => {
  const result = runWorkflow();
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call[0] === 'release' ? call[1] : call[0]), [
    'login', 'validate', 'deploy', 'cleanup',
  ]);
  assert.ok(result.stdout.includes('DEPLOYMENT_OK'), result.diagnostic);
  assert.ok(!result.stdout.includes('::warning::'), result.diagnostic);
});

test('workflow skips cleanup when validation or deployment fails', () => {
  for (const fail of ['validate', 'deploy']) {
    const result = runWorkflow({ fail });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.ok(!result.calls.some((call) => call[0] === 'cleanup'), result.diagnostic);
    assert.ok(!result.stdout.includes('DEPLOYMENT_OK'), result.diagnostic);
  }
});

test('workflow warns about cleanup failure while keeping a successful deployment successful', () => {
  const result = runWorkflow({ fail: 'cleanup' });
  assert.equal(result.status, 0, result.diagnostic);
  assert.ok(result.calls.some((call) => call[0] === 'cleanup'), result.diagnostic);
  assert.ok(result.stdout.includes('DEPLOYMENT_OK'), result.diagnostic);
  assert.ok(result.stdout.includes('::warning::'), result.diagnostic);
});

test('workflow passes exact cleanup arguments and preserves deployment scope', () => {
  for (const [service, targets] of [
    ['backend', ['backend']], ['frontends', ['cockpit', 'x-console']], ['all', []],
  ]) {
    const result = runWorkflow({ service, access: 'public' });
    assert.equal(result.status, 0, result.diagnostic);
    assert.deepEqual(result.calls.find((call) => call[0] === 'cleanup'), [
      'cleanup', '--root', result.root, '--registry', registry, '--apply',
    ]);
    assert.deepEqual(result.calls.find((call) => call[0] === 'release' && call[1] === 'deploy'), [
      'release', 'deploy', ...targets, '--release', result.manifestPath, '--access', 'public',
    ]);
  }
});

after(() => {
  // Verify the absolute target before recursively removing these owned fixtures.
  const resolved = fs.realpathSync(fixtureRoot);
  assert.equal(resolved, originalFixtureRoot);
  assert.match(path.basename(resolved), /^trade-cleanup-tests\./);
  fs.rmSync(resolved, { recursive: true, force: true });
});
