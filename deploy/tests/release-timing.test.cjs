// Offline release contract: all Docker/flock calls execute strict local mocks.
// Run with: node --test deploy/tests/release-timing.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const { test, after } = require('node:test');

const win = process.platform === 'win32';
const bash = process.env.TEST_BASH || (win ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const bashPath = (file) => win
  ? cp.execFileSync(bash, ['-c', 'cygpath -u "$1"', '--', file], { encoding: 'utf8' }).trim()
  : file;
let fixtureRoot, unixRoot;
if (win) {
  [unixRoot, fixtureRoot] = cp.execFileSync(bash, ['-c',
    'r=$(mktemp -d /tmp/trade-release-tests.XXXXXX)\nprintf "%s\\n" "$r"\ncygpath -w "$r"',
  ], { encoding: 'utf8' }).trim().split(/\r?\n/);
} else {
  fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-release-tests.'));
  unixRoot = fixtureRoot;
}
const ownedRoot = fs.realpathSync(fixtureRoot);
const nodePath = bashPath(process.execPath);
const source = fs.readFileSync(path.resolve(__dirname, '../scripts/release.sh'), 'utf8').replace(/\r\n/g, '\n');
const put = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, value, { mode: 0o755 });
};

function mockTool() {
  const fs = require('node:fs');
  const [tool, ...args] = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_STATE, 'utf8'));
  const reject = (message) => { process.stderr.write(message + '\n'); process.exit(91); };
  const record = (call) => fs.appendFileSync(process.env.TEST_AUDIT, JSON.stringify(call) + '\n');
  const failIfSelected = (command) => {
    if (state.delay === command) {
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, state.delayMs);
    }
    if (state.fail === command) {
      process.stderr.write('synthetic ' + command + ' failure\n');
      process.exit(state.exitCode);
    }
  };
  if (tool === 'flock') {
    if (args.join(' ') !== '-n 9') reject('Expected the existing nonblocking release lock');
    record({ tool, command: 'flock', args });
    process.exit(state.lockBusy ? 1 : 0);
  }
  if (tool !== 'docker') reject('Unexpected executable');
  if (args[0] === 'image' && args[1] === 'inspect' && args.length === 3) {
    record({ tool, command: 'image_inspect', args: args.slice(2) });
    failIfSelected('image_inspect');
    process.exit(0);
  }
  if (args[0] !== 'compose') reject('Only Compose and local image inspection are allowed');
  const envFiles = [], composeFiles = [];
  let composeRoot, index = 1;
  while (['--project-directory', '--env-file', '-f'].includes(args[index])) {
    const flag = args[index++], value = args[index++];
    if (!value) reject('Missing Compose option value');
    if (flag === '--project-directory') composeRoot = value;
    else if (flag === '--env-file') envFiles.push(value);
    else composeFiles.push(value);
  }
  const operation = args[index++], operationArgs = args.slice(index);
  if (!composeRoot || envFiles.length !== 2 || composeFiles.length < 1) reject('Incomplete Compose invocation');
  let command;
  if (operation === 'config') command = operationArgs[0] === '--images' ? 'config_images' : 'validation';
  else if (operation === 'pull') command = 'pull';
  else if (operation === 'stop') command = 'stop';
  else if (operation === 'up') command = operationArgs.includes('--no-recreate') ? 'dependencies' : 'health';
  else reject('Unexpected or destructive Compose operation');
  const images = Object.fromEntries(['BACKEND_IMAGE', 'COCKPIT_IMAGE', 'X_CONSOLE_IMAGE', 'EGRESS_IMAGE']
    .map((key) => [key, process.env[key]]));
  record({ tool, command, args: operationArgs, composeRoot, envFiles, composeFiles, images });
  failIfSelected(command);
  if (command === 'config_images') {
    const byService = { backend: images.BACKEND_IMAGE, cockpit: images.COCKPIT_IMAGE,
      'x-console': images.X_CONSOLE_IMAGE, egress: images.EGRESS_IMAGE,
      gateway: 'nginx:alpine', mysql: 'mysql:8.4', redis: 'redis:7' };
    for (const service of operationArgs.slice(1)) {
      if (!byService[service]) reject('Unknown image service');
      process.stdout.write(byService[service] + '\n');
    }
  }
}

