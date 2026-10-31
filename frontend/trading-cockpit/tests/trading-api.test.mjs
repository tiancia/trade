import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import ts from "typescript";
import * as tradingApi from "../app/lib/trading-api.mjs";
import {
  apiTimestampMillis,
  aiReviewScheduleLabel,
  formatTradingTimestamp,
  buildBacktestPayload,
  createJsonRequestHeaders,
  formatDateTimeLocal,
  buildStrategyParametersPayload,
  buildPaperSessionPayload,
  filterConfigurationEvents,
  formatReturnFraction,
  mergeContiguousHistory,
  marketDataIsFresh,
  backtestSelectionForStrategy,
  decisionIndicatorEntries,
  intradayModes,
  parameterFieldsForStrategy,
} from "../app/lib/trading-api.mjs";

const request = {
  strategyId: "threshold-event-default",
  instId: "BTC-USDT",
  bar: "1m",
  initialCash: "10000",
  from: "2026-10-16T08:00",
  to: "2026-10-17T08:00",
};

test("AI schedule distinguishes paused, due, overdue and persisted future windows", () => {
  const now = Date.parse("2026-10-29T06:00:00Z");
  assert.equal(aiReviewScheduleLabel(false, true, now, now), "AI 未启用");
  assert.equal(aiReviewScheduleLabel(true, undefined, now, now), "任务状态待读取");
  assert.equal(aiReviewScheduleLabel(true, false, now, now), "AI 已暂停");
  assert.equal(aiReviewScheduleLabel(true, true, null, now), "等待首次评估");
  assert.match(aiReviewScheduleLabel(true, true, (now + 86400000) / 1000, now), /下次评估窗口/);
  assert.equal(aiReviewScheduleLabel(true, true, now / 1000, now), "已到评估窗口，等待任务调度");
  assert.match(aiReviewScheduleLabel(true, true, new Date(now - 120000).toISOString(), now), /检查任务运行状态/);
});

test("AI timestamps use the trading zone even in a UTC browser and preserve epoch seconds", () => {
  const original = process.env.TZ;
  try {
    process.env.TZ = "UTC";
    const instant = "2026-10-29T12:03:59Z";
    assert.match(formatTradingTimestamp(instant, "Asia/Shanghai"), /20:03:59/);
    assert.equal(formatTradingTimestamp(Date.parse(instant) / 1000, "Asia/Shanghai"), formatTradingTimestamp(instant, "Asia/Shanghai"));
    assert.match(formatTradingTimestamp(instant, "America/New_York"), /8:03:59|08:03:59/);
    assert.equal(formatTradingTimestamp(null), "暂无记录");
  } finally {
    if (original == null) delete process.env.TZ; else process.env.TZ = original;
  }
});

test("market freshness requires a recent observation and the current candle period", () => {
  const now = Date.parse("2026-10-22T08:04:30Z");
  const candles = [{ ts: String(Date.parse("2026-10-22T08:04:00Z")) }];
  assert.equal(marketDataIsFresh(candles, "1m", now - 15_000, now), true);
  assert.equal(marketDataIsFresh(candles, "1m", now - 30_001, now), false);
  assert.equal(marketDataIsFresh([{ ts: String(now - 180_000) }], "1m", now, now), false);
  assert.equal(marketDataIsFresh([{ ts: String(now + 60_000) }], "1m", now, now), false);
  assert.equal(marketDataIsFresh([], "1m", now, now), false);
  assert.equal(marketDataIsFresh(candles, "unknown", now, now), false);
  const fiveMinute = [{ ts: String(Date.parse("2026-10-22T08:00:00Z")) }];
  assert.equal(marketDataIsFresh(fiveMinute, "5m", now, now), true);
  const nextPeriod = Date.parse("2026-10-22T08:05:00Z");
  assert.equal(marketDataIsFresh(fiveMinute, "5m", nextPeriod, nextPeriod), false);
});

// Mount the real page with inert JSX and hooks so transport completion order is
// exercised without adding a DOM test framework to the production dependency tree.
async function mountMarketRequests({ runtime = {}, configuration, history = [], dataMode = "demo", connected = false, tasks } = {}) {
  const callbacks = [];
  const effects = [];
  const states = [];
  const pending = [];
  const timers = [];
  const sources = [];
  let renderingEditor = false;
  let editorIndex = 0;
  const editorStates = [configuration, history, {}, "", false, false];
  const hooks = {
    useState(initial) {
      let value = typeof initial === "function" ? initial() : initial;
      if (renderingEditor && editorIndex < editorStates.length) value = editorStates[editorIndex++];
      else if (value?.runtime) value = { ...value, runtime: { ...value.runtime, ...runtime }, ...(tasks ? { tasks } : {}) };
      else if (value === "demo") value = dataMode;
      else if (states.length === 3) value = connected;
      const state = { value };
      states.push(state);
      return [state.value, (next) => { state.value = typeof next === "function" ? next(state.value) : next; }];
    },
    useRef: (current) => ({ current }),
    useCallback: (callback) => { callbacks.push(callback); return callback; },
    useMemo: (callback) => callback(),
    useEffect: (callback, dependencies) => { effects.push({ callback, dependencies }); },
  };
  const exports = {};
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(`${source}\nexport { ConfigurationEditor as testConfigurationEditor, mergeCandles as testMergeCandles };`, {
    fileName: "page.tsx",
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  vm.runInNewContext(compiled, {
    exports, process: { env: {} }, URL, Headers, AbortController, AbortSignal,
    window: { setTimeout: (callback) => timers.push(callback), clearTimeout() {}, setInterval() {}, clearInterval() {} },
    EventSource: class {
      listeners = {};
      constructor(url) { this.url = url; sources.push(this); }
      addEventListener(name, callback) { this.listeners[name] = callback; }
      close() {}
    },
    fetch(url, init) { return new Promise((resolve, reject) => pending.push({ url, init, resolve, reject })); },
    require(name) {
      if (name === "react") return hooks;
      if (name === "react/jsx-runtime") return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) };
      if (name === "lucide-react") return {};
      if (name === "next/link") return { default: "a" };
      if (name === "./lib/trading-api.mjs") return tradingApi;
      throw new Error(`Unexpected import: ${name}`);
    },
  });
  const tree = exports.default();
  return {
    refresh: callbacks.find((callback) => callback.toString().includes("/api/trading/runtime/status")),
    snapshot: states.find((state) => state.value?.runtime),
    mode: states.find((state) => state.value === dataMode),
    connected: states[3],
    tree,
    load: callbacks.find((callback) => callback.toString().includes("/api/trading/market/candles?")),
    effect: effects.find((effect) => effect.dependencies[0] === "1m").callback,
    market: states.find((state) => state.value?.candles),
    stream: states.find((state) => state.value === "connecting"),
    backtestInstrument: states.find((state) => state.value === "BTC-USDT"),
    openBacktest: callbacks.find((callback) => callback.toString().includes("setBacktestInstrument")),
    mergeCandles: exports.testMergeCandles,
    renderEditor() {
      renderingEditor = true;
      return exports.testConfigurationEditor({ strategyId: configuration.strategyId, connected: true, onSaved() {} });
    },
    pending, timers, sources,
  };
}

