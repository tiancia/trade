import assert from "node:assert/strict";
import test from "node:test";
import {
  apiTimestampMillis,
  buildBacktestPayload,
  createJsonRequestHeaders,
  formatDateTimeLocal,
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

test("includes the operator credential only when explicitly supplied by the caller", () => {
  const normal = createJsonRequestHeaders();
  assert.equal(normal.get("Content-Type"), "application/json");
  assert.equal(normal.has("X-Trading-Operator-Token"), false);
  assert.equal(createJsonRequestHeaders({ operatorToken: " " }).has("X-Trading-Operator-Token"), false);

  const mutation = createJsonRequestHeaders({ operatorToken: " test-operator-token " });
  assert.equal(mutation.get("X-Trading-Operator-Token"), "test-operator-token");
  assert.equal(mutation.has("Authorization"), false);
});

test("preserves explicit headers without mutating the caller's Headers object", () => {
  const original = new Headers({ "Content-Type": "application/problem+json", "X-Request-Id": "test-id" });
  const result = createJsonRequestHeaders({ headers: original, operatorToken: "test-token" });
  assert.equal(result.get("Content-Type"), "application/problem+json");
  assert.equal(result.get("X-Request-Id"), "test-id");
  assert.equal(result.get("X-Trading-Operator-Token"), "test-token");
  assert.equal(original.has("X-Trading-Operator-Token"), false);
});
