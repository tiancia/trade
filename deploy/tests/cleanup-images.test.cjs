// Offline only: Docker and flock are replaced by strict local mocks.
// Run with: node deploy/tests/cleanup-images.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const { createHash } = require('node:crypto');
const vm = require('node:vm');
const { test, after } = require('node:test');

const isWindows = process.platform === 'win32';
const bash = process.env.TEST_BASH || (isWindows ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const script = path.resolve(__dirname, '../scripts/cleanup-images.sh');
const registryHost = 'ccr.ccs.tencentyun.com';
const registry = registryHost + '/tiancia-trade';

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

const workflowText = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/build-deploy.yml'), 'utf8').replace(/\r\n/g, '\n');
function runnerScript(name) {
  const start = workflowText.indexOf('      - name: ' + name + '\n');
  assert.ok(start >= 0, 'Missing workflow step: ' + name);
  const end = workflowText.indexOf('\n      - name:', start + 1);
  const block = workflowText.slice(start, end < 0 ? undefined : end);
  const run = block.match(/        run: \|\r?\n([\s\S]*)/);
  assert.ok(run, 'Missing run script: ' + name);
  return run[1].replace(/^ {10}/gm, '');
}
const loginScript = runnerScript('Log in to Tencent Container Registry');
const existingScript = runnerScript('Fetch an existing build without rebuilding images');
const sourceScript = existingScript.match(/node <<'NODE'\r?\n([\s\S]*?)\r?\nNODE/)[1];
const remoteBlock = workflowText.match(/remote_script=\$\(cat <<'REMOTE'\r?\n([\s\S]*?)\r?\n[ \t]*REMOTE/);
assert.ok(remoteBlock, 'The workflow must contain its remote deployment script');
const remoteScript = remoteBlock[1].replace(/^ {10}/gm, '');
const fixtureUsername = 'fixture-tcr-user';
const fixturePassword = 'fixture-password-with spaces$';

function loginMock() {
  return [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    '[[ $# == 5 && $1 == login && $2 == "$TEST_REGISTRY_HOST" && $3 == --username && $4 == "$TEST_TCR_USER" && $5 == --password-stdin ]] || exit 44',
    'password=$(cat)',
    '[[ $password == "$TEST_TCR_PASSWORD" ]] || exit 45',
    'printf "login\\n" >> "$WORKFLOW_AUDIT"',
    'if [[ -n ${WORKFLOW_CONFIG_RECORD:-} ]]; then',
    '  printf "%s\\n" "$DOCKER_CONFIG" > "$WORKFLOW_CONFIG_RECORD"',
    '  printf "{\\"auths\\":{\\"fixture\\":{\\"auth\\":\\"dummy\\"}}}\\n" > "$DOCKER_CONFIG/config.json"',
    'fi',
    'if [[ $WORKFLOW_FAIL == login ]]; then exit 46; fi',
    '',
  ].join('\n');
}

function runLogin(options = {}) {
  const name = 'login-' + (++sequence);
  write(local(name + '/audit'), '');
  write(local(name + '/bin/docker'), loginMock());
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$WORKFLOW_BIN:/usr/bin:/bin"\n' + loginScript
      + '\nprintf "build-allowed\\n" >> "$WORKFLOW_AUDIT"\n',
  ], {
    env: {
      ...process.env,
      WORKFLOW_BIN: posix(name + '/bin'), WORKFLOW_AUDIT: posix(name + '/audit'),
      WORKFLOW_FAIL: options.fail || '', TEST_REGISTRY_HOST: registryHost,
      TEST_TCR_USER: fixtureUsername, TEST_TCR_PASSWORD: fixturePassword,
      REGISTRY_HOST: registryHost,
      TCR_USERNAME: options.noUsername ? '' : fixtureUsername,
      TCR_PASSWORD: options.noPassword ? '' : fixturePassword,
    },
    encoding: 'utf8', timeout: 15_000,
  });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(local(name + '/audit'), 'utf8').trim().split('\n').filter(Boolean);
  assert.ok(!(result.stdout + result.stderr).includes(fixturePassword), 'Never print the password');
  return { ...result, calls, diagnostic: result.stdout + result.stderr };
}

test('runner logs in to the fixed TCR host with the password on stdin', () => {
  const result = runLogin();
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls, ['login', 'build-allowed']);
});