function findElement(tree, predicate) {
  if (!tree || typeof tree !== "object") return undefined;
  if (predicate(tree)) return tree;
  for (const child of [tree.props?.children].flat(Infinity)) {
    const match = findElement(child, predicate);
    if (match) return match;
  }
}

async function mountPaperPanel(panelName = "PaperSimulationPanel", dataMode = "live") {
  const state = [];
  const refs = [];
  const timers = [];
  const pending = [];
  let stateIndex = 0;
  let refIndex = 0;
  let effect;
  let dependencies;
  const listeners = {};
  const hooks = {
    useState(initial) {
      const index = stateIndex++;
      if (!(index in state)) state[index] = typeof initial === "function" ? initial() : initial;
      return [state[index], (value) => { state[index] = typeof value === "function" ? value(state[index]) : value; }];
    },
    useRef(initial) {
      const index = refIndex++;
      return refs[index] ??= { current: initial };
    },
    useEffect(callback, deps) { effect = callback; dependencies = deps; },
  };
  const exports = {};
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(`${source}\nexport { ${panelName} as testPaperPanel };`, {
    fileName: "page.tsx", compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  vm.runInNewContext(compiled, {
    exports, process: { env: {} }, URL, Headers, AbortController, AbortSignal,
    window: { setTimeout: (callback) => timers.push(callback), clearTimeout() {}, setInterval: (callback) => timers.push(callback), clearInterval() {},
      addEventListener(name, callback) { listeners[name] = callback; }, removeEventListener(name) { delete listeners[name]; } },
    fetch(url, init) { return new Promise((resolve, reject) => pending.push({ url, init, resolve, reject })); },
    require(name) {
      if (name === "react") return hooks;
      if (name === "react/jsx-runtime") return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) };
      if (name === "lucide-react") return {};
      if (name === "next/link") return { default: "a" };
      if (name === "./lib/trading-api.mjs") return tradingApi;
      throw new Error(`Unexpected import: ${name}`);
    },
  });
  const render = (props = {}) => { stateIndex = 0; refIndex = 0; return exports.testPaperPanel({ dataMode, executionMode: "PAPER", refreshToken: 0, onCurrentChange() {}, ...props }); };
  const flush = () => new Promise((resolve) => setImmediate(resolve));
  return { render, flush, pending, listeners, dependencies: () => dependencies, load() { const cleanup = effect(); timers.shift()?.(); return cleanup; } };
}

test("AI advisor preserves assessments during refresh and ignores reads overtaken by a task mutation", async () => {
  const { render, load, pending, flush } = await mountPaperPanel("TradingAiPanel");
  const status = { enabled: true, autoApplyEligible: true, telegramEnabled: true, reviewIntervalMs: 3600000 };
  const review = { reviewId: "r1", startedAt: "2026-10-28T00:00:00Z", outcome: "APPLIED", detail: "市场趋势变化", notificationStatus: "NOT_REQUIRED", proposalJson: '{"strategyId":"trend","parameters":{"ema-fast-period":12}}' };
  const task = { id: "trading-ai", running: true, loops: [] };
  render(); load();
  assert.deepEqual(pending.map(item => item.url), ["/api/trading/ai/status", "/api/trading/ai/reviews?limit=5", "/api/automation/tasks/trading-ai"]);
  pending.shift().resolve(Response.json(status)); pending.shift().resolve(Response.json([review])); pending.shift().resolve(Response.json(task));
  await flush();
  let tree = render();
  assert.ok(findElement(tree, node => node.type === "details"));
  load();
  tree = render();
  assert.ok(findElement(tree, node => node.type === "details"), "periodic refresh keeps the prior assessment visible");
  const button = findElement(tree, node => node.type === "button" && node.props.children === "暂停 AI");
  assert.equal(button.props.children, "暂停 AI");
  button.props.onClick();
  const mutation = pending.pop();
  assert.equal(mutation.url, "/api/automation/tasks/trading-ai/stop");
  assert.equal(mutation.init.method, "POST");
  assert.equal(new Headers(mutation.init.headers).get("X-Trade-CSRF"), "1");
  mutation.resolve(Response.json({ ...task, running: false }));
  await flush();
  pending.shift().resolve(Response.json(status)); pending.shift().resolve(Response.json([review])); pending.shift().resolve(Response.json(task));
  await flush();
  tree = render();
  assert.ok(findElement(tree, node => node.type === "button" && node.props.children === "启动 AI"), "late read cannot undo the stop result");
  load(); pending.shift().reject(new Error("offline")); pending.shift().resolve(Response.json([])); pending.shift().resolve(Response.json(task));
  await flush();
  tree = render();
  assert.ok(findElement(tree, node => node.type === "details"));
  assert.ok(findElement(tree, node => node.props?.role === "alert"));
});

test("AI advisor demo mode makes no provider or management requests", async () => {
  const { render, load, pending } = await mountPaperPanel("TradingAiPanel", "demo");
  render(); load();
  assert.equal(pending.length, 0);
  assert.equal(findElement(render(), node => node.type === "button"), undefined);
});

