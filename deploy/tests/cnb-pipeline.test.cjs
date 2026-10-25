// Execute the actual YAML bootstrap against local Git history; never contact a cloud service.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const { test, after } = require('node:test');

const win = process.platform === 'win32';
const bash = process.env.TEST_BASH || (win ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const toUnix = (file) => win
  ? cp.execFileSync(bash, ['-c', 'cygpath -u "$1"', '--', file], { encoding: 'utf8' }).trim()
  : file;
const realGit = cp.execFileSync(bash, ['-c', 'command -v git'], { encoding: 'utf8' }).trim();
const realTimeout = cp.execFileSync(bash, ['-c', 'command -v timeout'], { encoding: 'utf8' }).trim();
const yaml = fs.readFileSync(path.resolve(__dirname, '../ci/cnb.yml'), 'utf8').replace(/\r\n/g, '\n');
const scriptStart = yaml.indexOf('      script: |\n');
assert.ok(scriptStart >= 0, 'The bootstrap must be a literal script task');
const scriptLines = yaml.slice(scriptStart + '      script: |\n'.length).split('\n');
const bootstrapLines = [];
for (const line of scriptLines) {
  if (line && !line.startsWith('        ')) break;
  bootstrapLines.push(line.slice(8));
}
const bootstrap = bootstrapLines.join('\n');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-cnb-pipeline-'));
const ownedRoot = fs.realpathSync(root);
const source = path.join(root, 'origin');
const put = (file, contents) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, contents, { mode: 0o755 }); };
const globalConfig = path.join(root, 'empty-git-config');
put(globalConfig, '');
const gitEnv = { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: globalConfig,
  GIT_AUTHOR_NAME: 'CNB Test', GIT_AUTHOR_EMAIL: 'cnb-test@example.invalid',
  GIT_COMMITTER_NAME: 'CNB Test', GIT_COMMITTER_EMAIL: 'cnb-test@example.invalid' };
