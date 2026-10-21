const EPOCH_MILLISECONDS_THRESHOLD = 100_000_000_000;

/**
 * Spring's Instant values arrive as epoch seconds; demo snapshots use ISO text.
 * @param {string | number | Date | null | undefined} value
 * @returns {number | null}
 */
export function apiTimestampMillis(value) {
  if (value == null || value === "") return null;
  if (value instanceof Date) {
    const milliseconds = value.getTime();
    return Number.isFinite(milliseconds) ? milliseconds : null;
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) return null;
    return Math.abs(value) < EPOCH_MILLISECONDS_THRESHOLD ? value * 1000 : value;
  }
  const milliseconds = Date.parse(value);
  return Number.isFinite(milliseconds) ? milliseconds : null;
}

/**
 * Preserve the user's local timezone when filling a datetime-local input.
 * @param {Date | number} value A Date or epoch milliseconds.
 * @returns {string}
 */
export function formatDateTimeLocal(value) {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error("时间格式无效");
  const pad = (/** @type {number} */ part) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * @param {{strategyId: string, instId: string, bar: string, initialCash: string | number, from: string, to: string}} values
 */
export function buildBacktestPayload(values) {
  const strategyId = values.strategyId.trim();
  const instId = values.instId.trim();
  const bar = values.bar.trim();
  if (!strategyId || !instId || !bar) throw new Error("请选择策略、交易标的和 K 线周期");
  if (!values.from.trim() || !values.to.trim()) throw new Error("请选择回测开始和结束时间");

  const from = new Date(values.from);
  const to = new Date(values.to);
  if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime())) {
    throw new Error("回测时间格式无效");
  }
  if (from.getTime() >= to.getTime()) throw new Error("回测开始时间必须早于结束时间");

  const initialCash = Number(values.initialCash);
  if (!Number.isFinite(initialCash) || initialCash <= 0) throw new Error("初始资金必须大于 0");

  return {
    strategyId,
    instId,
    bar,
    from: from.toISOString(),
    to: to.toISOString(),
    initialCash,
    feeRate: 0.001,
    slippageRate: 0.0002,
    forceCloseAtEnd: true,
    includeUnconfirmed: false,
    maxCandles: 10_000,
  };
}

/**
 * Build JSON headers while preserving caller-provided headers.
 * @param {{headers?: HeadersInit, method?: string}} [options]
 * @returns {Headers}
 */