test("global AI refresh cancels old reads and focus reloads the latest applied strategy", async () => {
  const panel = await mountPaperPanel("TradingAiPanel");
  const task = { id: "trading-ai", running: true, loops: [] };
  const status = { enabled: true, reviewIntervalMs: 1800000,
    assessment: { activeStrategyId: "intraday-range-15m", activeStrategyConfigVersion: 4,
      observedAt: "2026-10-29T12:00:00Z", nextReviewAt: "2026-10-29T12:30:00Z" } };
  panel.render({ refreshToken: 10 });
  const oldDependencies = [...panel.dependencies()];
  const cleanup = panel.load();
  const oldReads = panel.pending.splice(0);
  cleanup();
  panel.render({ refreshToken: 11 });
  assert.notDeepEqual(panel.dependencies(), oldDependencies);
  panel.load();
  assert.ok(oldReads.every(item => item.init.signal.aborted));
  panel.pending.shift().resolve(Response.json(status));
  panel.pending.shift().resolve(Response.json([]));
  panel.pending.shift().resolve(Response.json(task));
  await panel.flush();
  oldReads[0].resolve(Response.json({ ...status, assessment: { ...status.assessment, activeStrategyId: "old-trend", activeStrategyConfigVersion: 1 } }));
  oldReads[1].resolve(Response.json([])); oldReads[2].resolve(Response.json(task));
  await panel.flush();
  const tree = JSON.stringify(panel.render({ refreshToken: 11 }));
  assert.match(tree, /intraday-range-15m/);
  assert.doesNotMatch(tree, /old-trend/);
  panel.listeners.focus();
  assert.equal(panel.pending.length, 3);
  assert.ok(panel.pending.every(item => !item.init.method || item.init.method === "GET"));
  for (const item of panel.pending.splice(0)) item.reject(new Error("offline"));
  await panel.flush();
});

test("paper refresh keeps the displayed session until the new data arrives", async () => {
  const { render, flush, pending, load } = await mountPaperPanel();
  const account = { id: 1, sessionId: "session-1", status: "ACTIVE", instId: "BTC-USDT", quoteCcy: "USDT", initialCash: 100, cash: 100, equity: 100, realizedPnl: 0, positionQuantity: 0, totalFeesQuote: 0 };
  const archived = { ...account, id: 2, sessionId: "session-2", status: "ARCHIVED" };
  render();
  load();
  pending.shift().resolve(Response.json(account));
  pending.shift().resolve(Response.json([account, archived]));
  await flush();
  pending.shift().resolve(Response.json([]));
  pending.shift().resolve(Response.json([]));
  await flush();
  let tree = render();
  assert.ok(findElement(tree, (node) => node.props?.className === "session-identity"));

  load();
  tree = render();
  assert.ok(findElement(tree, (node) => node.props?.className === "session-identity"), "refresh must not remove the session");
  assert.ok(!findElement(tree, (node) => node.props?.children?.includes?.("正在读取模拟资金与历史…")), "refresh must not show the blocking placeholder");
  pending.shift().reject(new Error("offline"));
  pending.shift().resolve(Response.json([account]));
  await flush();
  tree = render();
  assert.ok(findElement(tree, (node) => node.props?.className === "session-identity"), "failed refresh retains the last session");
  assert.ok(findElement(tree, (node) => node.props?.role === "alert" && node.props.children.includes("当前显示上次读取的数据。")));
  assert.equal(findElement(tree, (node) => node.type === "button" && node.props?.type === "submit").props.disabled, true);

  findElement(tree, (node) => node.props?.["aria-label"] === "查看模拟会话").props.onChange({ target: { value: "session-2" } });
  render();
  load();
  tree = render();
  assert.ok(!findElement(tree, (node) => node.props?.className === "session-identity"), "the old session must not be shown under a new selection");
  assert.ok(findElement(tree, (node) => node.props?.children?.includes?.("正在读取模拟资金与历史…")));
});

test("paper deletion requires confirmation, sends the selected revision, and clears its cached history", async () => {
  const { render, flush, pending, load } = await mountPaperPanel();
  const account = { id: 1, sessionId: "session/1", status: "ACTIVE", instId: "BTC-USDT", quoteCcy: "USDT", initialCash: 100, cash: 100, equity: 100, realizedPnl: 0, positionQuantity: 0, totalFeesQuote: 0, version: 3 };
  render(); load();
  pending.shift().resolve(Response.json(account));
  pending.shift().resolve(Response.json([account]));
  await flush();
  pending.shift().resolve(Response.json([])); pending.shift().resolve(Response.json([]));
  await flush();
  let tree = render();
  const button = findElement(tree, (node) => node.type === "button" && node.props?.children?.includes?.("删除此会话"));
  assert.equal(button.props.disabled, false);
  button.props.onClick();
  assert.equal(pending.length, 0, "opening confirmation must not delete");
  tree = render();
  findElement(tree, (node) => node.type === "button" && node.props.children === "取消").props.onClick();
  tree = render();
  assert.ok(!findElement(tree, (node) => node.props?.["aria-label"] === "确认删除模拟会话"));
  button.props.onClick(); tree = render();
  const deleting = findElement(tree, (node) => node.type === "button" && node.props?.children?.includes?.("确认删除")).props.onClick();
  assert.equal(pending[0].url, "/api/trading/paper/sessions/session%2F1?expectedVersion=3");
  assert.equal(pending[0].init.method, "DELETE");
  assert.equal(pending[0].init.headers.get("X-Trade-CSRF"), "1");
  render(); load();
  assert.equal(pending.length, 1, "background refresh must not race the deletion");
  pending.shift().resolve(new Response(null, { status: 204 }));
  await deleting;
  tree = render();
  assert.ok(!findElement(tree, (node) => node.props?.className === "session-identity"));
  assert.ok(findElement(tree, (node) => node.props?.role === "status" && typeof node.props.children === "string" && node.props.children.includes("已删除")));
});

