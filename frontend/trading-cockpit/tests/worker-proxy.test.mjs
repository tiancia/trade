import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import ts from "typescript";

test("the Worker proxy preserves Origin and CSRF while rebuilding trusted forwarding metadata", async () => {
  const source = await readFile(new URL("../worker/index.ts", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const forwarded = [];
  const exports = {};
  vm.runInNewContext(compiled, {
    exports,
    URL, Headers, Request, Response,
    require(name) {
      if (name === "vinext/server/image-optimization") return {};
      if (name === "vinext/server/app-router-entry") return { default: { fetch() { throw new Error("API proxy unexpectedly rendered a page"); } } };
      throw new Error(`Unexpected import: ${name}`);
    },
    async fetch(url, init) {
      forwarded.push({ url: String(url), init });
      return new Response("upstream rejection", { status: 403 });
    },
  });

  for (const [site, expectedHost, expectedProto, expectedPort] of [
    ["https://cockpit.example", "cockpit.example", "https", "443"],
    ["https://cockpit.example:8443", "cockpit.example:8443", "https", "8443"],
    ["http://localhost:4317", "localhost:4317", "http", "4317"],
  ]) {
    for (const origin of ["https://attacker.example", "https://cockpit.example", null]) {
      const headers = new Headers({
        "X-Trade-CSRF": "1",
        "Authorization": "Basic offline-fixture",
        "Forwarded": "for=192.0.2.1;host=attacker.example;proto=http",
        "X-Forwarded-Host": "attacker.example",
        "X-Forwarded-Proto": "http",
        "X-Forwarded-Port": "81",
        "X-Forwarded-For": "192.0.2.1",
        "X-Forwarded-Prefix": "/attacker",
        "X-Forwarded-Unknown": "untrusted",
        "X-Real-IP": "192.0.2.1",
      });
      if (origin !== null) headers.set("Origin", origin);
      const response = await exports.default.fetch(
        new Request(`${site}/api/automation/tasks/trading/start?test=1`, { method: "POST", headers }),
        { TRADE_API_URL: "https://backend.example/base" },
        {},
      );
      const request = forwarded.at(-1);
      assert.equal(request.url, "https://backend.example/base/api/automation/tasks/trading/start?test=1");
      assert.equal(request.init.headers.get("Origin"), origin);
      assert.equal(request.init.headers.get("X-Trade-CSRF"), "1");
      assert.equal(request.init.headers.get("Authorization"), "Basic offline-fixture");
      assert.equal(request.init.headers.get("X-Forwarded-Host"), expectedHost);
      assert.equal(request.init.headers.get("X-Forwarded-Proto"), expectedProto);
      assert.equal(request.init.headers.get("X-Forwarded-Port"), expectedPort);
      for (const name of ["Forwarded", "X-Forwarded-For", "X-Forwarded-Prefix", "X-Forwarded-Unknown", "X-Real-IP"]) {
        assert.equal(request.init.headers.get(name), null);
      }
      assert.equal(request.init.method, "POST");
      assert.equal(request.init.redirect, "manual");
      assert.equal(response.status, 403);
    }
  }
});
