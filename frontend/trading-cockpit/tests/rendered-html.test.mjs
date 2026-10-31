import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";

const root = new URL("../", import.meta.url);

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  const request = new Request(`http://localhost${path}`, { headers: { accept: "text/html" } });
  const context = { waitUntil() {}, passThroughOnException() {} };

  // Node SSR exports a request handler; the Sites build exports a Worker object.
  // Exercise either build directly in memory without opening a listening server.
  if (typeof worker === "function") return worker(request, context);
  return worker.fetch(
    request,
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    context,
  );
}

test("server-renders the Orbit trading cockpit", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>Orbit Trading Cockpit<\/title>/i);
  assert.match(html, /交易驾驶舱/);
  assert.match(html, /事件总线健康/);
  assert.match(html, /交易自动化/);
  assert.match(html, /安全演示快照|演示数据/);
  // Time and locale-sensitive labels stay identical until the browser hydrates.
  assert.match(html, /<span class="clock">--:--:--<\/span>/);
  assert.match(html, /<div class="chart-time">(?:<span>--:--<\/span>){5}<\/div>/);
  assert.match(html, /<span class="timestamp">—<\/span>/);
  assert.doesNotMatch(html, /<span class="candle [^"]*" title=/);
  assert.doesNotMatch(html, /codex-preview|Your site is taking shape|react-loading-skeleton/i);
});

test("module management has a direct route and waits for real backend state", async () => {
  const response = await render("/controls");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /功能管理/);
  assert.match(html, /正在加载模块和运行状态/);
  assert.match(html, /href="\/"/);
  assert.doesNotMatch(html, /role="switch"|演示数据/);
});

test("production HTTP serves every page script, stylesheet and self-hosted font", { timeout: 20_000 }, async () => {
  const reservation = createServer();
  await new Promise((resolve) => reservation.listen(0, "127.0.0.1", resolve));
  const port = reservation.address().port;
  await new Promise((resolve) => reservation.close(resolve));
  const base = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["node_modules/vinext/dist/cli.js", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
    cwd: fileURLToPath(root), stdio: ["ignore", "pipe", "pipe"], windowsHide: true,
  });
  let diagnostics = "";
  child.stdout.on("data", (chunk) => { diagnostics = (diagnostics + chunk).slice(-20_000); });
  child.stderr.on("data", (chunk) => { diagnostics = (diagnostics + chunk).slice(-20_000); });
  const exited = new Promise((resolve) => child.once("exit", resolve));
  try {
    let response;
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (child.exitCode !== null) throw new Error(`SSR process exited: ${diagnostics}`);
      try { response = await fetch(base, { signal: AbortSignal.timeout(1000) }); break; }
      catch { await new Promise((resolve) => setTimeout(resolve, 100)); }
    }
    assert.equal(response?.status, 200, diagnostics);
    const html = await response.text();
    assert.doesNotMatch(html, /url\(["']?(?:file:|[A-Za-z]:[\\/])/i, "Font CSS must never expose filesystem URLs");
    const assets = new Set([...html.matchAll(/(?:src|href)="(\/[^" ]+\.(?:js|css))"/g)].map((match) => match[1]));
    assert.ok([...assets].some((asset) => asset.endsWith(".js")), "Page must load its client scripts");
    assert.ok([...assets].some((asset) => asset.endsWith(".css")), "Page must load its styles");
    for (const asset of assets) {
      const result = await fetch(new URL(asset, base), { signal: AbortSignal.timeout(1000) });
      assert.equal(result.status, 200, `${asset} must be served over HTTP`);
      await result.arrayBuffer();
    }
    const fonts = new Set([...html.matchAll(/url\(["']?(\/[^)"']+\.woff2)["']?\)/g)].map((match) => match[1]));
    assert.ok(fonts.size > 0, "The page must self-host Geist fonts");
    for (const font of fonts) {
      const result = await fetch(new URL(font, base), { signal: AbortSignal.timeout(1000) });
      assert.equal(result.status, 200, `${font} must be served over HTTP`);
      assert.match(result.headers.get("content-type") ?? "", /^font\/woff2\b/i);
      assert.ok((await result.arrayBuffer()).byteLength > 0);
    }
    const manifest = await fetch(`${base}/.vite/manifest.json`);
    assert.equal(manifest.status, 404, "Private build metadata must remain inaccessible");
  } finally {
    child.kill();
    await exited;
  }
});

test("removes starter-only assets and metadata", async () => {
  const [page, layout, packageJson] = await Promise.all([
    readFile(new URL("app/page.tsx", root), "utf8"),
    readFile(new URL("app/layout.tsx", root), "utf8"),
    readFile(new URL("package.json", root), "utf8"),
  ]);

  assert.match(page, /Orbit|ORBIT/);
  assert.match(layout, /Orbit Trading Cockpit/);
  assert.match(layout, /openGraph/);
  assert.doesNotMatch(page, /_sites-preview|SkeletonPreview|codex-preview/);
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  await assert.rejects(access(new URL("app/_sites-preview/SkeletonPreview.tsx", root)));
});

test("wires strategy activation and live candle streaming to the trading backend", async () => {
  const [page, worker] = await Promise.all([
    readFile(new URL("app/page.tsx", root), "utf8"),
    readFile(new URL("worker/index.ts", root), "utf8"),
  ]);

  assert.match(page, /\/api\/trading\/strategies\/active/);
  assert.match(page, /method:\s*"PUT"/);
  assert.match(page, /new EventSource\(streamUrl\)/);
  assert.match(page, /\/api\/trading\/market\/candles\/stream/);
  assert.match(page, /行情正常 · 推送已连接/);
  assert.match(page, /行情已过期 · 15 秒重试/);
  assert.match(worker, /env\.TRADE_API_URL/);
  assert.match(worker, /url\.pathname\.startsWith\("\/api\/"\)/);
});