test("a rejected paper deletion retains the session and reports a conflict", async () => {
  const { render, flush, pending, load } = await mountPaperPanel();
  const account = { sessionId: "archived", status: "ARCHIVED", instId: "BTC-USDT", quoteCcy: "USDT", initialCash: 100, cash: 100, equity: 100, realizedPnl: 0, positionQuantity: 0, totalFeesQuote: 0, version: 2 };
  render(); load();
  pending.shift().resolve(Response.json(account)); pending.shift().resolve(Response.json([account]));
  await flush();
  pending.shift().resolve(Response.json([])); pending.shift().resolve(Response.json([]));
  await flush();
  let tree = render();
  findElement(tree, (node) => node.type === "button" && node.props?.children?.includes?.("删除此会话")).props.onClick();
  tree = render();
  const deleting = findElement(tree, (node) => node.type === "button" && node.props?.children?.includes?.("确认删除")).props.onClick();
  pending.shift().resolve(Response.json({ error: "changed" }, { status: 409 }));
  await deleting;
  tree = render();
  assert.ok(findElement(tree, (node) => node.props?.className === "session-identity"));
  assert.ok(findElement(tree, (node) => node.props?.role === "alert" && node.props.children.includes("模拟会话已变化")));
  assert.ok(!findElement(tree, (node) => node.props?.["aria-label"] === "确认删除模拟会话"));
});

function resolveSnapshot(pending, marker) {
  const values = [
    { executionMode: "LIVE", marker }, { accepted: marker }, [{ id: `strategy-${marker}` }],
    [{ id: "trading", running: true, loops: [] }], [{ runId: `run-${marker}` }],
  ];
  pending.forEach((item, index) => item.resolve(Response.json(values[index])));
}

test("a partial initial refresh never labels demo balances or automation as live", async () => {
  const harness = await mountMarketRequests();
  const previous = harness.snapshot.value;
  const loading = harness.refresh(true);
  harness.pending[0].resolve(Response.json({ executionMode: "LIVE" }));
  harness.pending.slice(1).forEach((item) => item.reject(new Error("offline")));
  await loading;
  assert.equal(harness.mode.value, "demo");
  assert.equal(harness.connected.value, false);
  assert.equal(harness.snapshot.value, previous);
});

test("an outage keeps the last real snapshot and never returns live controls to demo mode", async () => {
  const harness = await mountMarketRequests();
  const loading = harness.refresh(true);
  resolveSnapshot(harness.pending, 1);
  await loading;
  const previous = harness.snapshot.value;
  assert.equal(harness.mode.value, "live");
  assert.equal(harness.connected.value, true);
  const retry = harness.refresh(true);
  harness.pending.slice(5).forEach((item) => item.reject(new Error("offline")));
  await retry;
  assert.equal(harness.snapshot.value, previous);
  assert.equal(harness.mode.value, "live");
  assert.equal(harness.connected.value, false);
});

test("a late refresh cannot overwrite newer runtime state", async () => {
  const harness = await mountMarketRequests();
  const old = harness.refresh(true);
  const next = harness.refresh(true);
  assert.equal(harness.pending[0].init.signal.aborted, true);
  resolveSnapshot(harness.pending.slice(5), 2);
  await next;
  resolveSnapshot(harness.pending.slice(0, 5), 1);
  await old;
  assert.equal(harness.snapshot.value.runtime.marker, 2);
});

test("a refresh started before a stop response cannot restore the old running state", async () => {
  const harness = await mountMarketRequests({ dataMode: "live", connected: true });
  const loading = harness.refresh(true);
  const toggle = findElement(harness.tree, (node) => node.props?.["aria-label"] === "停止交易自动化");
  const stopping = toggle.props.onClick();
  assert.match(harness.pending[5].url, /\/api\/automation\/tasks\/trading\/stop$/);
  harness.pending[5].resolve(Response.json({ id: "trading", running: false, loops: [] }));
  await stopping;
  resolveSnapshot(harness.pending.slice(0, 5), 1);
  await loading;
  assert.equal(harness.snapshot.value.tasks[0].running, false);
});

test("stopping during a disconnection still submits a real command", async () => {
  const harness = await mountMarketRequests({ dataMode: "live", connected: false });
  const toggle = findElement(harness.tree, (node) => node.props?.["aria-label"] === "停止交易自动化");
  assert.equal(toggle.props.disabled, false);
  const stopping = toggle.props.onClick();
  assert.match(harness.pending[0].url, /\/trading\/stop$/);
  harness.pending[0].resolve(Response.json({ id: "trading", running: false, loops: [] }));
  await stopping;
  assert.equal(harness.snapshot.value.tasks[0].running, false);
});

test("a disconnected snapshot cannot start trading automation", async () => {
  const harness = await mountMarketRequests({ dataMode: "live", connected: false, tasks: [{ id: "trading", running: false, loops: [] }] });
  const toggle = findElement(harness.tree, (node) => node.props?.["aria-label"] === "启动交易自动化");
  assert.equal(toggle.props.disabled, true);
  await toggle.props.onClick();
  assert.equal(harness.pending.length, 0);
  assert.equal(harness.snapshot.value.tasks[0].running, false);
});

test("missing trading automation cannot fall back to starting another task", async () => {
  const harness = await mountMarketRequests({ dataMode: "live", connected: true, tasks: [{ id: "x-posting", running: false, loops: [] }] });
  const toggle = findElement(harness.tree, (node) => node.props?.["aria-label"] === "启动交易自动化");
  assert.equal(toggle.props.disabled, true);
  await toggle.props.onClick();
  assert.equal(harness.pending.length, 0);
});

test("late candle requests cannot replace a newer interval", async () => {
  const harness = await mountMarketRequests();
  const old = harness.load("1m", new AbortController().signal);
  const latest = harness.load("5m", new AbortController().signal);
  harness.pending[1].resolve(Response.json({ bar: "5m", candles: [] }));
  assert.equal(await latest, true);
  harness.pending[0].resolve(Response.json({ bar: "1m", candles: [] }));
  await old;
  assert.equal(harness.market.value.bar, "5m");
});

