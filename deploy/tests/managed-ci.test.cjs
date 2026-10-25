// Offline contract checks: Docker, git and every SSH/SCP boundary are local mocks.
// Run with: node --test deploy/tests/managed-ci.test.cjs
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
let root, unixRoot;
if (win) {
  [unixRoot, root] = cp.execFileSync(bash, ['-c',
    'r=$(mktemp -d /tmp/trade-managed-tests.XXXXXX)\nprintf "%s\\n" "$r"\ncygpath -w "$r"',
  ], { encoding: 'utf8' }).trim().split(/\r?\n/);
} else {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-managed-tests.'));
  unixRoot = root;
}
const ownedRoot = fs.realpathSync(root);
const nodePath = bashPath(process.execPath);
const ciSource = fs.readFileSync(path.resolve(__dirname, '../scripts/managed-ci.sh'), 'utf8').replace(/\r\n/g, '\n');
const registry = 'ccr.ccs.tencentyun.com/tiancia-trade';
const actualSha = 'a'.repeat(40);
const historicalTag = 'sha-' + 'b'.repeat(40) + '-cn-4-123456abcdef';
const services = { BACKEND_IMAGE: 'backend', COCKPIT_IMAGE: 'cockpit', X_CONSOLE_IMAGE: 'x-console', EGRESS_IMAGE: 'egress' };
const username = 'synthetic-tcr-user';
const password = 'synthetic-password $with spaces';
const privateKey = 'synthetic-private-key-material';
const hostKey = 'synthetic-known-host-key-material';
const originalDockerConfig = '{"auths":{"preserve-existing":"synthetic-existing-config"}}\n';
const originalBuildxCurrent = '{"Name":"fixture-platform-builder","Global":true}\n';
const originalBuildxInstance = '{"Name":"fixture-platform-builder","Driver":"remote","Nodes":[{"Endpoint":"unix:///run/platform-buildkit.sock"}]}\n';
const put = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, value, { mode: 0o755 });
};
const serialize = (values) => ['RELEASE_VERSION=' + values.RELEASE_VERSION,
  ...Object.keys(services).map((key) => key + '=' + values[key]), ''].join('\n');
const baseline = () => ({ RELEASE_VERSION: 'old-release', BACKEND_IMAGE: 'trade/backend:old',
  COCKPIT_IMAGE: 'old-registry.example/team/cockpit:keep', X_CONSOLE_IMAGE: 'trade/x-console:keep', EGRESS_IMAGE: 'trade/egress:keep' });
const existing = () => ({ ...baseline(), RELEASE_VERSION: historicalTag, BACKEND_IMAGE: registry + '/backend:' + historicalTag });
const parse = (text) => Object.fromEntries(text.trim().split(/\r?\n/).filter((line) => line && !line.startsWith('#'))
  .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));