export function createJsonRequestHeaders({ headers, method = "GET" } = {}) {
  const result = new Headers(headers);
  if (!result.has("Content-Type")) result.set("Content-Type", "application/json");
  if (["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase())) result.set("X-Trade-CSRF", "1");
  return result;
}

/** Parameter labels and bounds mirror the threshold-event strategy contract. */
export const strategyParameterFields = [
  { key: "price-move-trigger-percent", label: "价格变化触发比例", hint: "0.02 表示 2%", min: 0, max: 1, step: "any" },
  { key: "volume-spike-multiplier", label: "成交量放大倍数", hint: "当前量 / 历史均量", min: 0, max: 10000, step: "any" },
  { key: "floating-loss-trigger-percent", label: "浮亏触发比例", hint: "0.10 表示 10%", min: 0, max: 1, step: "any" },
  { key: "buy-quote-amount", label: "每次买入金额（计价币）", hint: "仍受余额与统一风控约束", min: 0, max: 1e15, step: "any" },
  { key: "sell-base-amount", label: "每次卖出数量（基础币）", hint: "留空时按策略默认数量", min: 0, max: 1e15, step: "any", nullable: true },
  { key: "order-size", label: "默认下单数量", hint: "未指定买卖数量时使用", min: 0, max: 1e15, step: "any" },
  { key: "price-move-window-candles", label: "价格观察 K 线数", hint: "2–1000 根", min: 2, max: 1000, step: "1", integer: true },
  { key: "volume-lookback-candles", label: "成交量观察 K 线数", hint: "1–1000 根", min: 1, max: 1000, step: "1", integer: true },
];

/** Validate values before a compare-and-swap configuration request. */
export function buildStrategyParametersPayload({ expectedVersion, params, reason }) {
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 1) {
    throw new Error("配置版本无效，请刷新后重试");
  }
  const result = {};
  for (const field of strategyParameterFields) {
    const raw = params[field.key];
    if (field.nullable && (raw == null || String(raw).trim() === "")) { result[field.key] = null; continue; }
    const value = typeof raw === "string" && raw.trim() === "" ? NaN : Number(raw);
    if (!Number.isFinite(value) || value < field.min || (!field.integer && value === 0) || (field.max != null && value > field.max) || (field.integer && !Number.isInteger(value))) {
      throw new Error(`${field.label}无效${field.integer ? "，请输入允许范围内的正整数" : "，请输入允许范围内大于 0 的数字"}`);
    }
    // Keep the user's decimal text intact; the server validates it as BigDecimal.
    result[field.key] = field.integer ? value : String(raw).trim();
  }
  const confirmed = params["require-confirmed-candle"];
  if (confirmed != null) {
    if (![true, false, "true", "false"].includes(confirmed)) throw new Error("收盘 K 线选项无效");
    result["require-confirmed-candle"] = confirmed === true || confirmed === "true";
  }
  if (confirmed == null) throw new Error("请选择是否仅使用收盘 K 线");
  if (typeof reason !== "string" || !reason.trim()) throw new Error("请填写修改原因，便于区分版本");
  if (reason.trim().length > 1000) throw new Error("修改原因不能超过 1000 字");
  return { expectedVersion, reason: reason.trim(), params: result };
}

/** Creating a session preserves the previous session rather than resetting its balance. */
export function buildPaperSessionPayload({ initialCash, expectedSessionId, expectedVersion }) {
  const raw = String(initialCash).trim();
  const value = Number(raw);
  const parts = /^\+?(\d+(?:\.\d*)?|\.\d+)(?:[eE]([+-]?\d+))?$/.exec(raw);
  const invalidFunds = () => new Error("初始资金须大于 0、不超过 1 万亿且最多 8 位小数");
  if (!parts || !Number.isFinite(value) || value <= 0 || value > 1e12) throw invalidFunds();
  const [whole, fraction = ""] = parts[1].split(".");
  let digits = `${whole}${fraction}`.replace(/^0+/, "");
  const originalScale = fraction.length - Number(parts[2] ?? 0);
  let scale = originalScale;
  while (scale > 0 && digits.endsWith("0")) { digits = digits.slice(0, -1); scale -= 1; }
  if (scale > 8) throw invalidFunds();
  const plain = scale <= 0 ? digits + "0".repeat(-scale)
    : digits.length <= scale ? `0.${"0".repeat(scale - digits.length)}${digits}`
      : `${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
  const [integerPart, fractionalPart = ""] = plain.split(".");
  if (integerPart.length > 13 || (integerPart.length === 13 && (integerPart > "1000000000000" || (integerPart === "1000000000000" && /[1-9]/.test(fractionalPart))))) throw invalidFunds();
  // JSON strings reach the backend BigDecimal parser without a floating point round trip.
  const exactAmount = originalScale > 8 ? plain : raw;
  if (expectedSessionId == null && expectedVersion == null) return { initialCash: exactAmount, expectedSessionId: null, expectedVersion: null };
  if (typeof expectedSessionId !== "string" || !expectedSessionId || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) throw new Error("模拟账户版本无效，请刷新后重试");
  return { initialCash: exactAmount, expectedSessionId, expectedVersion };
}

/** Never guess percentage units from the magnitude of a return. */
export function formatReturnFraction(value, digits = 2) {
  return typeof value === "number" && Number.isFinite(value) ? `${(value * 100).toFixed(digits)}%` : "—";
}

/** A version filter only selects recorded events; it does not recompute strategy returns. */
export function filterConfigurationEvents(items, selectedVersion) {
  if (!selectedVersion) return items;
  return items.filter((item) => `${item.strategyId}@${item.strategyConfigVersion}` === selectedVersion);
}

/** Keep a contiguous cursor window; disjoint full refreshes must be paged back from their own oldest row. */
export function mergeContiguousHistory(fresh, cached, pageSize = 100) {
  const freshIds = new Set(fresh.map((item) => item.id));
  const overlaps = cached.some((item) => freshIds.has(item.id));
  const previous = fresh.length >= pageSize && !overlaps ? [] : cached;
  return [...fresh, ...previous.filter((item) => !freshIds.has(item.id))].sort((left, right) => right.id - left.id);
}