test("current runtime instrument drives REST, SSE and backtest defaults", async () => {
  const harness = await mountMarketRequests({ runtime: { instId: "SOL-USDC", baseCcy: "SOL", quoteCcy: "USDC" } });
  const pending = harness.load("15m", new AbortController().signal);
  assert.match(harness.pending[0].url, /instId=SOL-USDC&bar=15m/);
  harness.pending[0].resolve(Response.json({ instrumentId: "SOL-USDC", bar: "15m", candles: [] }));
  await pending;
  const cleanup = harness.effect();
  assert.match(harness.sources[0].url, /instId=SOL-USDC&bar=1m/);
  harness.openBacktest();
  assert.equal(harness.backtestInstrument.value, "SOL-USDC");
  cleanup();
});

test("unknown strategy types render recorded parameters without enabling edits", async () => {
  const configuration = { strategyId: "future-strategy", type: "future-type", version: 1, params: { "future-threshold": "0.2" } };
  const harness = await mountMarketRequests({ configuration, history: [configuration] });
  assert.deepEqual(parameterFieldsForStrategy("future-type"), []);
  const tree = JSON.stringify(harness.renderEditor());
  assert.match(tree, /暂不支持编辑策略类型/);
  assert.match(tree, /future-threshold/);
  assert.doesNotMatch(tree, /configuration-form/);
  assert.throws(() => buildStrategyParametersPayload({ expectedVersion: 1, reason: "unsupported", type: configuration.type, params: configuration.params }), /暂不支持编辑/);
});

test("a first stream candle cannot carry previous instrument or interval history into the new series", async () => {
  const harness = await mountMarketRequests({ runtime: { instId: "ETH-USDT" } });
  assert.equal(harness.market.value.instrumentId, "BTC-USDT");
  assert.ok(harness.market.value.candles.length > 1);
  const cleanup = harness.effect();
  const ts = String(Math.floor(Date.now() / 60_000) * 60_000);
  const update = { instrumentId: "ETH-USDT", bar: "1m", receivedAt: new Date().toISOString(), candles: [{ ts, close: "3200" }] };
  // The first candle may arrive before either the stream snapshot or REST history.
  harness.sources[0].listeners.candle({ data: JSON.stringify(update) });
  assert.equal(harness.market.value.instrumentId, "ETH-USDT");
  assert.deepEqual(Array.from(harness.market.value.candles, (candle) => candle.close), ["3200"]);
  const changedBar = harness.mergeCandles(harness.market.value, { ...update, bar: "15m", candles: [{ ts: "1000", close: "3300" }] });
  assert.deepEqual(Array.from(changedBar.candles, (candle) => candle.close), ["3300"]);
  cleanup();
});

test("retired market transports cannot replace the current series with late snapshots or REST history", async () => {
  const harness = await mountMarketRequests();
  const previous = harness.market.value;
  const cleanup = harness.effect();
  harness.timers[0]();
  cleanup();
  harness.sources[0].listeners.snapshot({ data: JSON.stringify({ instrumentId: "BTC-USDT", bar: "1m", candles: [{ ts: "1000" }] }) });
  harness.pending[0].resolve(Response.json({ instrumentId: "BTC-USDT", bar: "1m", candles: [{ ts: "2000" }] }));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(harness.pending[0].init.signal.aborted, true);
  assert.equal(harness.market.value, previous);
});

test("changing intervals cancels transport and ignores its eventual failure", async () => {
  const harness = await mountMarketRequests();
  const previous = harness.market.value;
  const cleanup = harness.effect();
  harness.timers[0]();
  cleanup();
  assert.equal(harness.pending[0].init.signal.aborted, true);
  harness.pending[0].reject(new Error("late failure"));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(harness.market.value, previous);
  assert.equal(harness.stream.value, "connecting");
});

test("a REST response cannot roll back a newer candle stream snapshot", async () => {
  const harness = await mountMarketRequests();
  const cleanup = harness.effect();
  harness.timers[0]();
  harness.sources[0].listeners.snapshot({ data: JSON.stringify({ bar: "1m", source: "SSE", candles: [{ ts: "2000" }] }) });
  harness.pending[0].resolve(Response.json({ bar: "1m", source: "REST", candles: [{ ts: "1000" }] }));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(harness.market.value.source, "SSE");
  assert.deepEqual(Array.from(harness.market.value.candles, (candle) => candle.ts), ["1000", "2000"]);
  assert.equal(harness.stream.value, "live");
  cleanup();
});

test("a late HTTP failure cannot replace a working candle stream with demo data", async () => {
  const harness = await mountMarketRequests();
  const cleanup = harness.effect();
  harness.timers[0]();
  harness.sources[0].listeners.snapshot({ data: JSON.stringify({ bar: "1m", source: "SSE", candles: [{ ts: "2000" }] }) });
  harness.pending[0].reject(new Error("late HTTP failure"));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(harness.market.value.source, "SSE");
  assert.equal(harness.stream.value, "live");
  cleanup();
});

test("a connected SSE transport cannot make stale candle data fresh", async () => {
  const harness = await mountMarketRequests();
  const cleanup = harness.effect();
  harness.sources[0].onopen();
  harness.sources[0].listeners.snapshot({ data: JSON.stringify({ bar: "1m", source: "HOT_CACHE", candles: [{ ts: "1000" }] }) });
  assert.equal(harness.stream.value, "live");
  const now = Date.now();
  assert.equal(marketDataIsFresh(harness.market.value.candles, "1m", now, now), false);
  cleanup();
});

test("frequent SSE updates preserve REST corrections to earlier candles", async () => {
  const harness = await mountMarketRequests();
  const cleanup = harness.effect();
  harness.sources[0].listeners.snapshot({ data: JSON.stringify({ bar: "1m", candles: [{ ts: "1000", confirm: "0" }, { ts: "2000", close: "100" }] }) });
  const pending = harness.load("1m", new AbortController().signal);
  harness.sources[0].listeners.candle({ data: JSON.stringify({ bar: "1m", receivedAt: new Date().toISOString(), candles: [{ ts: "2000", close: "102" }] }) });
  harness.pending[0].resolve(Response.json({ bar: "1m", candles: [{ ts: "1000", confirm: "1" }, { ts: "2000", close: "101" }] }));
  await pending;
  assert.equal(harness.market.value.candles[0].confirm, "1");
  assert.equal(harness.market.value.candles[1].close, "102");
  cleanup();
});

