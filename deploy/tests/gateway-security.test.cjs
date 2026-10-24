// Offline contract checks for Nginx's forwarding trust boundary. No server runs.
// Run with: node --test deploy/tests/gateway-security.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');

const nginx = path.resolve(__dirname, '../nginx');
const common = fs.readFileSync(path.join(nginx, 'includes/proxy-common.conf'), 'utf8');
const headers = new Map([...common.matchAll(/^proxy_set_header\s+(\S+)\s+([^;]+);/gm)]
  .map(([, name, value]) => [name.toLowerCase(), value]));

test('framework forwarding metadata cannot bypass gateway values with standard or prefix headers', () => {
  for (const name of ['forwarded', 'x-forwarded-prefix', 'x-forwarded-ssl']) {
    assert.equal(headers.get(name), '""', name + ' must be stripped');
  }
  assert.equal(headers.get('x-forwarded-host'), '$http_host');
  assert.equal(headers.get('x-forwarded-proto'), '$scheme');
  assert.equal(headers.get('x-forwarded-port'), '$trade_forwarded_port');
  assert.equal(headers.get('x-forwarded-for'), '$remote_addr');
  assert.equal(headers.get('x-real-ip'), '$remote_addr');
  assert.equal(headers.has('authorization'), false, 'API forwarding sets Authorization only once');
});

test('only backend API locations receive Basic credentials and retain all sanitized forwarding headers', () => {
  const api = fs.readFileSync(path.join(nginx, 'includes/api.conf'), 'utf8');
  const backend = fs.readFileSync(path.join(nginx, 'includes/backend-proxy.conf'), 'utf8');
  assert.equal((api.match(/include \/etc\/nginx\/includes\/backend-proxy\.conf;/g) || []).length, 2);
  assert.match(backend, /include \/etc\/nginx\/includes\/proxy-common\.conf;/);
  assert.match(backend, /proxy_set_header Authorization \$http_authorization;/);
  for (const template of ['private.conf.template', 'default.conf.template']) {
    const source = fs.readFileSync(path.join(nginx, template), 'utf8');
    assert.equal((source.match(/proxy_set_header Authorization "";/g) || []).length, 2);
  }
});

test('every public TLS server requires a client certificate and a revocation list', () => {
  const source = fs.readFileSync(path.join(nginx, 'default.conf.template'), 'utf8');
  assert.equal((source.match(/listen 443 ssl;/g) || []).length, 2);
  assert.equal((source.match(/ssl_verify_client on;/g) || []).length, 2);
  assert.equal((source.match(/ssl_client_certificate \/etc\/nginx\/tls\/client-ca\.pem;/g) || []).length, 2);
  assert.equal((source.match(/ssl_crl \/etc\/nginx\/tls\/client-crl\.pem;/g) || []).length, 2);
  assert.match(source, /listen 127\.0\.0\.1:8082;/);
  const controls = fs.readFileSync(path.join(nginx, 'includes/management-controls.conf'), 'utf8');
  assert.match(controls, /limit_req_zone \$binary_remote_addr/);
  assert.match(controls, /limit_req_status 429;/);
  assert.match(controls, /log_format trade_audit escape=json/);
  assert.doesNotMatch(controls, /\$(request_uri|http_authorization|request_body)\b/);
});

// Read the actual map's regex order and replacement, then exercise the port
// routing contract for both gateway templates, including SSH and IPv6 hosts.
function readPortMap(source) {
  const body = /map\s+"\$scheme:\$http_host"\s+\$trade_forwarded_port\s*\{([^}]+)\}/.exec(source)?.[1];
  assert.ok(body, 'gateway must derive forwarded ports from the direct request');
  let fallback;
  const rules = [];
  for (const [, expression, replacement] of body.matchAll(/^\s*(\S+)\s+(\S+);\s*$/gm)) {
    if (expression === 'default') fallback = replacement;
    else {
      assert.ok(expression.startsWith('~'), 'only explicit map regex rules are expected');
      rules.push([new RegExp(expression.slice(1)), replacement]);
    }
  }
  assert.ok(fallback);
  return (scheme, host) => {
    for (const [pattern, replacement] of rules) {
      const match = pattern.exec(scheme + ':' + host);
      if (!match) continue;
      if (!replacement.startsWith('$')) return replacement;
      const port = match.groups?.[replacement.slice(1)];
      assert.ok(port, 'a port map variable must come from the matched direct Host');
      return port;
    }
    return fallback;
  };
}

for (const template of ['private.conf.template', 'default.conf.template']) {
  test(template + ' preserves browser ports without trusting a forwarded port header', () => {
    const source = fs.readFileSync(path.join(nginx, template), 'utf8');
    const portFor = readPortMap(source);
    for (const [scheme, host, expected] of [
      ['http', 'localhost:18080', '18080'],
      ['http', '127.0.0.1:18081', '18081'],
      ['http', '[::1]:18080', '18080'],
      ['http', 'localhost', '80'],
      ['https', 'dashboard.example', '443'],
      ['https', 'dashboard.example:8443', '8443'],
    ]) {
      assert.equal(portFor(scheme, host), expected, scheme + '://' + host);
    }
    assert.equal((source.match(/include \/etc\/nginx\/includes\/proxy-common\.conf;/g) || []).length, 2);
  });
}