put(path.join(fixtureRoot, 'mock.cjs'), '(' + mockTool.toString() + ')();\n');
for (const tool of ['docker', 'flock']) {
  // Keep Compose's --env-file arguments out of Node's own option processing.
  put(path.join(fixtureRoot, 'bin', tool),
    '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" exec "$TEST_NODE" -- "$TEST_MOCK" ' + tool + ' "$@"\n');
}
function manifest(tag) {
  return ['RELEASE_VERSION=' + tag, ...[
    ['BACKEND', 'backend'], ['COCKPIT', 'cockpit'], ['X_CONSOLE', 'x-console'], ['EGRESS', 'egress'],
  ].map(([key, service]) => key + '_IMAGE=trade/' + service + ':' + tag), ''].join('\n');
}

let sequence = 0;
function run(options = {}) {
  const name = 'case-' + (++sequence);
  const local = (relative) => path.join(fixtureRoot, name, relative);
  const unix = (relative = '') => unixRoot + '/' + name + (relative ? '/' + relative : '');
  const server = options.spacedRoot ? 'server root' : 'server';
  const runtimeSecret = 'synthetic-runtime-secret-must-stay-private';
  const sentinel = local('must-not-be-created');
  const runtime = 'RUNTIME_SECRET=' + runtimeSecret + '\nDO_NOT_SOURCE=$(touch "' + unix('must-not-be-created') + '")\n';
  put(local(server + '/compose.prod.yaml'), 'services: {}\n');
  put(local(server + '/compose.public.yaml'), 'services: {}\n');
  put(local(server + '/deploy/.env'), runtime);
  put(local(server + '/deploy/release.env'), manifest('desired'));
  put(local(server + '/deploy/releases/current.env'), manifest('current'));
  put(local(server + '/deploy/releases/previous.env'), manifest('previous'));
  put(local('state.json'), JSON.stringify({ fail: options.fail, exitCode: options.exitCode || 7,
    lockBusy: options.lockBusy, delay: options.delay, delayMs: options.delayMs }));
  put(local('audit.jsonl'), '');
  put(local('scratch/.keep'), '');
  const incoming = options.incoming !== false;
  const script = incoming ? 'incoming/new-release.sh' : server + '/deploy/scripts/release.sh';
  put(local(script), source);
  let rootArgument = unix(server);
  if (options.invalidRoot === 'filesystem') rootArgument = '/';
  if (options.invalidRoot === 'missing') rootArgument = unix('missing');
  if (options.invalidRoot === 'incomplete') {
    put(local('incomplete/deploy/.keep'), '');
    rootArgument = unix('incomplete');
  }
  const explicit = [];
  if (options.explicitFiles) {
    put(local('explicit/runtime.env'), runtime);
    put(local('explicit/release.env'), manifest('explicit'));
    explicit.push('--config', unix('explicit/runtime.env'), '--release', unix('explicit/release.env'));
  }
  const rootOptions = incoming ? ['--root', rootArgument] : [];
  const flags = options.rootLast ? [...explicit, ...rootOptions] : [...rootOptions, ...explicit];
  if (options.noPull) flags.push('--no-pull');
  if (options.access) flags.push('--access', options.access);
  const args = [options.action || 'deploy', ...(options.full ? [] : ['backend']), ...flags];
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$TEST_BIN:/usr/bin:/bin"\nexec bash "$TEST_SCRIPT" "$@"', '--', ...args,
  ], {
    env: { ...process.env, TEST_BIN: unixRoot + '/bin', TEST_NODE: nodePath,
      TEST_MOCK: path.join(fixtureRoot, 'mock.cjs'), TEST_STATE: local('state.json'), TEST_AUDIT: local('audit.jsonl'),
      TEST_SCRIPT: unix(script), DEPLOY_WAIT_TIMEOUT: '25', DEPLOY_ACCESS_MODE: 'private', TMPDIR: unix('scratch') },
    encoding: 'utf8', timeout: 30_000,
  });
  if (result.error) throw result.error;
  const diagnostic = result.stdout + result.stderr;
  assert.ok(!diagnostic.includes(runtimeSecret), 'Runtime configuration must never be printed');
  assert.equal(fs.existsSync(sentinel), false, 'Runtime configuration must never be sourced');
  const calls = fs.readFileSync(local('audit.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  const stages = [...diagnostic.matchAll(/DEPLOY_STAGE stage=(\w+) event=(start|complete|failed) elapsed_seconds=(\d+) exit_code=(\d+)/g)]
    .map(([, stage, event, elapsed, code]) => ({ stage, event, elapsed: Number(elapsed), code: Number(code) }));
  assert.deepEqual(fs.readdirSync(local('scratch')), ['.keep'], 'Temporary image manifests must be removed');
  assert.ok(calls.every((call) => !call.args.some((arg) => ['down', 'prune', '--volumes'].includes(arg))));
  return { ...result, calls, stages, diagnostic, local, unix, server, rootArgument };
}

function assertSuccessfulStages(result, names) {
  assert.deepEqual(result.stages.map(({ stage, event }) => [stage, event]),
    names.flatMap((name) => [[name, 'start'], [name, 'complete']]));
  assert.ok(result.stages.every(({ elapsed, code }) => Number.isInteger(elapsed) && elapsed >= 0 && code === 0));
}
const selectedStages = ['validation', 'image_pull', 'container_stop', 'container_start_and_health', 'release_record'];

test('an incoming script uses --root for configuration, Compose paths and successful release records', () => {
  const result = run({ spacedRoot: true });
  assert.equal(result.status, 0, result.diagnostic);
  assertSuccessfulStages(result, selectedStages);
  for (const call of result.calls.filter((call) => call.tool === 'docker')) {
    assert.equal(call.composeRoot, result.rootArgument);
    assert.equal(call.envFiles[0], result.rootArgument + '/deploy/.env');
    assert.deepEqual(call.composeFiles, [result.rootArgument + '/compose.prod.yaml']);
    assert.equal(call.images.BACKEND_IMAGE, 'trade/backend:desired');
    assert.equal(call.images.COCKPIT_IMAGE, 'trade/cockpit:current');
  }
  const current = fs.readFileSync(result.local(result.server + '/deploy/releases/current.env'), 'utf8');
  assert.match(current, /BACKEND_IMAGE=trade\/backend:desired/);
  assert.match(current, /COCKPIT_IMAGE=trade\/cockpit:current/);
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/previous.env'), 'utf8'), manifest('current'));
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/release.env'), 'utf8'), current);
  assert.equal(fs.existsSync(result.local('incoming/deploy')), false);
  assert.deepEqual(result.calls.find((call) => call.command === 'stop').args, ['backend']);
});

test('a repository script retains its original root and default file semantics without --root', () => {
  const result = run({ incoming: false });
  assert.equal(result.status, 0, result.diagnostic);
  assertSuccessfulStages(result, selectedStages);
  const config = result.calls.find((call) => call.command === 'validation');
  assert.equal(config.composeRoot, result.unix(result.server));
  assert.equal(config.envFiles[0], result.unix(result.server + '/deploy/.env'));
});

test('explicit --config and --release keep priority on either side of --root', () => {
  for (const rootLast of [true, false]) {
    const result = run({ explicitFiles: true, rootLast, access: 'public' });
    assert.equal(result.status, 0, result.diagnostic);
    const config = result.calls.find((call) => call.command === 'validation');
    assert.equal(config.envFiles[0], result.unix('explicit/runtime.env'));
    assert.equal(config.images.BACKEND_IMAGE, 'trade/backend:explicit');
    assert.deepEqual(config.composeFiles, [result.rootArgument + '/compose.prod.yaml', result.rootArgument + '/compose.public.yaml']);
    assert.equal(fs.readFileSync(result.local('explicit/release.env'), 'utf8'), manifest('explicit'));
  }
});

test('rollback from incoming defaults to the chosen root previous.env and preserves the current snapshot', () => {
  const result = run({ action: 'rollback' });
  assert.equal(result.status, 0, result.diagnostic);
  const config = result.calls.find((call) => call.command === 'validation');
  assert.equal(config.images.BACKEND_IMAGE, 'trade/backend:previous');
  assert.equal(config.images.COCKPIT_IMAGE, 'trade/cockpit:current');
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/previous.env'), 'utf8'), manifest('current'));
});

test('a failed pull records its original exit code and never stops or starts a container', () => {
  const result = run({ fail: 'pull', exitCode: 37 });
  assert.equal(result.status, 37, result.diagnostic);
  assert.deepEqual(result.calls.map((call) => call.command), ['flock', 'validation', 'pull']);
  assert.deepEqual(result.stages.map(({ stage, event, code }) => [stage, event, code]), [
    ['validation', 'start', 0], ['validation', 'complete', 0], ['image_pull', 'start', 0], ['image_pull', 'failed', 37],
  ]);
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/current.env'), 'utf8'), manifest('current'));
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/previous.env'), 'utf8'), manifest('previous'));
  assert.ok(!result.diagnostic.includes('Containers may be stopped'));
});

test('--no-pull reports local image checks and completes every inspection before stopping', () => {
  const result = run({ noPull: true });
  assert.equal(result.status, 0, result.diagnostic);
  assertSuccessfulStages(result, ['validation', 'local_image_check', ...selectedStages.slice(2)]);
  assert.deepEqual(result.calls.map((call) => call.command), ['flock', 'validation', 'config_images', 'image_inspect', 'stop', 'health']);
});

test('a missing local image preserves the inspection error and cannot stop containers', () => {
  const result = run({ noPull: true, fail: 'image_inspect', exitCode: 17 });
  assert.equal(result.status, 17, result.diagnostic);
  const { stage, event, code } = result.stages.at(-1);
  assert.deepEqual({ stage, event, code }, { stage: 'local_image_check', event: 'failed', code: 17 });
  assert.ok(!result.calls.some((call) => ['stop', 'health', 'dependencies', 'pull'].includes(call.command)));
});

test('full deployment pulls everything first and times dependency startup before application stop', () => {
  const result = run({ full: true });
  assert.equal(result.status, 0, result.diagnostic);
  assertSuccessfulStages(result, ['validation', 'image_pull', 'dependencies_start', ...selectedStages.slice(2)]);
  assert.deepEqual(result.calls.map((call) => call.command), ['flock', 'validation', 'pull', 'dependencies', 'stop', 'health']);
  assert.deepEqual(result.calls.find((call) => call.command === 'pull').args,
    ['egress', 'backend', 'cockpit', 'x-console', 'gateway', 'mysql', 'redis']);
  const dependencyArgs = result.calls.find((call) => call.command === 'dependencies').args;
  assert.ok(dependencyArgs.includes('--no-recreate'));
  assert.deepEqual(dependencyArgs.slice(-2), ['mysql', 'redis']);
});

test('a health failure retains successful release records, a retry target, and the original error', () => {
  const result = run({ fail: 'health', exitCode: 23 });
  assert.equal(result.status, 23, result.diagnostic);
  assert.equal(result.stages.at(-1).stage, 'container_start_and_health');
  assert.equal(result.stages.at(-1).event, 'failed');
  assert.equal(result.stages.at(-1).code, 23);
  assert.ok(!result.stages.some(({ stage }) => stage === 'release_record'));
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/current.env'), 'utf8'), manifest('current'));
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/previous.env'), 'utf8'), manifest('current'));
  assert.ok(fs.readdirSync(result.local(result.server + '/deploy/releases')).some((name) => /^target-.*\.env$/.test(name)));
  assert.match(result.stderr, /Containers may be stopped or partially updated/);
});

test('invalid or incomplete root directories fail validation before any Docker call', () => {
  for (const invalidRoot of ['filesystem', 'missing', 'incomplete']) {
    const result = run({ invalidRoot });
    assert.equal(result.status, 1, result.diagnostic);
    assert.deepEqual(result.calls, []);
    assert.equal(result.stages.at(-1).stage, 'validation');
    assert.equal(result.stages.at(-1).event, 'failed');
    assert.equal(result.stages.at(-1).code, 1);
  }
});

test('validate reports only validation timing and changes no containers or image records', () => {
  const result = run({ action: 'validate' });
  assert.equal(result.status, 0, result.diagnostic);
  assertSuccessfulStages(result, ['validation']);
  assert.deepEqual(result.calls.map((call) => call.command), ['validation']);
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/current.env'), 'utf8'), manifest('current'));
  assert.equal(fs.readFileSync(result.local(result.server + '/deploy/releases/previous.env'), 'utf8'), manifest('previous'));
});

test('image pull elapsed time advances while the mocked Docker command is busy', () => {
  const result = run({ delay: 'pull', delayMs: 1200 });
  assert.equal(result.status, 0, result.diagnostic);
  const completed = result.stages.find(({ stage, event }) => stage === 'image_pull' && event === 'complete');
  assert.ok(completed.elapsed >= 1, result.diagnostic);
});

after(() => {
  const resolved = fs.realpathSync(fixtureRoot);
  assert.equal(resolved, ownedRoot);
  assert.match(path.basename(resolved), /^trade-release-tests\./);
  fs.rmSync(resolved, { recursive: true, force: true });
});