test("a slow candle request is allowed to finish before another poll starts", async () => {
  const harness = await mountMarketRequests();
  const signal = new AbortController().signal;
  const pending = harness.load("1m", signal);
  assert.equal(await harness.load("1m", signal), null);
  assert.equal(harness.pending.length, 1);
  assert.equal(harness.pending[0].init.cache, "no-store");
  harness.pending[0].resolve(Response.json({ bar: "1m", candles: [{ ts: "1000" }] }));
  assert.equal(await pending, true);
  const next = harness.load("1m", signal);
  assert.equal(harness.pending.length, 2);
  harness.pending[1].resolve(Response.json({ bar: "1m", candles: [{ ts: "2000" }] }));
  await next;
  assert.equal(harness.market.value.candles[0].ts, "2000");
});

test("delayed SSE events cannot overwrite a newer displayed price", async () => {
  const harness = await mountMarketRequests();
  const cleanup = harness.effect();
  const now = Date.now();
  const ts = String(Math.floor(now / 60_000) * 60_000);
  harness.sources[0].listeners.snapshot({ data: JSON.stringify({ bar: "1m", candles: [{ ts, close: "102" }] }) });
  harness.sources[0].listeners.candle({ data: JSON.stringify({ bar: "1m", receivedAt: new Date(now - 40_000).toISOString(), candles: [{ ts, close: "99" }] }) });
  assert.equal(harness.market.value.candles[0].close, "102");
  cleanup();
});

test("builds the required backend backtest range from local input times", () => {
  const payload = buildBacktestPayload(request);
  assert.equal(payload.from, new Date(request.from).toISOString());
  assert.equal(payload.to, new Date(request.to).toISOString());
  assert.equal(payload.strategyId, request.strategyId);
  assert.equal(payload.instId, "BTC-USDT");
  assert.equal(payload.bar, "1m");
  assert.equal(payload.initialCash, 10000);
  assert.equal(payload.feeRate, 0.001);
  assert.equal(payload.slippageRate, 0.0002);
  assert.equal(payload.forceCloseAtEnd, true);
  assert.equal(payload.includeUnconfirmed, false);
  assert.equal(payload.maxCandles, 10000);
});

const params = {
  "price-move-trigger-percent": "0.02", "volume-spike-multiplier": "3",
  "floating-loss-trigger-percent": "0.10", "buy-quote-amount": "10",
  "sell-base-amount": "", "order-size": "0.001", "price-move-window-candles": "5",
  "volume-lookback-candles": "20", "require-confirmed-candle": true,
};

test("builds a full version-protected configuration and preserves nullable sell size", () => {
  const payload = buildStrategyParametersPayload({ expectedVersion: 4, reason: " 提高门槛 ", params: { ...params, unknown: "ignored" } });
  assert.equal(payload.expectedVersion, 4);
  assert.equal(payload.reason, "提高门槛");
  assert.equal(payload.params["sell-base-amount"], null);
  assert.equal(payload.params["require-confirmed-candle"], true);
  assert.equal(payload.params["price-move-window-candles"], 5);
  assert.equal(Object.hasOwn(payload.params, "unknown"), false);
});

test("preserves exact decimal strategy values beyond JavaScript floating point precision", () => {
  const exact = "0.123456789012345678";
  const payload = buildStrategyParametersPayload({ expectedVersion: 2, reason: "精确阈值", params: { ...params, "price-move-trigger-percent": ` ${exact} ` } });
  assert.equal(payload.params["price-move-trigger-percent"], exact);
});

test("rejects invalid revisions, missing fields and out-of-range strategy values", () => {
  const base = { expectedVersion: 1, reason: "调整", params };
  for (const expectedVersion of [-1, 0, 1.2, NaN]) assert.throws(() => buildStrategyParametersPayload({ ...base, expectedVersion }), /版本无效/);
  for (const value of ["", "NaN", "Infinity", "0", "-1"]) assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...params, "buy-quote-amount": value } }), /每次买入金额/);
  for (const value of ["1", "2.5", "1001"]) assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...params, "price-move-window-candles": value } }), /价格观察/);
  assert.throws(() => buildStrategyParametersPayload({ ...base, reason: " " }), /修改原因/);
  assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...params, "volume-spike-multiplier": undefined } }), /成交量放大/);
});

const intradayParams = {
  mode: "TREND_PULLBACK", "ema-fast-period": "9", "ema-slow-period": "21", "rsi-period": "14",
  "atr-period": "14", "adx-period": "14", "bollinger-period": "20", "bollinger-deviations": "2",
  "breakout-period": "20", "volume-period": "20", "trend-min-adx": "18", "range-max-adx": "20",
  "entry-rsi-min": "45", "entry-rsi-max": "68", "min-volume-ratio": "1", "min-atr-percent": "0.0015",
  "max-atr-percent": "0.03", "max-entry-extension-atr": "2.5", "stop-loss-percent": "0.006",
  "take-profit-percent": "0.018", "estimated-slippage-rate": "0.0002", "min-net-reward-percent": "0.003",
  "buy-quote-amount": "10",
};

test("intraday saves every backend parameter for each supported mode without threshold-only keys", () => {
  for (const mode of intradayModes) {
    const payload = buildStrategyParametersPayload({ expectedVersion: 3, type: "intraday-technical", reason: "日内候选参数", params: { ...intradayParams, mode: mode.value, "require-confirmed-candle": false } });
    assert.deepEqual(Object.keys(payload.params).sort(), Object.keys(intradayParams).sort());
    assert.equal(payload.params.mode, mode.value);
    assert.equal(payload.params["ema-fast-period"], 9);
    assert.equal(payload.params["stop-loss-percent"], "0.006");
    assert.equal(Object.hasOwn(payload.params, "require-confirmed-candle"), false);
    assert.equal(Object.hasOwn(payload.params, "price-move-trigger-percent"), false);
  }
  assert.equal(parameterFieldsForStrategy("intraday-technical").length, 22);
  assert.equal(parameterFieldsForStrategy("threshold-event").length, 8);
});