const git = (args) => cp.execFileSync('git', args, { env: gitEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
git(['init', '--initial-branch=main', source]);
git(['-C', source, 'config', 'core.autocrlf', 'false']);
put(path.join(source, 'deploy/scripts/cnb-ci.sh'), `#!/usr/bin/env bash
set +x
set -euo pipefail
source_root=$(cd -- "$(dirname -- "\${BASH_SOURCE[0]}")/../.." && pwd -P)
{
  printf '%s\\0%s\\0' ACTUAL_HEAD "$(git -C "$source_root" rev-parse HEAD)"
  printf '%s\\0%s\\0' MARKER "$(cat "$source_root/marker")"
  for key in SOURCE_SHA CI_RUN_ID SERVICE BUILD_REQUESTED DEPLOY_REQUESTED EXISTING_RELEASE_MANIFEST DEPLOY_ROOT ACCESS_MODE; do
    printf '%s\\0%s\\0' "$key" "\${!key-}"
  done
} > "$TEST_RESULT"
`);
put(path.join(source, 'marker'), 'first\n');
git(['-C', source, 'add', '.']);
git(['-C', source, 'commit', '-m', 'first']);
const first = git(['-C', source, 'rev-parse', 'HEAD']);
put(path.join(source, 'marker'), 'second\n');
git(['-C', source, 'commit', '-am', 'second']);
const second = git(['-C', source, 'rev-parse', 'HEAD']);
put(path.join(source, 'marker'), 'latest\n');
git(['-C', source, 'commit', '-am', 'latest']);
const latest = git(['-C', source, 'rev-parse', 'HEAD']);
git(['-C', source, 'checkout', '-b', 'untrusted-side', first]);
put(path.join(source, 'marker'), 'side\n');
git(['-C', source, 'commit', '-am', 'untrusted side branch']);
const side = git(['-C', source, 'rev-parse', 'HEAD']);
git(['-C', source, 'checkout', 'main']);
const bin = path.join(root, 'bin');
put(path.join(bin, 'git'), `#!/usr/bin/env bash
set -euo pipefail
printf '%q ' "$@" >> "$TEST_AUDIT"
printf '\\n' >> "$TEST_AUDIT"
args=("$@")
if [[ \${args[0]} == clone ]]; then
  replaced=false
  for index in "\${!args[@]}"; do
    if [[ \${args[$index]} == https://github.com/tiancia/trade.git ]]; then
      args[$index]=$TEST_ORIGIN
      replaced=true
    fi
  done
  [[ $replaced == true ]] || { printf 'Unexpected clone URL\\n' >&2; exit 91; }
fi
if [[ \${TEST_MOVE_CHECKOUT:-false} == true && " $* " == *' checkout --detach '* ]]; then
  args[\${#args[@]}-1]=$TEST_LATEST
fi
exec "$TEST_REAL_GIT" "\${args[@]}"
`);
put(path.join(bin, 'timeout'), `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$1" >> "$TEST_TIMEOUT_AUDIT"
exec "$TEST_REAL_TIMEOUT" "$@"
`);
let sequence = 0;
function run(changes = {}, before) {
  const directory = path.join(root, 'run-' + (++sequence));
  fs.mkdirSync(directory);
  put(path.join(directory, 'bootstrap.sh'), bootstrap);
  put(path.join(directory, 'git-audit'), '');
  put(path.join(directory, 'timeout-audit'), '');
  if (before) before(directory);
  const secret = 'synthetic-registry-password-never-logged';
  const env = { ...gitEnv, TEST_BIN: toUnix(bin), TEST_SCRIPT: toUnix(path.join(directory, 'bootstrap.sh')),
    TEST_ORIGIN: toUnix(source), TEST_REAL_GIT: realGit, TEST_REAL_TIMEOUT: realTimeout,
    TEST_AUDIT: toUnix(path.join(directory, 'git-audit')), TEST_TIMEOUT_AUDIT: toUnix(path.join(directory, 'timeout-audit')),
    TEST_RESULT: toUnix(path.join(directory, 'adapter-result')), TEST_LATEST: latest,
    SOURCE_SHA: '', CI_RUN_ID: '4567', SERVICE: 'cockpit', BUILD_REQUESTED: 'true', DEPLOY_REQUESTED: 'false',
    EXISTING_RELEASE_MANIFEST: '', DEPLOY_ROOT: '/opt/trade', ACCESS_MODE: 'private',
    CNB_COMMIT: 'c'.repeat(40), TCR_PASSWORD: secret, ...changes };
  const execution = cp.spawnSync(bash, ['-c', 'export PATH="$TEST_BIN:$PATH"\nexec sh "$TEST_SCRIPT"'],
    { cwd: directory, env, encoding: 'utf8', timeout: 20000 });
  if (execution.error) throw execution.error;
  const text = execution.stdout + execution.stderr;
  assert.ok(!text.includes(secret), 'Credentials must not be printed by the source bootstrap');
  const resultFile = path.join(directory, 'adapter-result');
  let received;
  if (fs.existsSync(resultFile)) {
    const values = fs.readFileSync(resultFile, 'utf8').split('\0').slice(0, -1);
    received = Object.fromEntries(Array.from({ length: values.length / 2 }, (_, index) => values.slice(index * 2, index * 2 + 2)));
  }
  return { ...execution, directory, received, diagnostic: text,
    audit: fs.readFileSync(path.join(directory, 'git-audit'), 'utf8'),
    timeouts: fs.readFileSync(path.join(directory, 'timeout-audit'), 'utf8').trim().split('\n').filter(Boolean) };
}

test('manual source selection uses fetched GitHub main, independently of the CNB control commit', () => {
  const result = run();
  assert.equal(result.status, 0, result.diagnostic);
  assert.equal(result.received.SOURCE_SHA, latest);
  assert.equal(result.received.ACTUAL_HEAD, latest);
  assert.equal(result.received.MARKER, 'latest');
  assert.ok(result.audit.includes('https://github.com/tiancia/trade.git'));
  assert.match(result.audit, /fetch --no-tags origin/);
  assert.match(result.audit, /merge-base --is-ancestor/);
  assert.ok(result.timeouts.every((value) => /^[1-9][0-9]*$/.test(value) && Number(value) <= 300));
  assert.ok(result.timeouts.length >= 7);
});

test('API source selection freezes an exact trusted ancestor and preserves caller parameters', () => {
  for (const [sha, marker] of [[first, 'first'], [second, 'second']]) {
    const manifest = 'RELEASE_VERSION=history\nBACKEND_IMAGE=registry/backend:history\n';
    const result = run({ SOURCE_SHA: sha, CI_RUN_ID: '9876', SERVICE: 'backend', BUILD_REQUESTED: 'false',
      DEPLOY_REQUESTED: 'true', EXISTING_RELEASE_MANIFEST: manifest });
    assert.equal(result.status, 0, result.diagnostic);
    assert.deepEqual(result.received, { ACTUAL_HEAD: sha, MARKER: marker, SOURCE_SHA: sha, CI_RUN_ID: '9876',
      SERVICE: 'backend', BUILD_REQUESTED: 'false', DEPLOY_REQUESTED: 'true', EXISTING_RELEASE_MANIFEST: manifest,
      DEPLOY_ROOT: '/opt/trade', ACCESS_MODE: 'private' });
  }
});

test('malformed source selectors fail before cloning and cannot execute shell text', () => {
  for (const selector of ['main', '-bad', 'a'.repeat(39), 'A'.repeat(40), '$(touch injected)', latest + '\necho injected']) {
    const result = run({ SOURCE_SHA: selector });
    assert.notEqual(result.status, 0);
    assert.equal(result.received, undefined);
    assert.equal(result.audit, '');
    assert.equal(fs.existsSync(path.join(result.directory, 'injected')), false);
  }
});

test('a commit outside main and an absent commit never execute the source adapter', () => {
  for (const selector of [side, 'f'.repeat(40)]) {
    const result = run({ SOURCE_SHA: selector });
    assert.notEqual(result.status, 0);
    assert.equal(result.received, undefined);
    assert.ok(!result.audit.includes('checkout --detach'));
  }
});

test('an unexpected checkout result is rejected before the adapter runs', () => {
  const result = run({ SOURCE_SHA: first, TEST_MOVE_CHECKOUT: 'true' });
  assert.notEqual(result.status, 0);
  assert.equal(result.received, undefined);
  assert.match(result.diagnostic, /checked-out commit differs/);
});

test('existing source files and directories are preserved and prevent all Git operations', () => {
  for (const kind of ['file', 'directory']) {
    const result = run({}, (directory) => {
      const destination = path.join(directory, 'trade');
      if (kind === 'file') put(destination, 'preserve me');
      else put(path.join(destination, 'preserve'), 'preserve me');
    });
    assert.notEqual(result.status, 0);
    assert.equal(result.received, undefined);
    assert.equal(result.audit, '');
    assert.equal(fs.readFileSync(path.join(result.directory, 'trade', ...(kind === 'file' ? [] : ['preserve'])), 'utf8'), 'preserve me');
  }
});

test('only API/manual events use product CI; control pushes are inert and API selections remain intact', () => {
  assert.match(yaml, /^\.trade-pipeline: &trade-pipeline$/m);
  for (const event of ['api_trigger_trade', 'web_trigger']) {
    assert.match(yaml, new RegExp('^  ' + event + ':\\n    - <<: \\*trade-pipeline$', 'm'));
  }
  assert.match(yaml, /tags: cnb:arch:amd64\n    cpus: 4/);
  assert.match(yaml, /main:\/root\/\.cache\/trade-build-cache:copy-on-write/);
  assert.ok(yaml.includes('https://cnb.cool/tiancia/trade-ci-secrets/-/blob/main/env.yml'));
  assert.doesNotMatch(yaml, /^\s*(SOURCE_SHA|CI_RUN_ID|SERVICE|BUILD_REQUESTED|DEPLOY_REQUESTED|EXISTING_RELEASE_MANIFEST):/m);
  const push = yaml.slice(yaml.indexOf('  push:\n'), yaml.indexOf('  api_trigger_trade:\n'));
  assert.ok(push.includes("printf '%s\\n' 'CNB configuration ready; start product builds from GitHub or web_trigger'"));
  assert.doesNotMatch(push, /imports|services|docker|trade-pipeline|cnb-ci\.sh|managed-ci\.sh|git clone|DEPLOY_REQUESTED/);
  const retention = yaml.slice(yaml.indexOf('  endStages:'));
  assert.ok(retention.includes('commit: ${CNB_COMMIT}'));
  assert.ok(retention.includes('./trade/.ci-output/release-*.env'));
  assert.match(retention, /ttl: 30/);
});

after(() => {
  const resolved = fs.realpathSync(root);
  assert.equal(resolved, ownedRoot);
  assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
  assert.match(path.basename(resolved), /^trade-cnb-pipeline-/);
  fs.rmSync(resolved, { recursive: true, force: true });
});
