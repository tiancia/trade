import assert from "node:assert/strict";
import test from "node:test";
import { createJsonRequestHeaders, formatTradingTimestamp } from "../src/api.mjs";

test("module mutations carry CSRF without modifying caller headers", () => {
  const original = new Headers({ Authorization: "Basic offline-test" });
  const headers = createJsonRequestHeaders({ method: "PUT", headers: original });
  assert.equal(headers.get("X-Trade-CSRF"), "1");
  assert.equal(headers.get("Authorization"), "Basic offline-test");
  assert.equal(original.has("X-Trade-CSRF"), false);
  assert.equal(createJsonRequestHeaders().has("X-Trade-CSRF"), false);
});

test("backend epoch seconds and browser milliseconds display the same time", () => {
  const iso = "2026-10-31T02:00:00Z", millis = Date.parse(iso);
  assert.equal(formatTradingTimestamp(iso), formatTradingTimestamp(millis / 1000));
  assert.equal(formatTradingTimestamp(iso), formatTradingTimestamp(millis));
  assert.match(formatTradingTimestamp(iso), /10:00:00/);
  assert.equal(formatTradingTimestamp(null), "暂无记录");
  assert.equal(formatTradingTimestamp("invalid"), "时间无效");
});
