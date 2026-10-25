const EPOCH_MILLISECONDS_THRESHOLD = 100_000_000_000;

/**
 * A partial refresh must never promote demo funds, tasks or strategies to live data.
 * Once connected, an outage preserves the last real snapshot and its live controls.
 * @template T
 * @param {{snapshot: T, dataMode: "live" | "demo"}} previous
 * @param {PromiseSettledResult<unknown>[]} endpoints
 * @returns {{snapshot: T, dataMode: "live" | "demo", connected: boolean, successful: number}}
 */
export function reconcileCockpitRefresh(previous, endpoints) {
  const keys = ["runtime", "events", "strategies", "tasks", "backtests"];
  const successful = endpoints.filter((item) => item.status === "fulfilled").length;
  if (endpoints.length !== keys.length || successful !== keys.length) {
    return { ...previous, connected: false, successful };
  }
  const snapshot = /** @type {T} */ (Object.fromEntries(keys.map((key, index) => {
    const item = endpoints[index];
    return [key, item.status === "fulfilled" ? item.value : undefined];
  })));
  return { snapshot, dataMode: "live", connected: true, successful };
}

/**
 * A connected stream is healthy only while current-period candles keep arriving.
 * @param {{ts: string}[]} candles
 * @param {string} bar
 * @param {number} receivedAt Local time of the latest successful observation.
 * @param {number} now
 */
export function marketDataIsFresh(candles, bar, receivedAt, now) {
  const interval = { "1m": 60_000, "5m": 300_000, "15m": 900_000, "1h": 3_600_000, "4h": 14_400_000 }[bar.toLowerCase()];
  if (!interval || !Number.isFinite(now) || receivedAt <= 0 || receivedAt > now + 1_000 || now - receivedAt > 30_000) return false;
  const latest = Math.max(0, ...candles.map((candle) => Number(candle.ts)).filter(Number.isFinite));
  return latest > 0 && latest <= now && Math.floor(latest / interval) === Math.floor(now / interval);
}

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

/** @typedef {{key: string, label: string, hint: string, min: number, max: number, step: string, integer?: boolean, nullable?: boolean, allowZero?: boolean}} ParameterField */
/** Parameter labels and bounds mirror the threshold-event strategy contract.
 * @type {ParameterField[]}
 */
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

export const intradayModes = [
  { value: "TREND_PULLBACK", label: "趋势回踩", description: "上升趋势中，回踩后重新收上快 EMA，并由 RSI 回升确认。" },
  { value: "VOLUME_BREAKOUT", label: "放量突破", description: "趋势与量能确认后，收盘价突破此前窗口的最高价。" },
  { value: "RANGE_REVERSION", label: "区间回归", description: "低 ADX 区间中，价格从布林下轨外收回，向中轨回归。" },
];