test('missing TCR username or password stops the runner before Docker login', () => {
  for (const options of [{ noUsername: true }, { noPassword: true }]) {
    const result = runLogin(options);
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.deepEqual(result.calls, []);
    assert.match(result.diagnostic, /Missing TCR_(USERNAME|PASSWORD)/);
  }
});

test('runner registry login failure prevents later build or deployment work', () => {
  const result = runLogin({ fail: 'login' });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls, ['login']);
});

function runWorkflow(options = {}) {
  const name = 'workflow-' + (++sequence);
  const root = posix(name + '/server');
  const manifestPath = root + '/deploy/incoming/version/release.env';
  const configRecord = posix(name + '/docker-config-path');
  write(local(name + '/audit'), '');
  write(local(name + '/docker-config-path'), '');
  write(local(name + '/scratch/.keep'), '');
  write(local(name + '/server/deploy/incoming/version/release.env'), manifest());
  write(local(name + '/server/deploy/scripts/release.sh'), '#!/usr/bin/env bash\nexit 89\n');
  write(local(name + '/server/deploy/incoming/version/release.sh'), [
    '#!/usr/bin/env bash', 'set -euo pipefail',
    'test -f "$DOCKER_CONFIG/config.json"',
    'printf "release" >> "$WORKFLOW_AUDIT"',
    'printf "\\t%s" "$@" >> "$WORKFLOW_AUDIT"',
    'printf "\\n" >> "$WORKFLOW_AUDIT"',
    'if [[ $WORKFLOW_FAIL == "$1" ]]; then exit 42; fi', '',
  ].join('\n'));
  write(local(name + '/server/deploy/incoming/version/cleanup-images.sh'), [
    '#!/usr/bin/env bash', 'set -euo pipefail',
    'printf "cleanup" >> "$WORKFLOW_AUDIT"',
    'printf "\\t%s" "$@" >> "$WORKFLOW_AUDIT"',
    'printf "\\n" >> "$WORKFLOW_AUDIT"',
    'if [[ $WORKFLOW_FAIL == cleanup ]]; then exit 43; fi', '',
  ].join('\n'));
  write(local(name + '/bin/docker'), loginMock());
  const result = cp.spawnSync(bash, [
    '-c', 'export PATH="$WORKFLOW_BIN:/usr/bin:/bin"\n' + remoteScript,
    '--', root, manifestPath, options.access || 'private', options.service || 'backend', registryHost, registry,
  ], {
    env: {
      ...process.env, WORKFLOW_BIN: posix(name + '/bin'), WORKFLOW_AUDIT: posix(name + '/audit'),
      WORKFLOW_CONFIG_RECORD: configRecord, WORKFLOW_FAIL: options.fail || '',
      TEST_REGISTRY_HOST: registryHost, TEST_TCR_USER: fixtureUsername, TEST_TCR_PASSWORD: fixturePassword,
      TMPDIR: posix(name + '/scratch'),
    },
    input: (options.noUsername ? '' : fixtureUsername) + '\n' + fixturePassword + '\n',
    encoding: 'utf8', timeout: 15_000,
  });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(local(name + '/audit'), 'utf8').trim().split('\n').filter(Boolean)
    .map((line) => line.split('\t'));
  const configPath = fs.readFileSync(local(name + '/docker-config-path'), 'utf8').trim();
  if (!options.noUsername && options.service !== 'unsupported') {
    assert.ok(configPath, 'Docker login must use temporary credentials');
    assert.ok(configPath.startsWith(posix(name + '/scratch') + '/'), 'Config must stay inside owned fixtures');
    const localConfig = path.join(local(name + '/scratch'), path.posix.basename(configPath));
    assert.equal(fs.existsSync(localConfig), false, 'Temporary Docker config must be removed on every exit');
  } else {
    assert.equal(configPath, '');
  }
  assert.deepEqual(fs.readdirSync(local(name + '/scratch')), ['.keep'], 'No temporary credential directory may remain');
  assert.ok(!(result.stdout + result.stderr).includes(fixturePassword), 'Never print the password');
  return { ...result, calls, root, manifestPath, diagnostic: result.stdout + result.stderr };
}

test('workflow logs in with temporary TCR credentials before validating and deploying', () => {
  const result = runWorkflow();
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call[0] === 'release' ? call[1] : call[0]), [
    'login', 'validate', 'deploy', 'cleanup',
  ]);
  assert.ok(result.stdout.includes('DEPLOYMENT_OK'), result.diagnostic);
  assert.ok(!result.stdout.includes('::warning::'), result.diagnostic);
});