function mockTool() {
  const fs = require('node:fs');
  const path = require('node:path');
  const cp = require('node:child_process');
  const [tool, ...args] = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_STATE, 'utf8'));
  const record = (entry) => fs.appendFileSync(process.env.TEST_AUDIT, JSON.stringify({ tool, args, ...entry }) + '\n');
  const reject = (message, code = 91) => { process.stderr.write(message + '\n'); process.exit(code); };
  const local = (value) => {
    if (value.startsWith(process.env.TEST_UNIX_ROOT + '/')) {
      return path.join(process.env.TEST_LOCAL_ROOT, value.slice(process.env.TEST_UNIX_ROOT.length + 1));
    }
    const absolute = path.resolve(value);
    if (absolute.startsWith(process.env.TEST_LOCAL_ROOT + path.sep)) return absolute;
    return reject('Mock file access escaped its owned fixture: ' + value);
  };
  const parseManifest = (text) => Object.fromEntries(text.trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const equal = line.indexOf('='); return [line.slice(0, equal), line.slice(equal + 1)];
  }));
  const imageServices = { BACKEND_IMAGE: 'backend', COCKPIT_IMAGE: 'cockpit', X_CONSOLE_IMAGE: 'x-console', EGRESS_IMAGE: 'egress' };
  const configPath = process.env.DOCKER_CONFIG || '';
  const buildxConfig = process.env.BUILDX_CONFIG ? local(process.env.BUILDX_CONFIG) : undefined;
  const selectedBuilder = process.env.BUILDX_BUILDER;
  if (tool === 'git') {
    record({});
    if (!args.includes('rev-parse') || args.at(-1) !== 'HEAD') reject('Only the checked-out HEAD may select a build commit');
    process.stdout.write(state.sha + '\n');
    return;
  }
  if (tool === 'docker') {
    record({ dockerConfig: configPath, buildxConfig, builder: selectedBuilder });
    if (args[0] === 'login') {
      if (args[1] !== 'ccr.ccs.tencentyun.com' || !args.includes('--password-stdin') || args.includes('--password') || args.includes('-p')) {
        reject('Registry password must use stdin and the fixed TCR host');
      }
      if (args[args.indexOf('--username') + 1] !== state.username) reject('Unexpected registry user');
      if (fs.readFileSync(0, 'utf8').replace(/\r?\n$/, '') !== state.password) reject('Incorrect registry password transport');
      if (!configPath || configPath === process.env.TEST_ORIGINAL_DOCKER_CONFIG) reject('Registry credentials need a temporary config');
      fs.mkdirSync(local(configPath), { recursive: true });
      fs.writeFileSync(path.join(local(configPath), 'config.json'), '{"auths":{"synthetic":"temporary-auth"}}');
      if (state.fail === 'login') reject('synthetic login failure', 31);
      process.stdout.write('Login Succeeded\n');
      return;
    }
    if (args[0] === 'buildx' && args[1] === 'create') {
      if (state.builderMode === 'platform') reject('Platform builds must not create nested BuildKit containers');
      if (!args.includes('--name') || args[args.indexOf('--driver') + 1] !== 'docker-container' || !args.includes('--use')) {
        reject('Builds need their own named BuildKit builder');
      }
      process.stdout.write('synthetic-builder\n');
      return;
    }
    if (args[0] === 'buildx' && args[1] === 'inspect') {
      if (state.builderMode !== 'platform') reject('Only platform mode may query the existing builder');
      if (!buildxConfig || fs.readFileSync(path.join(buildxConfig, 'current'), 'utf8') !== state.originalBuildxCurrent) {
        reject('Platform mode lost the original builder selection metadata');
      }
      if (JSON.stringify(args) === JSON.stringify(['buildx', 'inspect'])) {
        if (configPath !== process.env.TEST_ORIGINAL_DOCKER_CONFIG) reject('Select the platform builder before changing Docker auth/context');
        process.stdout.write('Name: ' + (selectedBuilder || 'fixture-platform-builder') + '\nDriver: remote\n');
        return;
      }
      if (JSON.stringify(args) !== JSON.stringify(['buildx', 'inspect', '--bootstrap'])) {
        reject('Platform mode may only select then bootstrap the existing builder');
      }
      if (configPath === process.env.TEST_ORIGINAL_DOCKER_CONFIG) reject('Bootstrap must use temporary registry authentication');
      if (selectedBuilder !== 'fixture-platform-builder') reject('Bootstrap lost the frozen platform builder name');
      if (fs.existsSync(path.join(local(configPath), 'buildx'))) reject('Platform metadata must not be copied to temporary auth');
      if (state.fail === 'bootstrap') return reject('synthetic platform bootstrap failure', 31);
      process.stdout.write('Name: fixture-platform-builder\nDriver: remote\n');
      return;
    }
    if (args[0] === 'buildx' && args[1] === 'rm' && args[2] === '--force') {
      if (state.builderMode === 'platform') reject('Platform-owned builders must never be removed');
      return;
    }
    return reject('Unexpected Docker operation');
  }
  if (tool === 'build_images') {
    const options = {}, targets = [];
    for (let index = 0; index < args.length; index++) {
      if (args[index] === '--push') options.push = true;
      else if (args[index].startsWith('--')) options[args[index].slice(2)] = args[++index];
      else targets.push(args[index]);
    }
    const previous = options.base ? parseManifest(fs.readFileSync(local(options.base), 'utf8')) : {};
    record({ options, targets, previous, dockerConfig: configPath, builder: selectedBuilder, buildxConfig });
    if (options.registry !== state.registry || options.platform !== 'linux/amd64' || !options.push || !options['cache-dir']) {
      reject('The established build-images entry point must receive platform, persistent cache and push');
    }
    if (state.fail === 'build_images') {
      fs.writeFileSync(local(options.output), 'PARTIAL=not-a-success\n');
      return reject('synthetic build or push failure', 31);
    }
    const values = { ...previous, RELEASE_VERSION: options.tag };
    for (const [key, service] of Object.entries(imageServices)) {
      if (targets.includes(service)) values[key] = options.registry + '/' + service + ':' + options.tag;
      if (!values[key]) reject('A partial build lost an unselected image');
    }
    const manifest = ['RELEASE_VERSION=' + values.RELEASE_VERSION,
      ...Object.keys(imageServices).map((key) => key + '=' + values[key]), ''].join('\n');
    fs.writeFileSync(local(options.output), manifest);
    return;
  }
  if (tool === 'ssh' || tool === 'scp') {
    const config = args[args.indexOf('-F') + 1];
    if (!args.includes('-F')) reject('SSH configuration must be explicit');
    const text = fs.readFileSync(local(config), 'utf8');
    if (!/StrictHostKeyChecking yes/.test(text) || !/IdentitiesOnly yes/.test(text) || !/IdentityAgent none/.test(text)) {
      reject('SSH must pin host identities and avoid ambient agents');
    }
    if (text.includes(state.password) || text.includes(state.privateKey) || text.includes(state.hostKey)) reject('Secret material must not be embedded in SSH config');
    if (tool === 'scp') {
      const files = args.slice(2), download = files[0]?.endsWith('/deploy/releases/current.env');
      record({ sshConfig: config, transfer: download ? 'baseline' : 'upload' });
      if (download) {
        if (state.fail === 'baseline') return reject('synthetic baseline download failure', 31);
        fs.writeFileSync(local(files.at(-1)), state.baseline);
      } else {
        for (const file of files.slice(0, -1)) {
          if (!fs.statSync(local(file)).isFile()) reject('Missing staged deployment file');
        }
      }
      return;
    }
    const input = fs.readFileSync(0, 'utf8');
    const command = args.at(-1);
    if (command.startsWith('bash -c ')) {
      // Decode only the outer command's quoted arguments; the remote body never runs.
      const decoder = String.raw`bash() { printf '%s\0' "$@"; }
eval "$1"`;
      const decoded = cp.execFileSync(process.env.TEST_BASH_PATH, ['-c', decoder, '--', command], {
        encoding: 'utf8', timeout: 3000, env: process.env,
      }).split('\0').slice(0, -1);
      if (decoded[0] !== '-c' || decoded[2] !== '--') reject('Remote deployment must preserve quoted positional arguments');
      const rollback = decoded.slice(3)[7] === 'rollback';
      if (input !== (rollback ? '' : state.username + '\n' + state.password + '\n')) reject('Unexpected remote credential transport');
      record({ sshConfig: config, remoteScript: decoded[1], remoteArgs: decoded.slice(3), authenticatedStdin: !rollback });
      if (state.fail === 'remote_deploy') return reject('synthetic remote deployment failure', 37);
      process.stdout.write('DEPLOYMENT_OK\n');
    } else {
      record({ sshConfig: config, remoteScript: null });
      if (input) reject('Unexpected credentials on a prerequisite operation');
    }
    return;
  }
  reject('Unexpected mocked executable');
}