/** @type {ParameterField[]} */
export const intradayParameterFields = [
  { key: "ema-fast-period", label: "快 EMA 周期", hint: "2–100 根，须小于慢 EMA", min: 2, max: 100, step: "1", integer: true },
  { key: "ema-slow-period", label: "慢 EMA 周期", hint: "2–100 根，预热至少取此周期的 3 倍", min: 2, max: 100, step: "1", integer: true },
  { key: "rsi-period", label: "RSI 周期", hint: "Wilder 平滑，2–100 根", min: 2, max: 100, step: "1", integer: true },
  { key: "atr-period", label: "ATR 周期", hint: "Wilder 平滑，2–100 根", min: 2, max: 100, step: "1", integer: true },
  { key: "adx-period", label: "ADX 周期", hint: "Wilder 趋势强度，2–100 根", min: 2, max: 100, step: "1", integer: true },
  { key: "bollinger-period", label: "布林带周期", hint: "2–100 根，总体标准差", min: 2, max: 100, step: "1", integer: true },
  { key: "bollinger-deviations", label: "布林带标准差倍数", hint: "例如 2 表示上下各 2 倍标准差", min: 0, max: 5, step: "any" },
  { key: "breakout-period", label: "突破观察周期", hint: "此前 2–100 根最高价，排除当前 K 线", min: 2, max: 100, step: "1", integer: true },
  { key: "volume-period", label: "量能基准周期", hint: "此前 2–100 根均量，排除当前 K 线", min: 2, max: 100, step: "1", integer: true },
  { key: "trend-min-adx", label: "趋势最低 ADX", hint: "大于 0、至多 100 分，例如 18；不是 18%", min: 0, max: 100, step: "any" },
  { key: "range-max-adx", label: "区间最高 ADX", hint: "大于 0、至多 100 分，例如 20；不是百分比", min: 0, max: 100, step: "any" },
  { key: "entry-rsi-min", label: "入场 RSI 下限", hint: "大于 0、至多 100 分，须小于 RSI 上限", min: 0, max: 100, step: "any" },
  { key: "entry-rsi-max", label: "入场 RSI 上限", hint: "大于 0、至多 100 分，例如 68；不是 68%", min: 0, max: 100, step: "any" },
  { key: "min-volume-ratio", label: "最低量比", hint: "当前量 / 此前均量，例如 1.2 表示 1.2 倍", min: 0, max: 100, step: "any" },
  { key: "min-atr-percent", label: "最低 ATR 比例", hint: "ATR / 收盘价；0.0015 表示 0.15%", min: 0, max: 0.5, step: "any" },
  { key: "max-atr-percent", label: "最高 ATR 比例", hint: "0.03 表示 3%，须大于最低 ATR 比例", min: 0, max: 0.5, step: "any" },
  { key: "max-entry-extension-atr", label: "入场偏离快 EMA 上限", hint: "以 ATR 倍数计，例如 2.5 倍；趋势模式适用", min: 0, max: 100, step: "any" },
  { key: "stop-loss-percent", label: "止损比例", hint: "0.006 表示 0.6%，按已收盘 K 线检查", min: 0, max: 0.5, step: "any" },
  { key: "take-profit-percent", label: "止盈比例", hint: "0.018 表示 1.8%，须大于止损比例", min: 0, max: 0.5, step: "any" },
  { key: "estimated-slippage-rate", label: "单边预估滑点", hint: "0.0002 表示 0.02%；可显式填 0", min: 0, max: 0.1, step: "any", allowZero: true },
  { key: "min-net-reward-percent", label: "最低预期净收益比例", hint: "扣除双边手续费与滑点后，0.003 表示 0.3%", min: 0, max: 0.5, step: "any" },
  { key: "buy-quote-amount", label: "每次买入金额（计价币）", hint: "仅支持现货做多；仍受余额与统一风控约束", min: 0, max: 1e15, step: "any" },
];

/** Unknown strategy types must not accidentally submit the threshold schema. */
export function parameterFieldsForStrategy(type = "threshold-event") {
  if (type === "threshold-event") return strategyParameterFields;
  if (type === "intraday-technical") return intradayParameterFields;
  return [];
}

// Compare the same decimal text sent to BigDecimal, including differences below Number precision.
function decimalUnits(text) {
  const [coefficient, exponent = "0"] = String(text).toLowerCase().split("e");
  const [whole, fraction = ""] = coefficient.replace(/^\+/, "").split(".");
  const scale = fraction.length - Number(exponent);
  const digits = BigInt(`${whole || "0"}${fraction}`);
  return digits === 0n ? 0n : digits * 10n ** BigInt(18 - scale);
}

/** @param {{id: string, bar: string} | undefined} strategy */
export function backtestSelectionForStrategy(strategy) {
  return { strategyId: strategy?.id ?? "", bar: strategy?.bar ?? "1m" };
}

/** Format only recognized recorded indicators; fractional percentages have explicit units.
 * @param {Record<string, unknown> | null | undefined} metadata
 */