test('remote missing username or unsupported service stops before registry login', () => {
  for (const options of [{ noUsername: true }, { service: 'unsupported' }]) {
    const result = runWorkflow(options);
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.deepEqual(result.calls, []);
  }
});

test('remote registry login or validation failure prevents deployment and cleans credentials', () => {
  for (const fail of ['login', 'validate']) {
    const result = runWorkflow({ fail });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.ok(!result.calls.some((call) => call[0] === 'release' && call[1] === 'deploy'));
    assert.ok(!result.calls.some((call) => call[0] === 'cleanup'));
    assert.ok(!result.stdout.includes('DEPLOYMENT_OK'));
  }
});

test('failed deployment retains failure and skips cleanup while removing registry credentials', () => {
  const result = runWorkflow({ fail: 'deploy' });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.ok(!result.calls.some((call) => call[0] === 'cleanup'));
  assert.ok(!result.stdout.includes('DEPLOYMENT_OK'));
});

test('cleanup failure only warns after healthy deployment and still removes registry credentials', () => {
  const result = runWorkflow({ fail: 'cleanup' });
  assert.equal(result.status, 0, result.diagnostic);
  assert.ok(result.stdout.includes('DEPLOYMENT_OK'));
  assert.ok(result.stdout.includes('::warning::'));
});

test('workflow preserves exact service scope, access mode, normal pulls, and TCR cleanup namespace', () => {
  for (const [service, targets] of [
    ['backend', ['backend']], ['cockpit', ['cockpit']], ['x-console', ['x-console']],
    ['egress', ['egress']], ['frontends', ['cockpit', 'x-console']], ['all', []],
  ]) {
    for (const access of ['private', 'public']) {
      const result = runWorkflow({ service, access });
      assert.equal(result.status, 0, result.diagnostic);
      assert.deepEqual(result.calls.find((call) => call[0] === 'cleanup'), [
        'cleanup', '--root', result.root, '--registry', registry, '--apply',
      ]);
      assert.deepEqual(result.calls.find((call) => call[0] === 'release' && call[1] === 'deploy'), [
        'release', 'deploy', ...targets, '--root', result.root, '--release', result.manifestPath, '--access', access,
      ]);
    }
  }
});

test('workflow ships its release script and restores independent service caches before building', () => {
  const deployScript = runnerScript('Deploy only the selected services');
  assert.match(deployScript, /deploy\/scripts\/release\.sh deploy\/scripts\/cleanup-images\.sh/);
  assert.match(remoteScript, /release_script="\$\(dirname -- "\$manifest"\)\/release\.sh"/);
  assert.match(remoteScript, /bash "\$release_script" validate --root "\$root"/);
  assert.match(remoteScript, /bash "\$release_script" deploy "\$\{targets\[@\]\}" --root "\$root"/);
  const build = runnerScript('Build and push only the selected images');
  assert.match(build, /--cache-dir "\$RUNNER_TEMP\/trade-build-cache"/);
  for (const service of ['backend', 'cockpit', 'x-console', 'egress']) {
    const restoreStart = workflowText.indexOf('- name: Restore ' + service + ' build layers');
    const saveStart = workflowText.indexOf('- name: Save ' + service + ' build layers');
    assert.ok(restoreStart >= 0 && restoreStart < workflowText.indexOf('- name: Build and push only the selected images'));
    assert.ok(saveStart > restoreStart);
    const restore = workflowText.slice(restoreStart, workflowText.indexOf('\n      - name:', restoreStart));
    const save = workflowText.slice(saveStart, workflowText.indexOf('\n      - name:', saveStart));
    assert.ok(restore.includes('/trade-build-cache/' + service));
    assert.ok(restore.includes('trade-buildkit-v1-' + service + '-'));
    assert.match(save, /always\(\)/, 'successful exports should survive later push failures');
    assert.ok(save.includes('/trade-build-cache/' + service));
  }
});