put(path.join(root, 'mock.cjs'), '(' + mockTool.toString() + ')();\n');
for (const tool of ['git', 'docker', 'ssh', 'scp']) {
  put(path.join(root, 'bin', tool), '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" MSYS2_ENV_CONV_EXCL="TEST_UNIX_ROOT" exec "$TEST_NODE" -- "$TEST_MOCK" ' + tool + ' "$@"\n');
}
let sequence = 0;
function run(options = {}) {
  const name = 'case-' + (++sequence);
  const local = (relative) => path.join(root, name, relative);
  const unix = (relative) => unixRoot + '/' + name + '/' + relative;
  put(local('repo/deploy/scripts/managed-ci.sh'), ciSource);
  put(local('repo/deploy/scripts/build-images.sh'), '#!/usr/bin/env bash\nMSYS2_ARG_CONV_EXCL="*" MSYS2_ENV_CONV_EXCL="TEST_UNIX_ROOT" exec "$TEST_NODE" -- "$TEST_MOCK" build_images "$@"\n');
  for (const helper of ['release.sh', 'cleanup-images.sh']) {
    put(local('repo/deploy/scripts/' + helper), '#!/usr/bin/env bash\nprintf "fixture helper must execute only on the mocked remote\\n" >&2\nexit 92\n');
  }
  put(local('ssh/key'), privateKey + '\n');
  put(local('ssh/known-hosts'), hostKey + '\n');
  put(local('original-docker/config.json'), originalDockerConfig);
  for (const directory of ['original-docker/buildx', 'platform-buildx']) {
    put(local(directory + '/current'), originalBuildxCurrent);
    put(local(directory + '/instances/fixture-platform-builder'), originalBuildxInstance);
  }
  put(local('cache/preserve-cache'), 'persistent cache must survive request cleanup\n');
  put(local('scratch/.keep'), '');
  put(local('audit.jsonl'), '');
  const output = options.defaultOutput ? 'repo/.ci-output' : 'artifacts';
  put(local(output + '/release.env'), 'STALE=must-not-be-archived\n');
  put(local(output + '/preserve-other-artifact'), 'keep\n');
  put(local('state.json'), JSON.stringify({ sha: actualSha, username, password, privateKey, hostKey, registry,
    baseline: options.baseline || serialize(baseline()), fail: options.fail,
    builderMode: options.builderMode || 'isolated', originalBuildxCurrent }));
  const env = {
    ...process.env, PATH: process.env.PATH,
    TEST_NODE: nodePath, TEST_MOCK: path.join(root, 'mock.cjs'), TEST_BIN: unixRoot + '/bin',
    TEST_STATE: local('state.json'), TEST_AUDIT: local('audit.jsonl'), TEST_BASH_PATH: bash,
    TEST_UNIX_ROOT: unixRoot, TEST_LOCAL_ROOT: root, TEST_ORIGINAL_DOCKER_CONFIG: unix('original-docker'),
    CI_RUN_ID: '17', BUILD_NUMBER: '123', TCR_USERNAME: username, TCR_PASSWORD: password,
    SERVICE: options.service || 'backend', BUILD_REQUESTED: options.build === false ? 'false' : 'true',
    DEPLOY_REQUESTED: options.deploy ? 'true' : 'false', ACCESS_MODE: options.access || 'private',
    DEPLOY_ROOT: '/opt/trade', DEPLOY_HOST: options.noSSH ? '' : 'production.example', DEPLOY_USER: options.noSSH ? '' : 'deploy',
    DEPLOY_PORT: '2222', DEPLOY_SSH_KEY_FILE: options.noSSH ? '' : unix('ssh/key'),
    DEPLOY_KNOWN_HOSTS_FILE: options.noSSH ? '' : unix('ssh/known-hosts'),
    BUILD_CACHE_DIR: unix('cache'), DOCKER_CONFIG: unix('original-docker'), TMPDIR: unix('scratch'),
    EXISTING_RELEASE_MANIFEST: options.manifest ?? serialize(existing()),
    IMAGE_REGISTRY: registry, GITHUB_ACTIONS: 'false', GIT_COMMIT: 'c'.repeat(40), SOURCE_SHA: 'd'.repeat(40),
    ...options.env,
  };
  if (!Object.hasOwn(options.env || {}, 'CI_BUILDER_MODE')) {
    if (options.builderMode) env.CI_BUILDER_MODE = options.builderMode;
    else delete env.CI_BUILDER_MODE;
  }
  delete env.BUILDX_CONFIG;
  delete env.BUILDX_BUILDER;
  if (options.explicitBuildx) env.BUILDX_CONFIG = unix('platform-buildx');
  if (options.platformBuilder) env.BUILDX_BUILDER = options.platformBuilder;
  if (!options.defaultOutput) env.OUTPUT_DIR = unix(output);
  else delete env.OUTPUT_DIR;
  if (options.defaults) { delete env.BUILD_REQUESTED; delete env.DEPLOY_REQUESTED; }
  const result = cp.spawnSync(bash, ['-c',
    'export PATH="$TEST_BIN:/usr/bin:/bin"\nexec bash deploy/scripts/managed-ci.sh',
  ], { cwd: local('repo'), env, encoding: 'utf8', timeout: 25_000 });
  if (result.error) throw result.error;
  const calls = fs.readFileSync(local('audit.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  const diagnostic = result.stdout + result.stderr;
  for (const secret of [password, privateKey, hostKey]) {
    assert.ok(!diagnostic.includes(secret), 'Secret material must not appear in CI logs');
    assert.ok(!JSON.stringify(calls).includes(secret), 'Secret material must not appear in process arguments or audit metadata');
  }
  assert.ok(!diagnostic.includes(username), 'Registry user must not be printed to CI logs');
  assert.deepEqual(fs.readdirSync(local('scratch')), ['.keep'], 'Temporary registry and SSH data must be removed on every exit');
  assert.equal(fs.readFileSync(local('original-docker/config.json'), 'utf8'), originalDockerConfig);
  for (const directory of ['original-docker/buildx', 'platform-buildx']) {
    assert.equal(fs.readFileSync(local(directory + '/current'), 'utf8'), originalBuildxCurrent);
    assert.equal(fs.readFileSync(local(directory + '/instances/fixture-platform-builder'), 'utf8'), originalBuildxInstance);
  }
  assert.equal(fs.readFileSync(local('ssh/key'), 'utf8'), privateKey + '\n');
  assert.equal(fs.readFileSync(local('ssh/known-hosts'), 'utf8'), hostKey + '\n');
  assert.equal(fs.readFileSync(local('cache/preserve-cache'), 'utf8'), 'persistent cache must survive request cleanup\n');
  assert.equal(fs.readFileSync(local(output + '/preserve-other-artifact'), 'utf8'), 'keep\n');
  const artifactPath = local(output + '/release.env');
  const artifact = fs.existsSync(artifactPath) ? parse(fs.readFileSync(artifactPath, 'utf8')) : undefined;
  return { ...result, calls, diagnostic, artifact, local, unix };
}

test('partial builds freeze actual HEAD, download the production baseline, and preserve every unselected image', () => {
  const result = run({ defaultOutput: true });
  assert.equal(result.status, 0, result.diagnostic);
  const build = result.calls.find((call) => call.tool === 'build_images');
  assert.deepEqual(build.targets, ['backend']);
  assert.equal(build.options['cache-dir'], result.unix('cache'));
  assert.deepEqual(build.previous, baseline());
  assert.match(build.options.tag, new RegExp('^sha-' + actualSha + '-cn-17-[a-f0-9]{12}$'));
  assert.equal(result.artifact.RELEASE_VERSION, build.options.tag);
  assert.equal(result.artifact.BACKEND_IMAGE, registry + '/backend:' + build.options.tag);
  for (const key of ['COCKPIT_IMAGE', 'X_CONSOLE_IMAGE', 'EGRESS_IMAGE']) assert.equal(result.artifact[key], baseline()[key]);
  assert.equal(result.calls.filter((call) => call.tool === 'scp' && call.transfer === 'baseline').length, 1);
  assert.ok(!result.calls.some((call) => call.tool === 'ssh'));
  const builder = result.calls.find((call) => call.tool === 'docker' && call.args[1] === 'create');
  const builderName = builder.args[builder.args.indexOf('--name') + 1];
  assert.equal(build.builder, builderName);
  assert.deepEqual(result.calls.filter((call) => call.tool === 'docker').at(-1).args, ['buildx', 'rm', '--force', builderName]);
});

test('frontends expands to both web applications while retaining backend and egress references', () => {
  const result = run({ service: 'frontends' });
  assert.equal(result.status, 0, result.diagnostic);
  const build = result.calls.find((call) => call.tool === 'build_images');
  assert.deepEqual(build.targets, ['cockpit', 'x-console']);
  assert.equal(result.artifact.BACKEND_IMAGE, baseline().BACKEND_IMAGE);
  assert.equal(result.artifact.EGRESS_IMAGE, baseline().EGRESS_IMAGE);
  assert.equal(result.artifact.COCKPIT_IMAGE, registry + '/cockpit:' + result.artifact.RELEASE_VERSION);
  assert.equal(result.artifact.X_CONSOLE_IMAGE, registry + '/x-console:' + result.artifact.RELEASE_VERSION);
});

test('all build-only works with default flags and no production SSH credentials', () => {
  const result = run({ service: 'all', noSSH: true, defaults: true });
  assert.equal(result.status, 0, result.diagnostic);
  const build = result.calls.find((call) => call.tool === 'build_images');
  assert.deepEqual(build.targets, Object.values(services));
  assert.equal(build.options.base, undefined);
  assert.ok(!result.calls.some((call) => ['ssh', 'scp'].includes(call.tool)));
  for (const [key, service] of Object.entries(services)) assert.equal(result.artifact[key], registry + '/' + service + ':' + result.artifact.RELEASE_VERSION);
});

test('platform mode preserves default builder metadata while separating temporary registry authentication', () => {
  const result = run({ service: 'all', noSSH: true, builderMode: 'platform' });
  assert.equal(result.status, 0, result.diagnostic);
  const docker = result.calls.filter((call) => call.tool === 'docker');
  assert.deepEqual(docker.map((call) => call.args.slice(0, 2)), [
    ['buildx', 'inspect'], ['login', 'ccr.ccs.tencentyun.com'], ['buildx', 'inspect'],
  ]);
  const selection = docker[0];
  assert.deepEqual(selection.args, ['buildx', 'inspect']);
  assert.equal(path.normalize(selection.dockerConfig), path.normalize(result.local('original-docker')));
  assert.equal(selection.builder, undefined, 'the original implicit selection must be queried in its own context');
  const inspect = docker.at(-1);
  const build = result.calls.find((call) => call.tool === 'build_images');
  assert.equal(inspect.buildxConfig, result.local('original-docker/buildx'));
  assert.equal(build.buildxConfig, result.local('original-docker/buildx'));
  assert.equal(inspect.builder, 'fixture-platform-builder', 'bootstrap must retain the exact selected name after auth changes');
  assert.equal(build.builder, 'fixture-platform-builder');
  assert.notEqual(path.normalize(build.dockerConfig), path.normalize(result.local('original-docker')));
  assert.equal(build.dockerConfig, inspect.dockerConfig);
  assert.ok(result.artifact);
});

test('platform mode keeps an explicit BUILDX_CONFIG and BUILDX_BUILDER without creating or removing builders', () => {
  const result = run({ service: 'all', noSSH: true, builderMode: 'platform', explicitBuildx: true,
    platformBuilder: 'fixture-platform-builder' });
  assert.equal(result.status, 0, result.diagnostic);
  for (const call of result.calls.filter((entry) => entry.tool === 'docker' || entry.tool === 'build_images')) {
    assert.equal(call.buildxConfig, result.local('platform-buildx'));
    assert.equal(call.builder, 'fixture-platform-builder');
  }
  const selection = result.calls.find((call) => call.tool === 'docker' && call.args[1] === 'inspect' && call.args.length === 2);
  assert.equal(path.normalize(selection.dockerConfig), path.normalize(result.local('original-docker')));
  assert.ok(!result.calls.some((call) => call.tool === 'docker' && ['create', 'use', 'rm'].includes(call.args[1])));
});

test('platform bootstrap and build failures preserve shared metadata, remove temporary auth and never create or remove a builder', () => {
  for (const fail of ['bootstrap', 'build_images']) {
    const result = run({ service: 'all', noSSH: true, builderMode: 'platform', explicitBuildx: true,
      platformBuilder: 'fixture-platform-builder', fail });
    assert.equal(result.status, 31, result.diagnostic);
    assert.equal(result.artifact, undefined);
    assert.ok(!result.calls.some((call) => call.tool === 'docker' && ['create', 'use', 'rm'].includes(call.args[1])));
    const selection = result.calls.find((call) => call.tool === 'docker' && call.args[1] === 'inspect' && call.args.length === 2);
    const bootstrap = result.calls.find((call) => call.tool === 'docker' && call.args.includes('--bootstrap'));
    assert.equal(path.normalize(selection.dockerConfig), path.normalize(result.local('original-docker')));
    assert.notEqual(path.normalize(bootstrap.dockerConfig), path.normalize(result.local('original-docker')));
    assert.equal(bootstrap.builder, 'fixture-platform-builder');
    assert.equal(result.calls.some((call) => call.tool === 'build_images'), fail === 'build_images');
  }
});

test('failed registry login or build/push cannot deploy or leave a stale success manifest', () => {
  for (const fail of ['login', 'build_images', 'baseline']) {
    const result = run({ deploy: true, fail });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.equal(result.artifact, undefined);
    assert.ok(!result.calls.some((call) => call.remoteScript));
    assert.ok(!result.calls.some((call) => call.tool === 'scp' && call.transfer === 'upload'));
  }
});

test('deployment stages this checkout helpers and invokes the incoming release with --root and credential stdin', () => {
  const result = run({ service: 'frontends', deploy: true, access: 'public' });
  assert.equal(result.status, 0, result.diagnostic);
  const upload = result.calls.find((call) => call.tool === 'scp' && call.transfer === 'upload');
  assert.ok(upload.args.some((arg) => arg.endsWith('/deploy/scripts/release.sh')));
  assert.ok(upload.args.some((arg) => arg.endsWith('/deploy/scripts/cleanup-images.sh')));
  assert.match(upload.args.at(-1), /^trade-deploy:\/opt\/trade\/deploy\/incoming\/.+\/$/);
  const deploy = result.calls.find((call) => call.remoteScript);
  assert.equal(deploy.authenticatedStdin, true);
  assert.deepEqual(deploy.remoteArgs.slice(0, 1), ['/opt/trade']);
  assert.deepEqual(deploy.remoteArgs.slice(2), ['public', 'frontends', 'ccr.ccs.tencentyun.com', registry, '300', 'release']);
  assert.match(deploy.remoteArgs[1], /^\/opt\/trade\/deploy\/incoming\/.+\/release\.env$/);
  assert.ok((deploy.remoteScript.match(/--root/g) || []).length >= 2);
  assert.match(deploy.remoteScript, /release_script=.*manifest/);
  assert.match(deploy.remoteScript, /"\$release_script" validate/);
  assert.match(deploy.remoteScript, /"\$release_script" deploy/);
});

test('deployment failure retains the newly built manifest for a deploy-only retry', () => {
  const result = run({ deploy: true, fail: 'remote_deploy' });
  assert.notEqual(result.status, 0, result.diagnostic);
  assert.match(result.artifact.RELEASE_VERSION, new RegExp('^sha-' + actualSha + '-cn-17-[a-f0-9]{12}$'));
  assert.equal(result.artifact.BACKEND_IMAGE, registry + '/backend:' + result.artifact.RELEASE_VERSION);
  assert.equal(result.calls.filter((call) => call.remoteScript).length, 1);
});

test('deploy-only accepts exact selected TCR images while preserving restricted historical unselected references', () => {
  const result = run({ build: false, deploy: true });
  assert.equal(result.status, 0, result.diagnostic);
  assert.deepEqual(result.artifact, existing());
  assert.ok(!result.calls.some((call) => ['build_images', 'docker', 'git'].includes(call.tool)));
  assert.ok(!result.calls.some((call) => call.transfer === 'baseline'));
  assert.equal(result.calls.filter((call) => call.remoteScript).length, 1);
});

test('deploy-only rejects foreign, inherited, incomplete, duplicate and executable manifest input before remote operations', () => {
  const valid = serialize(existing());
  const mutations = [
    valid.replace(registry + '/backend:' + historicalTag, 'foreign.example/team/backend:' + historicalTag),
    valid.replace(registry + '/backend:' + historicalTag, registry + '/backend:inherited-old-tag'),
    valid.replace(/^EGRESS_IMAGE=.*\n/m, ''),
    valid + 'BACKEND_IMAGE=' + registry + '/backend:' + historicalTag + '\n',
    valid.replace(/^RELEASE_VERSION=.*\n/m, ''),
    valid.replace('RELEASE_VERSION=' + historicalTag, 'RELEASE_VERSION=latest'),
    valid + 'UNEXPECTED=value\n',
    valid + 'MALICIOUS=$(echo should-never-run)\n',
    '',
  ];
  for (const manifest of mutations) {
    const result = run({ build: false, deploy: true, manifest });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.equal(result.artifact, undefined);
    assert.deepEqual(result.calls, []);
  }
  const all = run({ service: 'all', build: false, deploy: true, manifest: valid });
  assert.notEqual(all.status, 0, all.diagnostic);
  assert.deepEqual(all.calls, []);
});

test('rollback selects the server previous release without registry login, rebuilds or a supplied manifest', () => {
  for (const fail of [undefined, 'remote_deploy']) {
    const result = run({ service: 'frontends', fail, env: { CI_ACTION: 'rollback',
      EXISTING_RELEASE_MANIFEST: '', TCR_USERNAME: '', TCR_PASSWORD: '' } });
    assert.equal(result.status, fail ? 37 : 0, result.diagnostic);
    assert.equal(result.artifact, undefined);
    assert.ok(!result.calls.some((call) => ['docker', 'git', 'build_images'].includes(call.tool)));
    assert.ok(!result.calls.some((call) => call.transfer === 'baseline'));
    const rollback = result.calls.find((call) => call.remoteScript);
    assert.equal(rollback.remoteArgs[7], 'rollback');
    assert.equal(rollback.remoteArgs[3], 'frontends');
    assert.equal(rollback.authenticatedStdin, false);
    assert.match(rollback.remoteScript, /"\$release_script" rollback/);
    assert.match(rollback.remoteScript, /--no-pull/);
    assert.match(rollback.remoteScript, /ROLLBACK_OK/);
  }
});

test('invalid service, boolean, registry or run id cannot reuse old artifacts or contact external tools', () => {
  for (const env of [{ SERVICE: 'backend; echo injected' }, { BUILD_REQUESTED: 'yes' },
    { DEPLOY_REQUESTED: 'yes' }, { CI_ACTION: 'invalid' }, { CI_RUN_ID: '0' }, { CI_RUN_ID: '-1' },
    { IMAGE_REGISTRY: 'foreign.example/team' }, { CI_BUILDER_MODE: 'unsupported' }]) {
    const result = run({ service: 'all', noSSH: true, env });
    assert.notEqual(result.status, 0, result.diagnostic);
    assert.equal(result.artifact, undefined);
    assert.deepEqual(result.calls, []);
  }
});

after(() => {
  const resolved = fs.realpathSync(root);
  assert.equal(resolved, ownedRoot);
  assert.match(path.basename(resolved), /^trade-managed-tests\./);
  fs.rmSync(resolved, { recursive: true, force: true });
});