test("intraday preserves decimal text and permits only explicit zero slippage", () => {
  const exact = "0.123456789012345678";
  const base = { expectedVersion: 1, type: "intraday-technical", reason: "精确参数" };
  const payload = buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "stop-loss-percent": exact, "take-profit-percent": "0.2", "estimated-slippage-rate": "0" } });
  assert.equal(payload.params["stop-loss-percent"], exact);
  assert.equal(payload.params["estimated-slippage-rate"], "0");
  for (const key of ["min-atr-percent", "entry-rsi-min", "trend-min-adx", "min-net-reward-percent"]) {
    assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, [key]: "0" } }), /无效/);
  }
  for (const mode of ["", undefined, "UNKNOWN", "trend_pullback"]) {
    assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, mode } }), /模式/);
  }
  assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "ema-slow-period": "101" } }), /慢 EMA/);
  assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "bollinger-deviations": undefined } }), /布林带/);
  assert.throws(() => buildStrategyParametersPayload({ ...base, type: "unknown", params: intradayParams }), /暂不支持编辑/);
});

test("intraday rejects contradictory EMA, RSI, ATR and stop/target relationships", () => {
  const base = { expectedVersion: 1, type: "intraday-technical", reason: "关系校验", params: intradayParams };
  for (const [changes, message] of [
    [{ "ema-fast-period": "21" }, /快 EMA/],
    [{ "ema-fast-period": "22" }, /快 EMA/],
    [{ "entry-rsi-min": "68" }, /RSI 下限/],
    [{ "entry-rsi-max": "44" }, /RSI 下限/],
    [{ "min-atr-percent": "0.03" }, /最低 ATR/],
    [{ "max-atr-percent": "0.001" }, /最低 ATR/],
    [{ "take-profit-percent": "0.006" }, /止盈/],
    [{ "stop-loss-percent": "0.019" }, /止盈/],
  ]) assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, ...changes } }), message);
  const exact = buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "entry-rsi-min": "45.000000000000000001", "entry-rsi-max": "45.000000000000000002" } });
  assert.equal(exact.params["entry-rsi-min"], "45.000000000000000001");
});

test("decimal validation rejects JavaScript coercions and backend-incompatible precision", () => {
  const base = { expectedVersion: 1, type: "intraday-technical", reason: "数值校验" };
  for (const value of [true, false, null, {}, [1], "0x10", "0b10", "1e-19", "1.0000000000000000000"]) {
    assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "min-volume-ratio": value } }), /最低量比无效/);
  }
  assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "estimated-slippage-rate": "0.100000000000000001" } }), /单边预估滑点无效/);
  for (const value of ["14.000000000000000001", "13.999999999999999999", "14000000000000000001e-18"]) {
    assert.throws(() => buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "rsi-period": value } }), /RSI 周期无效/);
  }
  for (const value of ["14.000000000000000000", "1.4e1", "14e0"]) {
    assert.equal(buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "rsi-period": value } }).params["rsi-period"], 14);
  }
});

test("backtest selections inherit the selected strategy bar", () => {
  assert.deepEqual(backtestSelectionForStrategy({ id: "intraday-pullback-15m", bar: "15m" }), { strategyId: "intraday-pullback-15m", bar: "15m" });
  assert.deepEqual(backtestSelectionForStrategy({ id: "threshold-event-defensive", bar: "5m" }), { strategyId: "threshold-event-defensive", bar: "5m" });
  assert.deepEqual(backtestSelectionForStrategy(undefined), { strategyId: "", bar: "1m" });
});

test("recorded technical indicators distinguish score units and fractional percentages", () => {
  const entries = decisionIndicatorEntries({ emaFast: 100, rsi: 62, adx: 18, atrPercent: 0.0015,
    volumeRatio: 1.2, stopPrice: "98.5", plannedNetRewardPercent: 0.003, unknown: 25,
    atr: Infinity, bollingerUpper: null, bollingerLower: "invalid" });
  const values = Object.fromEntries(entries.map((entry) => [entry.key, entry.value]));
  assert.deepEqual(values, { emaFast: "100", rsi: "62", atrPercent: "0.15%", adx: "18", volumeRatio: "1.2", stopPrice: "98.5", plannedNetRewardPercent: "0.30%" });
  assert.equal(entries.find((entry) => entry.key === "rsi").label, "RSI 分数");
  assert.deepEqual(decisionIndicatorEntries(null), []);
  assert.deepEqual(decisionIndicatorEntries({}), []);
});

test("new simulation sessions require valid funds and a concurrency revision", () => {
  assert.deepEqual(buildPaperSessionPayload({ initialCash: "25000.50", expectedSessionId: "trial-1", expectedVersion: 3 }), { initialCash: "25000.50", expectedSessionId: "trial-1", expectedVersion: 3 });
  assert.deepEqual(buildPaperSessionPayload({ initialCash: 100, expectedSessionId: null, expectedVersion: null }), { initialCash: "100", expectedSessionId: null, expectedVersion: null });
  assert.equal(buildPaperSessionPayload({ initialCash: "25000.12345678", expectedSessionId: null, expectedVersion: null }).initialCash, "25000.12345678");
  for (const initialCash of ["", " ", "0", "-1", "Infinity", NaN, "0.000000001", "1000000000001"]) assert.throws(() => buildPaperSessionPayload({ initialCash, expectedSessionId: null, expectedVersion: null }), /初始资金/);
  assert.throws(() => buildPaperSessionPayload({ initialCash: 100, expectedSessionId: "trial-1", expectedVersion: undefined }), /版本无效/);
});

test("preserves exact large funds and validates the unrounded decimal precision and limit", () => {
  const base = { expectedSessionId: null, expectedVersion: null };
  const exact = "999999999999.12345678";
  assert.equal(buildPaperSessionPayload({ ...base, initialCash: ` ${exact} ` }).initialCash, exact);
  assert.equal(buildPaperSessionPayload({ ...base, initialCash: "1.000000000" }).initialCash, "1");
  assert.equal(buildPaperSessionPayload({ ...base, initialCash: "1e-8" }).initialCash, "1e-8");
  for (const initialCash of ["999999999999.123456789", "1000000000000.00000001", "1e-9", "0.000000001000000000"]) {
    assert.throws(() => buildPaperSessionPayload({ ...base, initialCash }), /初始资金/);
  }
});