export function decisionIndicatorEntries(metadata) {
  if (!metadata || typeof metadata !== "object") return [];
  /** @type {[string, string, boolean?][]} */
  const fields = [
    ["close", "信号收盘价"], ["emaFast", "快 EMA"], ["emaSlow", "慢 EMA"],
    ["rsi", "RSI 分数"], ["atr", "ATR"], ["atrPercent", "ATR / 收盘价", true],
    ["adx", "ADX 分数"], ["bollingerMiddle", "布林中轨"], ["bollingerUpper", "布林上轨"],
    ["bollingerLower", "布林下轨"], ["donchianHigh", "此前窗口最高价"], ["volumeRatio", "量比"],
    ["stopPrice", "收盘止损参考价"], ["takeProfitPrice", "收盘止盈参考价"],
    ["positionReturnPercent", "持仓收益", true], ["estimatedRoundTripCostPercent", "预估双边成本", true],
    ["plannedNetRewardPercent", "计划净收益", true], ["plannedNetRewardRiskRatio", "计划净盈亏比"],
  ];
  return fields.flatMap(([key, label, fraction]) => {
    const raw = metadata[key];
    if ((typeof raw !== "number" && typeof raw !== "string") || raw === "") return [];
    const value = Number(raw);
    if (!Number.isFinite(value)) return [];
    return [{ key, label, value: fraction ? formatReturnFraction(value) : value.toLocaleString("zh-CN", { maximumFractionDigits: 6 }) }];
  });
}

/** Validate values before a compare-and-swap configuration request. */
export function buildStrategyParametersPayload({ expectedVersion, params, reason, type = "threshold-event" }) {
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 1) {
    throw new Error("配置版本无效，请刷新后重试");
  }
  const result = {};
  const fields = parameterFieldsForStrategy(type);
  if (!fields.length) throw new Error(`暂不支持编辑策略类型：${type}`);
  for (const field of fields) {
    const raw = params[field.key];
    if (field.nullable && (raw == null || String(raw).trim() === "")) { result[field.key] = null; continue; }
    const text = typeof raw === "number" || typeof raw === "string" ? String(raw).trim() : "";
    const decimal = /^[+-]?(\d+(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(text);
    const scale = decimal ? (decimal[2] ?? decimal[3] ?? "").length - Number(decimal[4] ?? 0) : Infinity;
    const digits = decimal?.[1].replace(".", "") ?? "";
    const exactInteger = scale <= 0 || (scale <= digits.length && /^0*$/.test(digits.slice(-scale)));
    const value = Number(text);
    if (!decimal || text.length > 100 || !Number.isFinite(value) || !Number.isSafeInteger(scale) || scale < -2_147_483_647 || value < field.min || (!field.integer && ((!field.allowZero && value === 0) || scale > 18)) || (field.max != null && value > field.max) || (field.integer && (!Number.isInteger(value) || !exactInteger)) || (!field.integer && decimalUnits(text) > decimalUnits(field.max))) {
      throw new Error(`${field.label}无效${field.integer ? "，请输入允许范围内的正整数" : "，请输入允许范围内大于 0 的数字"}`);
    }
    // Keep the user's decimal text intact; the server validates it as BigDecimal.
    result[field.key] = field.integer ? value : String(raw).trim();
  }
  if (type === "intraday-technical") {
    if (!intradayModes.some((mode) => mode.value === params.mode)) throw new Error("请选择有效的日内策略模式");
    if (result["ema-fast-period"] >= result["ema-slow-period"]) throw new Error("快 EMA 周期必须小于慢 EMA 周期");
    if (decimalUnits(result["entry-rsi-min"]) >= decimalUnits(result["entry-rsi-max"])) throw new Error("入场 RSI 下限必须小于上限");
    if (decimalUnits(result["min-atr-percent"]) >= decimalUnits(result["max-atr-percent"])) throw new Error("最低 ATR 比例必须小于最高 ATR 比例");
    if (decimalUnits(result["stop-loss-percent"]) >= decimalUnits(result["take-profit-percent"])) throw new Error("止盈比例必须大于止损比例");
    result.mode = params.mode;
  } else {
    const confirmed = params["require-confirmed-candle"];
    if (confirmed != null) {
      if (![true, false, "true", "false"].includes(confirmed)) throw new Error("收盘 K 线选项无效");
      result["require-confirmed-candle"] = confirmed === true || confirmed === "true";
    }
    if (confirmed == null) throw new Error("请选择是否仅使用收盘 K 线");
  }
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