test('cache persistence ignores restored indexes and stale export markers', () => {
  const name = 'cache-ready-' + (++sequence);
  write(local(name + '/output'), '');
  for (const service of ['backend', 'cockpit', 'x-console', 'egress']) {
    write(local(name + '/trade-build-cache/' + service + '/index.json'), '{}');
  }
  write(local(name + '/trade-build-cache/.exported-cockpit'), 'old-release\n');
  write(local(name + '/trade-build-cache/.exported-x-console'), 'current-release\n');
  write(local(name + '/trade-build-cache/.exported-egress'), 'current-release\n');
  const result = cp.spawnSync(bash, ['-c', runnerScript('Inspect reusable build caches')], {
    env: { ...process.env, RUNNER_TEMP: posix(name), GITHUB_OUTPUT: posix(name + '/output'),
      IMAGE_TAG: 'current-release', TARGET_SERVICES: 'backend cockpit x-console egress' },
    encoding: 'utf8', timeout: 15_000,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.deepEqual(fs.readFileSync(local(name + '/output'), 'utf8').trim().split(/\r?\n/),
    ['x_console=true', 'egress=true']);
});

function runSelection(options = {}) {
  const name = 'selection-' + (++sequence);
  write(local(name + '/release/.keep'), '');
  write(local(name + '/env'), '');
  write(local(name + '/summary'), '');
  const sha = 'a'.repeat(40);
  const candidates = options.candidates || [{ id: 51, registry }];
  const entries = candidates.map((candidate) => {
    const tag = `sha-${sha}-${candidate.id}-1`;
    const refs = Object.fromEntries(['backend', 'cockpit', 'x-console', 'egress']
      .map((service) => [service, `${candidate.registry}/${service}:${tag}`]));
    if (candidate.inherited) refs[candidate.inherited] = 'trade/' + candidate.inherited + ':older';
    const content = [
      'RELEASE_VERSION=' + tag, 'BACKEND_IMAGE=' + refs.backend, 'COCKPIT_IMAGE=' + refs.cockpit,
      'X_CONSOLE_IMAGE=' + refs['x-console'], 'EGRESS_IMAGE=' + refs.egress, '',
    ].join('\n');
    const zip = Buffer.from('fixture zip ' + candidate.id);
    return { ...candidate, content, zip, artifact: {
      id: candidate.id + 100, name: 'image-manifest-' + tag, expired: false,
      created_at: `2026-10-22T00:00:${String(candidate.id).padStart(2, '0')}Z`,
      size_in_bytes: zip.length, digest: 'sha256:' + createHash('sha256').update(zip).digest('hex'),
      workflow_run: { id: candidate.id, head_branch: 'main', repository_id: 1, head_repository_id: 1 },
    } };
  });
  let selectedArchive;
  const commands = [];
  const execFileSync = (command, args) => {
    commands.push([command, ...args]);
    if (command === 'unzip') {
      if (args[0] === '-Z1') return 'release.env\n';
      if (args[0] === '-p') return selectedArchive.content;
    }
    assert.equal(command, 'gh');
    assert.equal(args[0], 'api');
    const endpoint = args[1];
    if (/\/artifacts\?/.test(endpoint)) return JSON.stringify([{ artifacts: entries.map((entry) => entry.artifact) }]);
    const zip = endpoint.match(/\/artifacts\/(\d+)\/zip$/);
    if (zip) {
      selectedArchive = entries.find((entry) => entry.artifact.id === Number(zip[1]));
      return selectedArchive.zip;
    }
    const run = endpoint.match(/\/actions\/runs\/(\d+)$/);
    if (run) return JSON.stringify({
      path: '.github/workflows/build-deploy.yml', event: 'workflow_dispatch', status: 'completed',
      head_branch: 'main', head_sha: sha, head_repository: { full_name: 'tiancia/trade' },
    });
    throw new Error('Unexpected mock gh endpoint: ' + endpoint);
  };
  const env = {
    GITHUB_REPOSITORY: 'tiancia/trade', GITHUB_REF_NAME: 'main', GITHUB_RUN_ID: '99',
    GITHUB_SERVER_URL: 'https://github.com', SOURCE_RUN_ID: options.sourceRunId || '',
    IMAGE_REGISTRY: registry, TARGET_SERVICES: options.targets || 'cockpit',
    RELEASE_DIR: local(name + '/release'), GITHUB_ENV: local(name + '/env'),
    GITHUB_STEP_SUMMARY: local(name + '/summary'),
  };
  let error;
  try {
    vm.runInNewContext(sourceScript, {
      require: (id) => id === 'node:child_process' ? { execFileSync } : require(id),
      process: { env }, Buffer,
    }, { filename: 'workflow-existing-build.js', timeout: 2000 });
  } catch (caught) { error = caught; }
  const file = local(name + '/release/release.env');
  return { error, commands, manifest: fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined,
    env: fs.readFileSync(local(name + '/env'), 'utf8') };
}

test('existing-build selection accepts the exact TCR namespace without pulling images on the runner', () => {
  const result = runSelection();
  assert.equal(result.error, undefined);
  assert.ok(result.manifest.includes(registry + '/cockpit:sha-'));
  assert.ok(result.env.includes('IMAGE_TAG=sha-'));
  assert.ok(result.commands.every((command) => ['gh', 'unzip'].includes(command[0])));
});

test('existing-build selection rejects old GHCR manifests and can select an older matching TCR build', () => {
  const old = { id: 52, registry: 'ghcr.io/tiancia/trade' };
  const rejected = runSelection({ candidates: [old] });
  assert.match(rejected.error?.message || '', /No retained build matches/);
  assert.equal(rejected.manifest, undefined);
  const selected = runSelection({ candidates: [old, { id: 51, registry }] });
  assert.equal(selected.error, undefined);
  assert.ok(selected.manifest.includes(registry + '/cockpit:sha-'));
});

test('existing-build selection requires every selected service to match the TCR build', () => {
  const result = runSelection({ targets: 'cockpit x-console', candidates: [{ id: 51, registry, inherited: 'x-console' }] });
  assert.match(result.error?.message || '', /No retained build matches/);
  assert.equal(result.manifest, undefined);
});

test('explicit source run does not silently fall back to a different TCR build', () => {
  const result = runSelection({ sourceRunId: '52', candidates: [
    { id: 52, registry: 'ghcr.io/tiancia/trade' }, { id: 51, registry },
  ] });
  assert.match(result.error?.message || '', /No retained build matches/);
  assert.equal(result.manifest, undefined);
});

const buildImagesPath = path.resolve(__dirname, '../scripts/build-images.sh');
const buildImagesSource = fs.readFileSync(buildImagesPath, 'utf8').replace(/\r\n/g, '\n');
const filterStart = buildImagesSource.indexOf('filter_push_progress() {');
const filterEnd = buildImagesSource.indexOf('\npush_image() {', filterStart);
assert.ok(filterStart >= 0 && filterEnd > filterStart, 'The upload progress filter must be testable independently');
const filterDefinition = buildImagesSource.slice(filterStart, filterEnd);

// Strict mocks execute the real generated shell command, never a registry call.
function mockPushTool() {
  const fs = require('node:fs');
  const cp = require('node:child_process');
  const [tool, ...args] = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_PUSH_STATE, 'utf8'));
  fs.appendFileSync(process.env.TEST_PUSH_AUDIT, JSON.stringify({ tool, args }) + '\n');
  const reject = (reason) => { process.stderr.write(reason + '\n'); process.exitCode = 91; };
  const runShell = (shellArgs) => {
    // Bind the mock explicitly across Windows Node -> Git Bash environment conversion.
    shellArgs = [...shellArgs];
    shellArgs[1] = 'docker() { MSYS2_ARG_CONV_EXCL="*" "$TEST_NODE" "$TEST_PUSH_MOCK" docker "$@"; }\n' + shellArgs[1];
    const result = cp.spawnSync(process.env.TEST_PUSH_BASH, shellArgs, { stdio: 'inherit', env: process.env });
    if (result.error) throw result.error;
    process.exitCode = result.status ?? 92;
  };
  if (tool === 'script') {
    if (args.length !== 6 || args[0] !== '--quiet' || args[1] !== '--return' || args[2] !== '--flush'
      || args[3] !== '--command' || args[5] !== '/dev/null') return reject('Unexpected pseudo-terminal arguments');
    return runShell(['-c', args[4]]);
  }
  if (tool !== 'docker') return reject('Unexpected upload test tool');
  if (args[0] === 'build') {
    if (args.length !== 6 || args[1] !== '--platform' || args[2] !== 'linux/amd64'
      || args[3] !== '--tag' || args[4] !== state.image || !args[5].replaceAll('\\', '/').endsWith('/frontend/trading-cockpit')) {
      return reject('Unexpected selected-image build arguments');
    }
    return;
  }
  if (args.length !== 2 || args[0] !== 'push' || args[1] !== state.image) return reject('Unexpected docker push arguments');
  process.stdout.write(state.output);
  process.exitCode = state.pushExit;
}