test("version filters do not attribute inherited cumulative equity to the version", () => {
  const records = [{ strategyId: "alpha", strategyConfigVersion: 1, equity: 10000 }, { strategyId: "alpha", strategyConfigVersion: 2, equity: 11000 }, { strategyId: "beta", strategyConfigVersion: 2, equity: 11500 }];
  assert.deepEqual(filterConfigurationEvents(records, "alpha@2"), [records[1]]);
  assert.equal(filterConfigurationEvents(records, ""), records);
  assert.equal(formatReturnFraction(1.25), "125.00%");
  assert.equal(formatReturnFraction(0.01), "1.00%");
  assert.equal(formatReturnFraction(null), "—");
});

test("disjoint full history refreshes retain a cursor that can recover the interior gap", () => {
  const cached = Array.from({ length: 100 }, (_, index) => ({ id: 200 - index }));
  const fresh = Array.from({ length: 100 }, (_, index) => ({ id: 400 - index }));
  const refreshed = mergeContiguousHistory(fresh, cached);
  assert.equal(refreshed.length, 100);
  assert.equal(Math.min(...refreshed.map((item) => item.id)), 301);
  // Paging before 301 retrieves the previously unseen 300..201, rather than skipping it.
  const olderPage = Array.from({ length: 100 }, (_, index) => ({ id: 300 - index }));
  assert.equal(olderPage[0].id, refreshed.at(-1).id - 1);
});

test("overlapping history refreshes preserve previously loaded older rows without duplicate IDs", () => {
  const cached = Array.from({ length: 150 }, (_, index) => ({ id: 200 - index, label: "cached" }));
  const fresh = Array.from({ length: 100 }, (_, index) => ({ id: 250 - index, label: "fresh" }));
  const refreshed = mergeContiguousHistory(fresh, cached);
  assert.equal(refreshed.length, 200);
  assert.deepEqual(refreshed.map((item) => item.id), Array.from({ length: 200 }, (_, index) => 250 - index));
  assert.equal(refreshed.find((item) => item.id === 200).label, "fresh");
});

test("rejects a missing or invalid backtest range before sending a request", () => {
  assert.throws(() => buildBacktestPayload({ ...request, from: "" }), /请选择回测开始和结束时间/);
  assert.throws(() => buildBacktestPayload({ ...request, to: "invalid" }), /回测时间格式无效/);
  assert.throws(() => buildBacktestPayload({ ...request, to: request.from }), /开始时间必须早于结束时间/);
  assert.throws(() => buildBacktestPayload({ ...request, from: request.to, to: request.from }), /开始时间必须早于结束时间/);
});

test("rejects missing strategy selection and nonpositive or nonfinite funds", () => {
  for (const key of ["strategyId", "instId", "bar"]) {
    assert.throws(() => buildBacktestPayload({ ...request, [key]: " " }), /请选择策略/);
  }
  for (const initialCash of ["", "0", "-100", "invalid", Infinity, NaN]) {
    assert.throws(() => buildBacktestPayload({ ...request, initialCash }), /初始资金必须大于 0/);
  }
});

test("formats datetime-local defaults in the browser's local timezone", () => {
  const local = new Date(2026, 9, 17, 8, 5, 59);
  assert.equal(formatDateTimeLocal(local), "2026-10-17T08:05");
  assert.equal(formatDateTimeLocal(local.getTime()), "2026-10-17T08:05");
  assert.throws(() => formatDateTimeLocal(new Date(NaN)), /时间格式无效/);
});

test("normalizes the deployed Spring Instant epoch seconds and ISO snapshots", () => {
  const seconds = 1792215008.733182288;
  const expected = seconds * 1000;
  assert.equal(apiTimestampMillis(seconds), expected);
  assert.equal(apiTimestampMillis(1792213200000), 1792213200000);
  assert.equal(apiTimestampMillis("2026-10-17T00:00:00Z"), Date.parse("2026-10-17T00:00:00Z"));
  assert.equal(apiTimestampMillis(new Date("2026-10-17T00:00:00Z")), Date.parse("2026-10-17T00:00:00Z"));
});

test("keeps absent and malformed runtime timestamps unavailable", () => {
  for (const value of [undefined, null, "", "invalid", NaN, Infinity, new Date(NaN)]) {
    assert.equal(apiTimestampMillis(value), null);
  }
  assert.equal(apiTimestampMillis(0), 0);
});

test("builds JSON request headers without application credentials", () => {
  const normal = createJsonRequestHeaders();
  assert.equal(normal.get("Content-Type"), "application/json");
  assert.equal(normal.has("X-Trading-Operator-Token"), false);
  assert.equal(normal.has("Authorization"), false);
});

test("preserves explicit headers without mutating the caller's Headers object", () => {
  const original = new Headers({ "Content-Type": "application/problem+json", "X-Request-Id": "test-id" });
  const result = createJsonRequestHeaders({ headers: original });
  assert.equal(result.get("Content-Type"), "application/problem+json");
  assert.equal(result.get("X-Request-Id"), "test-id");
  assert.equal(result.has("X-Trading-Operator-Token"), false);
  assert.equal(original.has("X-Trading-Operator-Token"), false);
});

test("marks every management mutation with the fixed CSRF header while leaving reads unchanged", () => {
  for (const method of ["POST", "put", "PATCH", "DELETE"]) {
    const original = new Headers({ "X-Request-Id": "test-id" });
    const result = createJsonRequestHeaders({ headers: original, method });
    assert.equal(result.get("X-Trade-CSRF"), "1");
    assert.equal(result.get("X-Request-Id"), "test-id");
    assert.equal(original.has("X-Trade-CSRF"), false);
  }
  for (const method of ["GET", "HEAD", "OPTIONS"]) {
    assert.equal(createJsonRequestHeaders({ method }).has("X-Trade-CSRF"), false);
  }
});
