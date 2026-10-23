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
  assert.equal(headers.get('authorization'), '""', 'gateway Basic Auth credentials must remain private');
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
