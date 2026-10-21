import assert from "node:assert/strict";
import test from "node:test";
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
} from "../app/lib/trading-api.mjs";

const request = {
  strategyId: "threshold-event-default",
  instId: "BTC-USDT",
  bar: "1m",
  initialCash: "10000",
  from: "2026-10-16T08:00",
  to: "2026-10-17T08:00",
};

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
