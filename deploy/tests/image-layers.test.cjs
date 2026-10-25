// Offline layout checks. Optional artifact checks use built output and tools mode;
// no Docker daemon, application server, provider or production service is started.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test } = require('node:test');

const root = path.resolve(__dirname, '../..');
const backend = fs.readFileSync(path.join(root, 'backend/Dockerfile'), 'utf8');
const cockpit = fs.readFileSync(path.join(root, 'frontend/trading-cockpit/Dockerfile'), 'utf8');

function runtimeCopies(source) {
  const runtime = source.split(/^FROM .* AS runtime\s*$/m)[1];
  assert.ok(runtime, 'a separate runtime stage is required');
  return [...runtime.matchAll(/^COPY\s+--from=build\s+--chown=\S+\s+(.+)$/gm)].map(([, argumentsText]) => {
    const argumentsList = argumentsText.trim().split(/\s+/);
    return { sources: argumentsList.slice(0, -1), destination: argumentsList.at(-1) };
  });
}

function filesBelow(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const child = path.join(directory, entry.name);
    return entry.isDirectory() ? filesBelow(child) : [child];
  });
}

// Resolve the actual COPY inputs to files in a built artifact, preserving Docker's
// directory-content semantics. This catches omitted or misplaced runtime files.
function copiedFiles(copies, sourcePrefix, artifactDirectory) {
  const result = new Map();
  for (const copy of copies) {
    for (const source of copy.sources) {
      assert.ok(source.startsWith(sourcePrefix), source);
      const local = path.join(artifactDirectory, source.slice(sourcePrefix.length));
      assert.ok(fs.existsSync(local), `COPY input must exist: ${source}`);
      const directory = fs.statSync(local).isDirectory();
      for (const file of directory ? filesBelow(local) : [local]) {
        const relative = directory ? path.relative(local, file) : path.basename(file);
        const destination = path.posix.join(copy.destination, relative.replaceAll('\\', '/'));
        assert.equal(result.has(destination), false, `runtime file must not be copied twice: ${destination}`);
        result.set(destination, file);
      }
    }
  }
  return result;
}

test('source-only backend edits preserve the ordinary Maven dependency layer and every offline test', () => {
  const pom = backend.search(/^COPY .*backend\/pom\.xml.*$/m);
  const dependencies = backend.search(/^RUN (?:\.\/mvnw|mvn) (?:-s \S+ )?-B -ntp dependency:go-offline$/m);
  const sources = backend.indexOf('COPY backend/src/');
  assert.ok(pom >= 0 && dependencies > pom && sources > dependencies);
  assert.doesNotMatch(backend, /--mount=type=cache[^\n]*target=\/root\/\.m2/,
    'an empty fresh-runner cache mount must not hide the restored Maven repository');
  assert.match(backend, /RUN RUN_INTEGRATION_TESTS=false (?:\.\/mvnw|mvn) (?:-s \S+ )?-B -ntp clean package/);
  assert.doesNotMatch(backend, /(?:skipTests|maven\.test\.skip)/);
  assert.deepEqual(runtimeCopies(backend).map((copy) => copy.sources), [
    ['/workspace/backend/extracted/dependencies/'],
    ['/workspace/backend/extracted/spring-boot-loader/'],
    ['/workspace/backend/extracted/snapshot-dependencies/'],
    ['/workspace/backend/extracted/application/'],
  ]);
});

test('cockpit application bundles cannot invalidate the runtime package COPY layer', () => {
  const copies = runtimeCopies(cockpit);
  assert.deepEqual(copies[0], {
    sources: ['/app/dist/standalone/node_modules'], destination: './node_modules',
  });
  assert.ok(copies.slice(1).every((copy) => copy.sources.every((source) => !source.includes('/node_modules'))));
  assert.equal(copies.some((copy) => copy.sources.includes('/app/dist/standalone')), false);
  assert.match(cockpit, /^USER node$/m);
  assert.match(cockpit, /^HEALTHCHECK /m);
});

const packagedJar = path.join(root, 'backend/target/trade-0.0.1-SNAPSHOT.jar');
test('Spring Boot tools extraction matches all runtime COPY paths and resolves the slim jar classpath', {
  skip: !fs.existsSync(packagedJar) && 'run backend mvnw clean package first',
}, (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'trade-image-layers-'));
  const ownedDirectory = fs.realpathSync(directory);
  const javaBin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
  const java = javaBin ? path.join(javaBin, process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const jar = javaBin ? path.join(javaBin, process.platform === 'win32' ? 'jar.exe' : 'jar') : 'jar';
  const run = (command, args, cwd = directory) => {
    const result = cp.spawnSync(command, args, { cwd, windowsHide: true, encoding: 'utf8', timeout: 30_000 });
    if (result.error) throw result.error;
    assert.equal(result.status, 0, result.stdout + result.stderr);
    return result.stdout;
  };
  try {
    fs.copyFileSync(packagedJar, path.join(directory, 'app.jar'));
    run(java, ['-Djarmode=tools', '-jar', 'app.jar', 'extract', '--layers', '--destination', 'extracted']);
    const files = copiedFiles(runtimeCopies(backend), '/workspace/backend/extracted/', path.join(directory, 'extracted'));
    assert.ok(files.has('app.jar'), 'the renamed slim jar must retain the existing runtime entrypoint');
    assert.match(backend, /ENTRYPOINT \["java", "-jar", "\/app\/app\.jar"\]/);
    assert.ok(fs.statSync(files.get('app.jar')).size < fs.statSync(packagedJar).size / 2,
      'the application layer must no longer include all dependencies');
    context.diagnostic(`Executable jar ${fs.statSync(packagedJar).size} bytes; application jar ${fs.statSync(files.get('app.jar')).size} bytes`);
    const manifestDirectory = path.join(directory, 'manifest');
    fs.mkdirSync(manifestDirectory);
    run(jar, ['xf', files.get('app.jar'), 'META-INF/MANIFEST.MF'], manifestDirectory);
    const manifest = fs.readFileSync(path.join(manifestDirectory, 'META-INF/MANIFEST.MF'), 'utf8').replace(/\r?\n /g, '');
    assert.match(manifest, /^Main-Class: com\.trade\.TradeApplication\r?$/m);
    const classpath = /^Class-Path: (.+)\r?$/m.exec(manifest)?.[1].trim().split(/\s+/);
    assert.ok(classpath?.length > 10, 'the slim executable must reference extracted libraries');
    classpath.forEach((entry) => assert.ok(files.has(entry), `runtime COPY must include ${entry}`));
  } finally {
    assert.equal(fs.realpathSync(directory), ownedDirectory);
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

const standalone = path.join(root, 'frontend/trading-cockpit/dist/standalone');
test('the emitted standalone layout retains the server, bundles and every runtime package', {
  skip: !fs.existsSync(standalone) && 'generate the cockpit standalone artifact first',
}, () => {
  const files = copiedFiles(runtimeCopies(cockpit), '/app/dist/standalone/', standalone);
  const originalFiles = filesBelow(standalone).map((file) => path.relative(standalone, file).replaceAll('\\', '/'));
  assert.deepEqual([...files.keys()].sort(), originalFiles.sort(), 'splitting layers must preserve the complete standalone output');
  for (const required of ['server.js', 'package.json', 'node_modules/vinext/package.json']) {
    assert.ok(files.has(required), required);
  }
  assert.ok([...files.keys()].some((file) => file.startsWith('dist/client/')));
  assert.ok([...files.keys()].some((file) => file.startsWith('dist/server/')));
});
