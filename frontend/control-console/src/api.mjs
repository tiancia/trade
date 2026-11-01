/** @param {{headers?: HeadersInit, method?: string}} [options] */
export function createJsonRequestHeaders({ headers, method = "GET" } = {}) {
  const result = new Headers(headers);
  if (!result.has("Content-Type")) result.set("Content-Type", "application/json");
  if (["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase())) result.set("X-Trade-CSRF", "1");
  return result;
}

/** Spring Instant responses may use epoch seconds; browser timestamps use milliseconds.
 * @param {string | number | null | undefined} value
 * @param {string} zone
 */
export function formatTradingTimestamp(value, zone = "Asia/Shanghai") {
  if (value == null || value === "") return "暂无记录";
  const milliseconds = typeof value === "number"
    ? Math.abs(value) < 100_000_000_000 ? value * 1000 : value
    : Date.parse(value);
  if (!Number.isFinite(milliseconds)) return "时间无效";
  return new Date(milliseconds).toLocaleString("zh-CN", { timeZone: zone, hour12: false });
}
