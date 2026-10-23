import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import ts from "typescript";
import * as tradingApi from "../app/lib/trading-api.mjs";
import {
  apiTimestampMillis,
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
async function mountMarketRequests() {
  const callbacks = [];
  const effects = [];
  const states = [];
  const pending = [];
  const timers = [];
  const sources = [];
  const hooks = {
    useState(initial) {
      const state = { value: typeof initial === "function" ? initial() : initial };
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
  const compiled = ts.transpileModule(source, {
    fileName: "page.tsx",
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  vm.runInNewContext(compiled, {
    exports, process: { env: {} }, URL, Headers, AbortController,
    window: { setTimeout: (callback) => timers.push(callback), clearTimeout() {}, setInterval() {}, clearInterval() {} },
    EventSource: class {
      listeners = {};
      constructor() { sources.push(this); }
      addEventListener(name, callback) { this.listeners[name] = callback; }
      close() {}
    },
    fetch(url, init) { return new Promise((resolve, reject) => pending.push({ url, init, resolve, reject })); },
    require(name) {
      if (name === "react") return hooks;
      if (name === "react/jsx-runtime") return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) };
      if (name === "lucide-react") return {};
      if (name === "./lib/trading-api.mjs") return tradingApi;
      throw new Error(`Unexpected import: ${name}`);
    },
  });
  exports.default();
  return {
    load: callbacks.find((callback) => callback.toString().includes("/api/trading/market/candles?")),
    effect: effects.find((effect) => effect.dependencies[0] === "1m").callback,
    market: states.find((state) => state.value?.candles),
    stream: states.find((state) => state.value === "connecting"),
    pending, timers, sources,
  };
}

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
  const payload = buildStrategyParametersPayload({ ...base, params: { ...intradayParams, "stop-loss-percent": exact, "estimated-slippage-rate": "0" } });
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
