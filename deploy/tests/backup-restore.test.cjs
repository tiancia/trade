// Offline regression: every Docker, age, SSH/SCP and heartbeat action is mocked.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { gzipSync } = require('node:zlib');
const { test, after } = require('node:test');
const win = process.platform === 'win32';
const bash = process.env.TEST_BASH || (win ? 'C:/git/Git/bin/bash.exe' : '/bin/bash');
const bashPath = (file) => win ? cp.execFileSync(bash, ['-c', 'cygpath -u "$1"', '--', file], { encoding: 'utf8' }).trim() : file;
let root, unixRoot;
if (win) {
  [unixRoot, root] = cp.execFileSync(bash, ['-c', 'r=$(mktemp -d /tmp/trade-backup-tests.XXXXXX)\nprintf "%s\\n" "$r"\ncygpath -w "$r"'], { encoding: 'utf8' }).trim().split(/\r?\n/);
} else { root = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-backup-tests.')); unixRoot = root; }
const ownedRoot = fs.realpathSync(root);
const put = (file, value) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, value, { mode: 0o755 }); };
const nodePath = bashPath(process.execPath);
function mockTool() {
  const fs = require('node:fs');
  const [tool, ...args] = process.argv.slice(2);
  const state = JSON.parse(fs.readFileSync(process.env.TEST_STATE, 'utf8'));
  fs.appendFileSync(process.env.TEST_AUDIT, JSON.stringify({ tool, args }) + '\n');
  const reject = () => { process.stderr.write('simulated ' + tool + ' failure\n'); process.exit(23); };
  if (state.fail === tool) reject();
  if (tool === 'flock') process.exit(state.busy ? 1 : 0);
  if (tool === 'age') {
    if (args.includes('--decrypt')) process.stdout.write(fs.readFileSync(args.at(-1)));
    else { process.stdin.on('data', (data) => process.stdout.write(data)); }
  } else if (tool === 'ssh') {
    if (state.failRemoteVerify && args.at(-1).includes('sha256sum')) reject();
  } else if (tool === 'scp' || tool === 'curl') {
    // Intentionally no real network.
  } else if (tool === 'amtool' || tool === 'alertmanager') {
    // Configuration validation/startup boundary only; no notification is sent.
  } else if (tool === 'docker') {
    if (args[0] === 'compose') {
      const command = args.at(-1);
      if (command.includes('COUNT(*)')) process.stdout.write(state.nonInnoDB ? '1\n' : '0\n');
      else if (command.includes('mysqldump')) {
        if (state.failDump) reject();
        process.stdout.write('CREATE TABLE proof (id BIGINT PRIMARY KEY);\nINSERT INTO proof VALUES (1);\n');
      } else reject();
    } else if (args[0] === 'image') {
      if (state.missingImage) reject();
    } else if (args[0] === 'create') process.stdout.write('a'.repeat(64) + '\n');
    else if (args[0] === 'start' || args[0] === 'rm') { /* isolated fixture */ }
    else if (args[0] === 'exec') {
      if (args.includes('-i')) {
        process.stdin.on('data', (data) => fs.appendFileSync(process.env.TEST_IMPORTED, data));
        process.stdin.on('end', () => { if (state.failImport) reject(); });
      } else if (args.at(-1).includes('COUNT(*)')) process.stdout.write(state.emptyRestore ? '0\n' : '1\n');
      else if (args.at(-1).includes('mysqlcheck') && state.failCheck) reject();
    } else reject();
  } else reject();
}
put(path.join(root, 'mock.cjs'), '(' + mockTool.toString() + ')();\n');
for (const tool of ['docker', 'age', 'ssh', 'scp', 'flock', 'curl', 'amtool', 'alertmanager']) {
  put(path.join(root, 'bin', tool), '#!/bin/bash\nexec "$TEST_NODE" "$TEST_MOCK" ' + tool + ' "$@"\n');
}
let sequence = 0;
function run(kind = 'backup', state = {}, extra = []) {
  const name = 'case-' + (++sequence), fixture = path.join(root, name), unixFixture = unixRoot + '/' + name;
  for (const file of ['deploy/.env', 'deploy/releases/current.env', 'recipients', 'identity', 'known-hosts', 'heartbeat']) put(path.join(fixture, file), 'fixture\n');
  put(path.join(fixture, 'state.json'), JSON.stringify(state));
  put(path.join(fixture, 'audit.jsonl'), '');
  put(path.join(fixture, 'imported.sql'), '');
  const backupName = 'mysql-20261024T000000Z-1.sql.gz.age';
  const archive = gzipSync('CREATE TABLE proof (id BIGINT PRIMARY KEY);\nINSERT INTO proof VALUES (1);\n');
  put(path.join(fixture, backupName), archive);
  const checksum = state.badChecksum ? '0'.repeat(64) : createHash('sha256').update(archive).digest('hex');
  put(path.join(fixture, backupName + '.sha256'), checksum + '  ' + backupName + '\n');
  const script = bashPath(path.resolve(__dirname, '../scripts/' + (kind === 'backup' ? 'backup-mysql.sh' : 'restore-drill.sh')));
  const args = kind === 'backup'
    ? ['--root', unixFixture, '--recipients', unixFixture + '/recipients', '--destination', 'backup@offsite.test:/srv/trade-backups', '--identity', unixFixture + '/identity', '--known-hosts', unixFixture + '/known-hosts', '--heartbeat-config', unixFixture + '/heartbeat']
    : ['--backup', unixFixture + '/' + backupName, '--identity', unixFixture + '/identity'];
  const result = cp.spawnSync(bash, ['-c', 'export PATH="$TEST_BIN:/usr/bin:/bin"\nexec bash "$TEST_SCRIPT" "$@"', '--', ...args, ...extra], {
    encoding: 'utf8', timeout: 20_000,
    env: { ...process.env, TEST_BIN: unixRoot + '/bin', TEST_NODE: nodePath, TEST_MOCK: unixRoot + '/mock.cjs', TEST_STATE: path.join(fixture, 'state.json'), TEST_AUDIT: path.join(fixture, 'audit.jsonl'), TEST_IMPORTED: path.join(fixture, 'imported.sql'), TEST_SCRIPT: script },
  });
  if (result.error) throw result.error;
  const audit = fs.readFileSync(path.join(fixture, 'audit.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  return { ...result, audit, fixture, diagnostic: result.stdout + result.stderr };
}
test('backup success requires encrypted stream, strict SSH and remote hash verification before heartbeat', () => {
  const r = run(); assert.equal(r.status, 0, r.diagnostic); assert.match(r.stdout, /BACKUP_OFFSITE_OK/);
  const tools = r.audit.map((x) => x.tool);
  assert.ok(tools.includes('age') && tools.includes('scp'));
  const verify = r.audit.findIndex((x) => x.tool === 'ssh' && x.args.at(-1).includes('sha256sum'));
  assert.ok(verify >= 0 && r.audit.findIndex((x) => x.tool === 'curl') > verify);
  for (const x of r.audit.filter((x) => ['ssh', 'scp'].includes(x.tool))) assert.ok(x.args.includes('StrictHostKeyChecking=yes'));
  const dumps = r.audit.filter((x) => x.tool === 'docker').map((x) => x.args.at(-1));
  assert.ok(dumps.some((x) => x.includes('--single-transaction') && x.includes('--set-gtid-purged=OFF')));
  assert.ok(!r.audit.some((x) => x.args.includes('stop') || x.args.includes('down')));
  assert.ok(fs.readdirSync(path.join(r.fixture, 'backups')).every((name) => !name.endsWith('.partial') && !name.endsWith('.sql')));
});
for (const [name, state] of Object.entries({ dump: { failDump: true }, encryption: { fail: 'age' }, upload: { fail: 'scp' }, checksum: { failRemoteVerify: true }, lock: { busy: true }, engine: { nonInnoDB: true } })) {
  test('backup ' + name + ' failure cannot report success or send heartbeat', () => {
    const r = run('backup', state); assert.notEqual(r.status, 0, r.diagnostic); assert.doesNotMatch(r.stdout, /BACKUP_OFFSITE_OK/);
    assert.ok(!r.audit.some((x) => x.tool === 'curl'));
  });
}
test('failed external heartbeat is a failing run after durable upload', () => {
  const r = run('backup', { fail: 'curl' }); assert.notEqual(r.status, 0); assert.doesNotMatch(r.stdout, /BACKUP_OFFSITE_OK/);
});
test('shell metacharacters and local/placeholder destination are rejected before Docker', () => {
  for (const dest of ['backup@offsite.test:/srv/backup;id', 'backup@offsite.test:/srv/../secret', 'backup@localhost:/srv/backup', 'backup@backup-host:/srv/backup']) {
    const r = run('backup', {}, ['--destination', dest]); assert.notEqual(r.status, 0); assert.deepEqual(r.audit, []);
  }
});
test('restore imports into a fresh network-isolated container and removes only its ID and anonymous volume', () => {
  const r = run('restore'); assert.equal(r.status, 0, r.diagnostic); assert.match(r.stdout, /RESTORE_DRILL_OK/);
  const create = r.audit.find((x) => x.tool === 'docker' && x.args[0] === 'create');
  assert.equal(create.args[create.args.indexOf('--network') + 1], 'none');
  assert.ok(!create.args.includes('-p') && !create.args.some((a) => a.includes('source=trade-')));
  assert.match(fs.readFileSync(path.join(r.fixture, 'imported.sql'), 'utf8'), /INSERT INTO proof/);
  assert.deepEqual(r.audit.find((x) => x.args[0] === 'rm').args, ['rm', '-fv', 'a'.repeat(64)]);
});
test('bad backup checksum is rejected before Docker starts', () => {
  const r = run('restore', { badChecksum: true }); assert.notEqual(r.status, 0); assert.deepEqual(r.audit, []);
});
for (const [name, state] of Object.entries({ import: { failImport: true }, tables: { emptyRestore: true }, integrity: { failCheck: true } })) {
  test('restore ' + name + ' failure cleans up and cannot report success', () => {
    const r = run('restore', state); assert.notEqual(r.status, 0, r.diagnostic); assert.doesNotMatch(r.stdout, /RESTORE_DRILL_OK/);
    assert.ok(r.audit.some((x) => x.args[0] === 'rm'));
  });
}
function alertmanager(target, state = {}) {
  const name = 'am-' + (++sequence), fixture = path.join(root, name), unixFixture = unixRoot + '/' + name;
  put(path.join(fixture, 'state.json'), JSON.stringify(state)); put(path.join(fixture, 'audit.jsonl'), '');
  if (target !== null) put(path.join(fixture, 'webhook'), target);
  const source = fs.readFileSync(path.resolve(__dirname, '../alertmanager/entrypoint.sh'), 'utf8')
    .replaceAll('/run/secrets/alertmanager_webhook_url', unixFixture + '/webhook')
    .replaceAll('/bin/amtool', unixRoot + '/bin/amtool').replaceAll('/bin/alertmanager', unixRoot + '/bin/alertmanager');
  put(path.join(fixture, 'entrypoint.sh'), source);
  const r = cp.spawnSync(bash, [unixFixture + '/entrypoint.sh'], { encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, TEST_NODE: nodePath, TEST_MOCK: unixRoot + '/mock.cjs', TEST_STATE: path.join(fixture, 'state.json'), TEST_AUDIT: path.join(fixture, 'audit.jsonl') } });
  if (r.error) throw r.error;
  return { ...r, audit: fs.readFileSync(path.join(fixture, 'audit.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse), diagnostic: r.stdout + r.stderr };
}
test('Alertmanager cannot start with absent, empty, HTTP or placeholder receiver', () => {
  for (const target of [null, '', 'http://notify.service.test', 'https://example.com/webhook', 'https://notify.invalid/token', 'https://localhost/notify']) {
    const r = alertmanager(target); assert.notEqual(r.status, 0, r.diagnostic); assert.deepEqual(r.audit, []);
  }
});
test('Alertmanager validates config before starting with an HTTPS receiver and never prints secret', () => {
  const target = 'https://notify.service.test/secret-token'; const r = alertmanager(target);
  assert.equal(r.status, 0, r.diagnostic); assert.deepEqual(r.audit.map((x) => x.tool), ['amtool', 'alertmanager']);
  assert.ok(!r.diagnostic.includes(target));
});
test('Alertmanager config validation failure prevents startup', () => {
  const r = alertmanager('https://notify.service.test/secret-token', { fail: 'amtool' });
  assert.notEqual(r.status, 0); assert.deepEqual(r.audit.map((x) => x.tool), ['amtool']);
});
after(() => {
  const resolved = fs.realpathSync(root); assert.equal(resolved, ownedRoot); assert.match(path.basename(resolved), /^trade-backup-tests\./);
  fs.rmSync(resolved, { recursive: true, force: true });
});
