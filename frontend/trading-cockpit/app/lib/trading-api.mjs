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
 * Only requests whose caller supplies an operator token receive that credential.
 * @param {{headers?: HeadersInit, operatorToken?: string}} [options]
 * @returns {Headers}
 */
export function createJsonRequestHeaders({ headers, operatorToken } = {}) {
  const result = new Headers(headers);
  if (!result.has("Content-Type")) result.set("Content-Type", "application/json");
  if (operatorToken?.trim()) result.set("X-Trading-Operator-Token", operatorToken.trim());
  return result;
}