function runPushBuild(options = {}) {
  const name = 'push-build-' + (++sequence);
  const image = registry + '/cockpit:diag-20261023.1_1';
  const payload = [
    '\u001b[2K\r111111aaaaaa: Waiting\r\n',
    '\u001b[1A\u001b[2K\r111111aaaaaa: Pushing [>   ] 1MB/4MB\r',
    '\u001b[1A\u001b[2K\r111111aaaaaa: Pushing [=>  ] 2MB/4MB\r',
    '\u001b[1A\u001b[2K\r222222bbbbbb: Pushing [>   ] 1MB/8MB\r',
    '\u001b[1A\u001b[2K\r222222bbbbbb: Pushing [=>  ] 2MB/8MB\r',
    '\u001b[1A\u001b[2K\r111111aaaaaa: Pushed\r\n',
    '222222bbbbbb: Layer already exists\r\n',
    '333333cccccc: Pushing [>   ] 1MB/5MB\r',
    '333333cccccc: Pushing [=>  ] 2MB/5MB\r',
    'diag-20261023.1_1: digest: sha256:' + 'a'.repeat(64) + ' size: 1572\r\n',
    ...(options.pushExit ? ['error: fixture connection reset by peer\r\n'] : []),
  ].join('');
  write(local(name + '/base.env'), manifest());
  write(local(name + '/audit.jsonl'), '');
  write(local(name + '/state.json'), JSON.stringify({ image, output: payload, pushExit: options.pushExit || 0 }));
  write(local(name + '/mock-tool.cjs'), '(' + mockPushTool.toString() + ')();\n');
  for (const tool of ['docker', 'script']) {
    write(local(name + '/bin/' + tool), '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" exec "$TEST_NODE" "$TEST_PUSH_MOCK" ' + tool + ' "$@"\n');
  }
  const outputPath = local(name + '/output dir/release.env');
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$TEST_PUSH_BIN:/usr/bin:/bin"\nexec bash "$TEST_BUILD_SCRIPT" --registry "$TEST_REGISTRY" --tag diag-20261023.1_1 --base "$TEST_BASE" --output "$TEST_OUTPUT" --push cockpit',
  ], {
    env: {
      ...process.env, TEST_NODE: nodePath, TEST_PUSH_BASH: bash,
      TEST_PUSH_MOCK: local(name + '/mock-tool.cjs'), TEST_PUSH_STATE: local(name + '/state.json'),
      TEST_PUSH_AUDIT: local(name + '/audit.jsonl'), TEST_PUSH_BIN: posix(name + '/bin'),
      TEST_BUILD_SCRIPT: bashPath(buildImagesPath), TEST_REGISTRY: registry,
      TEST_BASE: posix(name + '/base.env'), TEST_OUTPUT: posix(name + '/output dir/release.env'),
      GITHUB_ACTIONS: options.actions === false ? 'false' : 'true',
    },
    encoding: 'utf8', timeout: 15_000,
  });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(local(name + '/audit.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  return { ...result, calls, image, outputPath, diagnostic: result.stdout + result.stderr };
}

test('Actions push uses the pseudo-terminal without imposing a shorter upload timeout', () => {
  const result = runPushBuild();
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call.tool), ['docker', 'script', 'docker']);
  assert.deepEqual(result.calls.at(-1).args, ['push', result.image]);
  assert.equal(result.calls.find((call) => call.tool === 'script').args[4], 'docker push ' + result.image);
  assert.ok(fs.readFileSync(result.outputPath, 'utf8').includes('COCKPIT_IMAGE=' + result.image));
});

