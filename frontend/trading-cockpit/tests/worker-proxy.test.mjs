import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import ts from "typescript";

test("the Worker API proxy preserves the caller's Origin and CSRF header", async () => {
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

  for (const origin of ["https://attacker.example", "https://cockpit.example", null]) {
    const headers = new Headers({ "X-Trade-CSRF": "1", "X-Forwarded-Host": "attacker.example" });
    if (origin !== null) headers.set("Origin", origin);
    const response = await exports.default.fetch(
      new Request("https://cockpit.example/api/automation/tasks/trading/start?test=1", { method: "POST", headers }),
      { TRADE_API_URL: "https://backend.example/base" },
      {},
    );
    const request = forwarded.at(-1);
    assert.equal(request.url, "https://backend.example/base/api/automation/tasks/trading/start?test=1");
    assert.equal(request.init.headers.get("Origin"), origin);
    assert.equal(request.init.headers.get("X-Trade-CSRF"), "1");
    assert.equal(request.init.headers.get("X-Forwarded-Host"), "cockpit.example");
    assert.equal(request.init.headers.get("X-Forwarded-Proto"), "https");
    assert.equal(request.init.method, "POST");
    assert.equal(request.init.redirect, "manual");
    assert.equal(response.status, 403);
  }
});