test('Actions push strips terminal redraws and throttles progress while retaining final states and digest', () => {
  const result = runPushBuild();
  assert.equal(result.status, 0, result.diagnostic);
  assert.ok(!result.stdout.includes('\u001b'), result.diagnostic);
  assert.ok(!result.stdout.includes('\r'), result.diagnostic);
  assert.equal(result.stdout.match(/111111aaaaaa: Pushing/g)?.length, 1, result.diagnostic);
  assert.equal(result.stdout.match(/222222bbbbbb: Pushing/g)?.length, 1, result.diagnostic);
  for (const state of ['111111aaaaaa: Waiting', '111111aaaaaa: Pushed', '222222bbbbbb: Layer already exists', 'digest: sha256:']) {
    assert.ok(result.stdout.includes(state), result.diagnostic);
  }
  assert.ok(result.stdout.includes('Last observed progress: 333333cccccc: Pushing [=>  ] 2MB/5MB'), result.diagnostic);
  assert.ok(!result.stdout.includes('Last observed progress: 111111aaaaaa'), result.diagnostic);
});

test('failed Actions push preserves its exit code and error without writing a release manifest', () => {
  const result = runPushBuild({ pushExit: 7 });
  assert.equal(result.status, 7, result.diagnostic);
  assert.ok(result.stdout.includes('error: fixture connection reset by peer'), result.diagnostic);
  assert.ok(!fs.existsSync(result.outputPath));
});

test('Actions push preserves exit 124 without claiming a local upload timeout', () => {
  const result = runPushBuild({ pushExit: 124 });
  assert.equal(result.status, 124, result.diagnostic);
  assert.ok(!result.stderr.includes('15 minute limit'), result.diagnostic);
  assert.ok(!fs.existsSync(result.outputPath));
  assert.deepEqual(result.calls.filter((call) => call.tool === 'docker').map((call) => call.args[0]), ['build', 'push']);
});

test('a killed push preserves exit 137 without asserting that a timeout caused it', () => {
  const result = runPushBuild({ pushExit: 137 });
  assert.equal(result.status, 137, result.diagnostic);
  assert.ok(result.stderr.includes('was killed (exit 137'), result.diagnostic);
  assert.ok(!result.stderr.includes('exceeded the 15 minute limit'), result.diagnostic);
  assert.ok(!fs.existsSync(result.outputPath));
});

test('non-Actions builds keep the original direct Docker push path and raw output', () => {
  const result = runPushBuild({ actions: false });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call.tool), ['docker', 'docker']);
  assert.deepEqual(result.calls.at(-1).args, ['push', result.image]);
  assert.equal(result.stdout.match(/111111aaaaaa: Pushing/g)?.length, 2);
  assert.ok(result.stdout.includes('\u001b'));
  assert.ok(fs.existsSync(result.outputPath));
});

test('the real progress filter streams its first update immediately and permits another after ten seconds', async () => {
  const child = cp.spawn(bash, ['-c', filterDefinition + '\nfilter_push_progress'], {
    env: { ...process.env, PATH: isWindows ? process.env.PATH : '/usr/bin:/bin' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  let firstOutput;
  const streamed = new Promise((resolve) => { firstOutput = resolve; });
  child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; firstOutput(); });
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  const completed = new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('close', (status) => status === 0 ? resolve() : reject(new Error(stderr + ' exit=' + status)));
  });
  const abort = setTimeout(() => { child.kill(); }, 17_000);
  let firstOutputDeadline;
  try {
    child.stdin.write('\u001b[2K\r333333cccccc: Pushing 1MB/4MB\r');
    await Promise.race([streamed, new Promise((_, reject) => {
      firstOutputDeadline = setTimeout(() => reject(new Error('First progress update remained buffered')), 2000);
    })]);
    clearTimeout(firstOutputDeadline);
    assert.ok(stdout.includes('333333cccccc: Pushing 1MB/4MB'), stdout + stderr);
    child.stdin.write('333333cccccc: Pushing 2MB/4MB\r');
    await new Promise((resolve) => setTimeout(resolve, 10_200));
    child.stdin.end('333333cccccc: Pushing 3MB/4MB\r333333cccccc: Pushing 4MB/4MB\r333333cccccc: Pushed\n');
    await completed;
    assert.equal(stdout.match(/333333cccccc: Pushing/g)?.length, 2, stdout + stderr);
    assert.ok(stdout.includes('333333cccccc: Pushed'), stdout + stderr);
  } finally {
    clearTimeout(firstOutputDeadline);
    clearTimeout(abort);
    if (child.exitCode === null) {
      child.kill();
      await completed.catch(() => {});
    }
  }
});

after(() => {
  // Verify the absolute target before recursively removing these owned fixtures.
  const resolved = fs.realpathSync(fixtureRoot);
  assert.equal(resolved, originalFixtureRoot);
  assert.match(path.basename(resolved), /^trade-cleanup-tests\./);
  fs.rmSync(resolved, { recursive: true, force: true });
});
