"use client";

import {
  Activity,
  ArrowUpRight,
  Bell,
  Bot,
  Boxes,
  BrainCircuit,
  Check,
  ChevronRight,
  Clock3,
  Command,
  Gauge,
  Layers3,
  LayoutDashboard,
  LoaderCircle,
  Menu,
  Play,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  SquareActivity,
  TestTube2,
  TrendingUp,
  Trash2,
  WalletCards,
  X,
  Zap,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { apiTimestampMillis, backtestSelectionForStrategy, buildBacktestPayload, buildPaperSessionPayload, buildStrategyParametersPayload, createJsonRequestHeaders, decisionIndicatorEntries, filterConfigurationEvents, formatDateTimeLocal, formatReturnFraction, intradayModes, marketDataIsFresh, mergeContiguousHistory, parameterFieldsForStrategy, reconcileCockpitRefresh } from "./lib/trading-api.mjs";

type View = "overview" | "strategies" | "events" | "backtests";
type ApiTimestamp = string | number;

type Strategy = {
  id: string;
  type: string;
  enabled: boolean;
  bar: string;
  params?: Record<string, unknown>;
};

type RuntimeStatus = {
  instId?: string;
  baseCcy?: string;
  quoteCcy?: string;
  executionMode: "PAPER" | "LIVE" | string;
  liveEnabled: boolean;
  runningStrategyIds: string[];
  activeStrategyId?: string | null;
  activeStrategyRevision?: number;
  activeStrategyChangedAt?: ApiTimestamp | null;
  lastDecision?: {
    decisionId?: string;
    strategyId?: string;
    strategyConfigVersion?: number;
    timestamp?: ApiTimestamp;
    action?: string;
    reason?: string;
    confidence?: number;
    winProbability?: number;
    expectedNetEdgePercent?: number;
    lastPrice?: number;
    executionStatus?: string;
    metadata?: Record<string, unknown>;
  } | null;
  lastError?: string | null;
  lastRunStartedAt?: ApiTimestamp | null;
  lastRunCompletedAt?: ApiTimestamp | null;
  marketDataStale: boolean;
};

type EventBusStatus = {
  running: boolean;
  accepting: boolean;
  queueDepth: number;
  queueCapacity: number;
  handlerCount: number;
  accepted: number;
  dropped: number;
  consumed: number;
  failed: number;
};

type AutomationLoop = {
  id: string;
  initialDelayMs: number;
  fixedDelayMs: number;
  nextRunAt?: ApiTimestamp | null;
  lastRunStartedAt?: ApiTimestamp | null;
  lastRunCompletedAt?: ApiTimestamp | null;
  lastRunSuccessful?: boolean | null;
  lastError?: string | null;
};

type AutomationTask = {
  id: string;
  name: string;
  running: boolean;
  autoStart: boolean;
  loops: AutomationLoop[];
};

type BacktestRun = {
  runId: string;
  strategyConfigVersion?: number;
  strategyConfigSnapshot?: Record<string, unknown>;
  status: string;
  request: {
    strategyId: string;
    instId: string;
    bar: string;
    initialCash?: number;
    parameterOverrides?: Record<string, unknown>;
  };
  createdAt: ApiTimestamp;
  error?: string | null;
  totalReturn?: number | null;
  maxDrawdown?: number | null;
  winRate?: number | null;
  tradeCount?: number;
  closedTradesPerDay?: number | null;
  processedCandleCount?: number;
  candleCount?: number;
};

type Snapshot = {
  runtime: RuntimeStatus;
  events: EventBusStatus;
  strategies: Strategy[];
  tasks: AutomationTask[];
  backtests: BacktestRun[];
};

type Candle = {
  ts: string;
  open: string;
  high: string;
  low: string;
  close: string;
  vol?: string;
  volCcy?: string;
  volCcyQuote?: string;
  confirm?: string;
};

type CandleSeries = {
  instrumentId: string;
  bar: string;
  source: string;
  asOf: ApiTimestamp;
  checkedAt?: ApiTimestamp;
  candles: Candle[];
};

type CandleStreamUpdate = {
  eventId: string;
  instrumentId: string;
  bar: string;
  receivedAt: ApiTimestamp;
  candles: Candle[];
};

type ActiveStrategySelection = {
  strategyId: string;
  revision: number;
  changedAt?: ApiTimestamp | null;
};

type StrategyConfiguration = {
  strategyId: string;
  type: string;
  bar: string;
  version: number;
  changedAt: ApiTimestamp;
  reason: string;
  params: Record<string, string | number | boolean | null>;
};

type PaperAccount = {
  id: number;
  sessionId: string;
  instId: string;
  quoteCcy: string;
  status: "ACTIVE" | "ARCHIVED";
  initialCash: number;
  cash: number;
  positionQuantity: number;
  averageCost: number;
  realizedPnl: number;
  totalFeesQuote: number;
  lastPrice: number | null;
  equity: number | null;
  unrealizedPnl: number | null;
  totalReturnPercent: number | null;
  version: number;
  createdAt: ApiTimestamp;
  updatedAt: ApiTimestamp;
  valuationAt: ApiTimestamp | null;
  valuationStale: boolean;
};

type PaperEquityPoint = {
  id: number;
  sessionId: string;
  timestamp: ApiTimestamp;
  cash: number;
  positionQuantity: number;
  lastPrice: number | null;
  equity: number | null;
  realizedPnl: number;
  unrealizedPnl: number | null;
  strategyId: string | null;
  strategyConfigVersion: number | null;
  kind: "INITIAL" | "MARK" | "FILL";
};

type PaperFill = {
  id: number;
  sessionId: string;
  decisionId: string;
  strategyId: string;
  strategyConfigVersion: number;
  side: string;
  filledBase: number;
  price: number;
  cashDelta: number;
  feeQuote: number;
  realizedPnl: number;
  cashAfter: number;
  equityAfter: number;
  createdAt: ApiTimestamp;
};

type StreamState = "connecting" | "live" | "polling" | "demo";

const API_BASE = (process.env.NEXT_PUBLIC_TRADE_API_URL ?? "").replace(/\/$/, "");

const demoSnapshot: Snapshot = {
  runtime: {
    executionMode: "PAPER",
    liveEnabled: false,
    runningStrategyIds: ["threshold-event-default"],
    activeStrategyId: "threshold-event-default",
    activeStrategyRevision: 3,
    activeStrategyChangedAt: new Date(Date.now() - 3_600_000).toISOString(),
    lastDecision: {
      decisionId: "dec-7f4c91",
      strategyId: "threshold-event-default",
      timestamp: new Date(Date.now() - 84_000).toISOString(),
      action: "HOLD",
      reason:
        "Momentum remains constructive, but expected net edge is still below the execution threshold after fees.",
      confidence: 0.76,
      winProbability: 0.61,
      expectedNetEdgePercent: 1.84,
      lastPrice: 67842.1,
      executionStatus: "SKIPPED",
    },
    lastError: null,
    lastRunStartedAt: new Date(Date.now() - 91_000).toISOString(),
    lastRunCompletedAt: new Date(Date.now() - 84_000).toISOString(),
    marketDataStale: false,
  },
  events: {
    running: true,
    accepting: true,
    queueDepth: 31,
    queueCapacity: 1000,
    handlerCount: 3,
    accepted: 928_413,
    dropped: 0,
    consumed: 928_382,
    failed: 2,
  },
  strategies: [
    { id: "threshold-event-default", type: "threshold-event", enabled: true, bar: "1m", params: { "price-move-trigger-percent": "0.02", "volume-spike-multiplier": "3" } },
    { id: "threshold-event-defensive", type: "threshold-event", enabled: true, bar: "5m", params: { "price-move-trigger-percent": "0.03", "volume-spike-multiplier": "4" } },
    { id: "threshold-event-reactive", type: "threshold-event", enabled: true, bar: "1m", params: { "price-move-trigger-percent": "0.01", "volume-spike-multiplier": "2" } },
  ],
  tasks: [
    {
      id: "trading",
      name: "Trading automation",
      running: true,
      autoStart: false,
      loops: [
        {
          id: "decision",
          initialDelayMs: 30_000,
          fixedDelayMs: 1_800_000,
          nextRunAt: new Date(Date.now() + 24 * 60_000 + 18_000).toISOString(),
          lastRunSuccessful: true,
        },
        {
          id: "event-scan",
          initialDelayMs: 30_000,
          fixedDelayMs: 60_000,
          nextRunAt: new Date(Date.now() + 36_000).toISOString(),
          lastRunSuccessful: true,
        },
      ],
    },
  ],
  backtests: [
    {
      runId: "bt_20260717_01",
      status: "SUCCEEDED",
      request: { strategyId: "threshold-event-default", instId: "BTC-USDT", bar: "1m" },
      createdAt: new Date(Date.now() - 3_900_000).toISOString(),
      totalReturn: 0.1268,
      maxDrawdown: 0.0431,
      winRate: 0.623,
      tradeCount: 42,
      processedCandleCount: 10_000,
      candleCount: 10_000,
    },
    {
      runId: "bt_20260716_04",
      status: "SUCCEEDED",
      request: { strategyId: "threshold-event-defensive", instId: "BTC-USDT", bar: "5m" },
      createdAt: new Date(Date.now() - 86_400_000).toISOString(),
      totalReturn: 0.0842,
      maxDrawdown: 0.0618,
      winRate: 0.571,
      tradeCount: 28,
      processedCandleCount: 4800,
      candleCount: 4800,
    },
  ],
};

const demoCloses = [
  67280, 67335, 67294, 67420, 67515, 67470, 67602, 67740, 67665, 67790,
  67855, 67782, 67920, 68010, 67940, 67866, 67972, 68088, 68142, 68072,
  68190, 68238, 68134, 68292, 68345, 68260, 68318, 68412, 68378, 68452,
];

const demoCandles: Candle[] = demoCloses.map((close, index) => {
  const open = index === 0 ? close - 34 : demoCloses[index - 1];
  const spread = 42 + (index % 5) * 11;
  return {
    ts: String(Date.UTC(2026, 6, 21, 12, index)),
    open: String(open),
    high: String(Math.max(open, close) + spread),
    low: String(Math.min(open, close) - spread * 0.8),
    close: String(close),
    vol: String(18 + index * 0.7),
    volCcyQuote: String((18 + index * 0.7) * close),
    confirm: index === demoCloses.length - 1 ? "0" : "1",
  };
});

const demoMarket: CandleSeries = {
  instrumentId: "BTC-USDT",
  bar: "1m",
  source: "DEMO",
  asOf: new Date().toISOString(),
  candles: demoCandles,
};

const navItems: Array<{ id: View; label: string; icon: typeof LayoutDashboard }> = [
  { id: "overview", label: "总览", icon: LayoutDashboard },
  { id: "strategies", label: "策略", icon: BrainCircuit },
  { id: "events", label: "事件", icon: Boxes },
  { id: "backtests", label: "回测", icon: TestTube2 },
];

function compact(value: number) {
  return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

function pct(value?: number | null, digits = 2) {
  return formatReturnFraction(value, digits);
}

function timeAgo(value: ApiTimestamp | null | undefined, now: Date | null) {
  const timestamp = apiTimestampMillis(value);
  if (timestamp == null) return "暂无记录";
  if (now == null) return "—";
  const seconds = Math.max(0, Math.round((now.getTime() - timestamp) / 1000));
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  return `${Math.floor(seconds / 3600)} 小时前`;
}

class ApiRequestError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "请求失败，请稍后重试";
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const timeout = AbortSignal.timeout(15_000);
  const response = await fetch(`${API_BASE}${path}`, {
    cache: "no-store",
    ...init,
    signal: init?.signal ? AbortSignal.any([init.signal, timeout]) : timeout,
    headers: createJsonRequestHeaders({ headers: init?.headers, method: init?.method }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const detail = problem?.detail ?? problem?.message ?? problem?.error;
    throw new ApiRequestError(response.status, `${response.status} ${typeof detail === "string" ? detail : response.statusText}`);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

function number(value?: string | number | null, digits = 1) {
  if (value == null || value === "") return "—";
  const parsed = Number(value);
  return Number.isFinite(parsed)
    ? new Intl.NumberFormat("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(parsed)
    : "—";
}

function mergeCandles(current: CandleSeries, update: CandleStreamUpdate): CandleSeries {
  const sameSeries = current.instrumentId === update.instrumentId && current.bar === update.bar;
  const byTimestamp = new Map((sameSeries ? current.candles : []).map((candle) => [candle.ts, candle]));
  update.candles.forEach((candle) => byTimestamp.set(candle.ts, candle));
  const candles = Array.from(byTimestamp.values())
    .sort((left, right) => Number(left.ts) - Number(right.ts))
    .slice(-80);
  return {
    instrumentId: update.instrumentId,
    bar: update.bar,
    source: "SSE",
    asOf: update.receivedAt,
    candles,
  };
}

function eventData<T>(event: Event): T {
  return JSON.parse((event as MessageEvent<string>).data) as T;
}

export default function Home() {
  const [activeView, setActiveView] = useState<View>("overview");
  const [snapshot, setSnapshot] = useState<Snapshot>(demoSnapshot);
  const [dataMode, setDataMode] = useState<"live" | "demo">("demo");
  const [runtimeConnected, setRuntimeConnected] = useState(false);
  const refreshAbort = useRef<AbortController | null>(null);
  const refreshRevision = useRef(0);
  const hasLiveSnapshot = useRef(false);
  const [refreshing, setRefreshing] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [backtestOpen, setBacktestOpen] = useState(false);
  const [backtestRange, setBacktestRange] = useState({ from: "", to: "" });
  const [backtestSelection, setBacktestSelection] = useState({ strategyId: "", bar: "1m" });
  const [backtestInstrument, setBacktestInstrument] = useState("BTC-USDT");
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  // SSR and the first browser render share placeholders until hydration finishes.
  const [clock, setClock] = useState<Date | null>(null);
  const clientReady = clock !== null;
  const [taskPending, setTaskPending] = useState(false);
  const [backtestPending, setBacktestPending] = useState(false);
  const [strategyPending, setStrategyPending] = useState<string | null>(null);
  const [chartBar, setChartBar] = useState("1m");
  const [market, setMarket] = useState<CandleSeries>(demoMarket);
  const [marketReceivedAt, setMarketReceivedAt] = useState(0);
  const marketRequest = useRef(0);
  const marketStreamVersion = useRef(0);
  const marketStreamUpdates = useRef(new Map<string, { version: number; candle: Candle }>());
  const marketPending = useRef<{ instId: string; bar: string; signal: AbortSignal; requestId: number } | null>(null);
  const [streamState, setStreamState] = useState<StreamState>("connecting");
  const [query, setQuery] = useState("");
  const [paperCurrent, setPaperCurrent] = useState<PaperAccount | null>(null);
  const [paperRefreshToken, setPaperRefreshToken] = useState(0);
  const paperMode = snapshot.runtime.executionMode === "PAPER";
  const marketInstrument = snapshot.runtime.instId ?? paperCurrent?.instId ?? "BTC-USDT";

  const refresh = useCallback(async (quiet = false) => {
    refreshAbort.current?.abort();
    const abort = new AbortController();
    refreshAbort.current = abort;
    const revision = ++refreshRevision.current;
    if (!quiet) setRefreshing(true);
    const endpoints = await Promise.allSettled([
      request<RuntimeStatus>("/api/trading/runtime/status", { signal: abort.signal }),
      request<EventBusStatus>("/api/trading/runtime/events", { signal: abort.signal }),
      request<Strategy[]>("/api/trading/strategies", { signal: abort.signal }),
      request<AutomationTask[]>("/api/automation/tasks", { signal: abort.signal }),
      request<BacktestRun[]>("/api/trading/backtests?offset=0&limit=12", { signal: abort.signal }),
    ]);
    if (abort.signal.aborted || revision !== refreshRevision.current) return;
    const result = reconcileCockpitRefresh({ snapshot: demoSnapshot, dataMode: hasLiveSnapshot.current ? "live" : "demo" }, endpoints);
    setRuntimeConnected(result.connected);
    if (result.connected) {
      hasLiveSnapshot.current = true;
      setSnapshot(result.snapshot);
      setDataMode("live");
      if (!quiet) setToast("已同步 5/5 个运行时数据源");
    } else {
      // Keep real operations real during an outage, especially the stop command.
      if (!quiet) setToast(hasLiveSnapshot.current ? "状态同步失败，保留上次真实快照；操作结果须由后端确认" : `后端数据尚未完整同步（${result.successful}/5），继续展示演示快照`);
    }
    setRefreshing(false);
    setPaperRefreshToken((current) => current + 1);
  }, []);

  const loadMarket = useCallback(async (bar: string, signal: AbortSignal) => {
    if (marketPending.current?.instId === marketInstrument && marketPending.current.bar === bar && !marketPending.current.signal.aborted) return null;
    const requestId = ++marketRequest.current;
    const streamVersion = marketStreamVersion.current;
    const requestedAt = Date.now();
    marketPending.current = { instId: marketInstrument, bar, signal, requestId };
    try {
      const series = await request<CandleSeries>(`/api/trading/market/candles?instId=${encodeURIComponent(marketInstrument)}&bar=${encodeURIComponent(bar)}&limit=80`, { signal, cache: "no-store" });
      // A newer interval or poll owns the visible chart. Stream updates are
      // merged over the response so frequent pushes cannot starve history loading.
      if (signal.aborted || requestId !== marketRequest.current) return null;
      const updates = Array.from(marketStreamUpdates.current.values()).filter((update) => update.version > streamVersion).map((update) => update.candle);
      setMarket(updates.length > 0
        ? mergeCandles(series, { eventId: "history", instrumentId: series.instrumentId, bar, receivedAt: series.asOf, candles: updates })
        : series);
      setMarketReceivedAt(apiTimestampMillis(series.checkedAt) ?? requestedAt);
      setStreamState((current) => current === "live" ? current : "polling");
      return true;
    } catch {
      if (signal.aborted || requestId !== marketRequest.current) return null;
      if (streamVersion !== marketStreamVersion.current) return null;
      setStreamState((current) => current === "demo" ? "demo" : "polling");
      return false;
    } finally {
      if (marketPending.current?.requestId === requestId) marketPending.current = null;
    }
  }, [marketInstrument]);

  useEffect(() => {
    const initialRefresh = window.setTimeout(() => { setClock(new Date()); void refresh(true); }, 0);
    const clockTimer = window.setInterval(() => setClock(new Date()), 1000);
    const refreshTimer = window.setInterval(() => refresh(true), 30_000);
    return () => {
      window.clearTimeout(initialRefresh);
      window.clearInterval(clockTimer);
      window.clearInterval(refreshTimer);
      refreshAbort.current?.abort();
    };
  }, [refresh]);

  useEffect(() => {
    let active = true;
    const abort = new AbortController();
    marketStreamUpdates.current.clear();
    const rememberUpdates = (candles: Candle[]) => {
      const version = ++marketStreamVersion.current;
      candles.forEach((candle) => marketStreamUpdates.current.set(candle.ts, { version, candle }));
      while (marketStreamUpdates.current.size > 300) marketStreamUpdates.current.delete(marketStreamUpdates.current.keys().next().value!);
    };
    const initialMarket = window.setTimeout(() => {
      void loadMarket(chartBar, abort.signal).then((connected) => {
        if (!active) return;
        if (connected === false) {
          setMarket({ ...demoMarket, instrumentId: marketInstrument, candles: marketInstrument === demoMarket.instrumentId ? demoMarket.candles : [], bar: chartBar, asOf: new Date().toISOString() });
        }
      });
    }, 0);

    const streamUrl = `${API_BASE}/api/trading/market/candles/stream?instId=${encodeURIComponent(marketInstrument)}&bar=${encodeURIComponent(chartBar)}&limit=80`;
    const source = new EventSource(streamUrl);
    source.addEventListener("snapshot", (event) => {
      if (!active) return;
      const series = eventData<CandleSeries>(event);
      const latest = series.candles.reduce<Candle | null>((current, candle) => !current || Number(candle.ts) > Number(current.ts) ? candle : current, null);
      rememberUpdates(latest ? [latest] : []);
      setMarket(series);
      setMarketReceivedAt(apiTimestampMillis(series.checkedAt) ?? 0);
      setStreamState("live");
    });
    source.addEventListener("candle", (event) => {
      if (!active) return;
      const update = eventData<CandleStreamUpdate>(event);
      const receivedAt = apiTimestampMillis(update.receivedAt) ?? 0;
      const now = Date.now();
      // A delayed queue event must not overwrite newer prices or revive freshness.
      if (receivedAt <= 0 || now - receivedAt > 30_000 || receivedAt > now + 1_000) return;
      rememberUpdates(update.candles);
      setMarket((current) => mergeCandles(current, update));
      if (marketDataIsFresh(update.candles, update.bar, receivedAt, now)) setMarketReceivedAt(receivedAt);
      setStreamState("live");
    });
    source.onopen = () => active && setStreamState("live");
    source.onerror = () => active && setStreamState((current) => current === "demo" ? "demo" : "polling");

    const pollTimer = window.setInterval(() => {
      if (document.visibilityState === "visible") void loadMarket(chartBar, abort.signal);
    }, 15_000);
    return () => {
      active = false;
      abort.abort();
      window.clearTimeout(initialMarket);
      source.close();
      window.clearInterval(pollTimer);
    };
  }, [chartBar, loadMarket, marketInstrument]);

  const marketFresh = market.instrumentId === marketInstrument && market.bar === chartBar && marketDataIsFresh(market.candles, market.bar, marketReceivedAt, clock?.getTime() ?? 0);
  const marketStatus = market.source === "DEMO"
    ? "安全演示 K 线"
    : !marketFresh ? "行情已过期 · 15 秒重试"
      : streamState === "live" ? "行情正常 · 推送已连接" : "行情正常 · 15 秒轮询";

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setCommandOpen((open) => !open);
      }
      if (event.key === "Escape") {
        setCommandOpen(false);
        setBacktestOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 3200);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const task = snapshot.tasks.find((item) => item.id === "trading");
  const decisionLoop = task?.loops.find((loop) => loop.id === "decision");
  const eventLoop = task?.loops.find((loop) => loop.id === "event-scan");
  const queueUtilization = Math.min(100, (snapshot.events.queueDepth / Math.max(1, snapshot.events.queueCapacity)) * 100);
  const decision = snapshot.runtime.lastDecision;
  const chart = useMemo(() => {
    const items = market.candles.slice(-48).map((candle) => ({
      candle,
      open: Number(candle.open),
      high: Number(candle.high),
      low: Number(candle.low),
      close: Number(candle.close),
    })).filter((item) => [item.open, item.high, item.low, item.close].every(Number.isFinite));
    const fallback = { items: [], latest: null as Candle | null, change: 0, min: 0, max: 1, range: 1, priceScale: [0], priceTop: 50, times: [] as string[], quoteVolume: 0 };
    if (!items.length) return fallback;
    const min = Math.min(...items.map((item) => item.low));
    const max = Math.max(...items.map((item) => item.high));
    const range = Math.max(max - min, 1);
    const latest = items.at(-1)!.candle;
    const firstClose = items[0].close;
    const change = firstClose === 0 ? 0 : ((items.at(-1)!.close - firstClose) / firstClose) * 100;
    const priceScale = Array.from({ length: 5 }, (_, index) => max - (range * index / 4));
    const priceTop = Math.min(96, Math.max(2, ((max - items.at(-1)!.close) / range) * 100));
    const timeIndexes = [0, .25, .5, .75, 1].map((ratio) => Math.round((items.length - 1) * ratio));
    const times = timeIndexes.map((index) => clientReady ? new Date(Number(items[index].candle.ts)).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }) : "--:--");
    const quoteVolume = items.reduce((sum, item) => sum + Number(item.candle.volCcyQuote ?? 0), 0);
    return { items, latest, change, min, max, range, priceScale, priceTop, times, quoteVolume };
  }, [market, clientReady]);

  const openBacktest = useCallback(() => {
    const now = Date.now();
    setBacktestRange({ from: formatDateTimeLocal(now - 86_400_000), to: formatDateTimeLocal(now) });
    setBacktestSelection(backtestSelectionForStrategy(snapshot.strategies.find((strategy) => strategy.id === snapshot.runtime.activeStrategyId) ?? snapshot.strategies[0]));
    setBacktestInstrument(marketInstrument);
    setBacktestOpen(true);
  }, [snapshot.strategies, snapshot.runtime.activeStrategyId, marketInstrument]);

  const filteredCommands = useMemo(() => {
    const commands = [
      { label: "打开运行总览", hint: "Overview", command: "overview" },
      { label: "查看策略矩阵", hint: "Strategies", command: "strategies" },
      { label: "检查事件背压", hint: "Events", command: "events" },
      { label: "新建策略回测", hint: "Backtest", command: "backtest" },
      { label: "同步后端状态", hint: "Refresh", command: "refresh" },
    ];
    return commands.filter((item) => item.label.toLowerCase().includes(query.toLowerCase()));
  }, [query]);

  const navigate = (view: View) => {
    setActiveView(view);
    setMobileNavOpen(false);
  };

  const toggleTask = async () => {
    if (!task || taskPending) return;
    if (dataMode === "live" && !runtimeConnected && !task.running) {
      setToast("请先同步最新运行状态，再启动交易自动化");
      return;
    }
    setTaskPending(true);
    if (dataMode === "live") {
      try {
        const next = await request<AutomationTask>(`/api/automation/tasks/${task.id}/${task.running ? "stop" : "start"}`, { method: "POST" });
        refreshRevision.current += 1;
        setRefreshing(false);
        setSnapshot((current) => ({ ...current, tasks: current.tasks.map((item) => item.id === next.id ? next : item) }));
        setToast(next.running ? "交易自动化已启动" : "交易自动化已安全停止");
      } catch (error) {
        setToast(`任务状态更新失败：${errorMessage(error)}`);
      }
    } else {
      setSnapshot((current) => ({
        ...current,
        tasks: current.tasks.map((item) => item.id === task.id ? { ...item, running: !item.running } : item),
      }));
      setToast("演示快照已切换，不会触发真实交易");
    }
    setTaskPending(false);
  };

  const activateStrategy = async (strategyId: string) => {
    if (strategyId === snapshot.runtime.activeStrategyId || strategyPending) return;
    if (dataMode === "live" && !runtimeConnected) {
      setToast("请先同步最新运行状态，再切换策略");
      return;
    }
    setStrategyPending(strategyId);
    if (dataMode === "live") {
      try {
        const selection = await request<ActiveStrategySelection>("/api/trading/strategies/active", {
          method: "PUT",
          body: JSON.stringify({
            strategyId,
            expectedRevision: snapshot.runtime.activeStrategyRevision ?? 0,
          }),
        });
        refreshRevision.current += 1;
        setRefreshing(false);
        setSnapshot((current) => ({
          ...current,
          runtime: {
            ...current.runtime,
            activeStrategyId: selection.strategyId,
            activeStrategyRevision: selection.revision,
            activeStrategyChangedAt: selection.changedAt,
            runningStrategyIds: [selection.strategyId],
          },
        }));
        setToast(`当前策略已切换为 ${selection.strategyId}`);
      } catch (error) {
        const hint = error instanceof ApiRequestError && error.status === 403 ? "请检查网关登录状态或访问权限；" : "";
        setToast(`策略切换失败：${hint}${errorMessage(error)}`);
        await refresh(true);
      }
    } else {
      setSnapshot((current) => ({
        ...current,
        runtime: {
          ...current.runtime,
          activeStrategyId: strategyId,
          activeStrategyRevision: (current.runtime.activeStrategyRevision ?? 0) + 1,
          activeStrategyChangedAt: new Date().toISOString(),
          runningStrategyIds: [strategyId],
        },
      }));
      setToast("演示策略已切换，不会触发真实交易");
    }
    setStrategyPending(null);
  };

  const submitBacktest = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (backtestPending) return;
    const data = new FormData(event.currentTarget);
    let payload: ReturnType<typeof buildBacktestPayload>;
    try {
      payload = buildBacktestPayload({
        strategyId: String(data.get("strategyId") ?? ""),
        instId: String(data.get("instId") ?? ""),
        bar: String(data.get("bar") ?? ""),
        initialCash: Number(data.get("initialCash")),
        from: String(data.get("from") ?? ""),
        to: String(data.get("to") ?? ""),
      });
    } catch (error) {
      setToast(errorMessage(error));
      return;
    }
    setBacktestPending(true);

    if (dataMode === "live") {
      try {
        const run = await request<BacktestRun>("/api/trading/backtests", { method: "POST", body: JSON.stringify(payload) });
        refreshRevision.current += 1;
        setRefreshing(false);
        setSnapshot((current) => ({ ...current, backtests: [run, ...current.backtests] }));
        setToast(`回测 ${run.runId} 已进入队列`);
      } catch (error) {
        setToast(`回测提交失败：${errorMessage(error)}`);
        setBacktestPending(false);
        return;
      }
    } else {
      const run: BacktestRun = {
        runId: `demo_${Date.now().toString().slice(-6)}`,
        status: "QUEUED",
        request: payload,
        createdAt: new Date().toISOString(),
        processedCandleCount: 0,
        candleCount: 10_000,
        tradeCount: 0,
      };
      setSnapshot((current) => ({ ...current, backtests: [run, ...current.backtests] }));
      setToast("演示回测已加入队列");
    }
    setBacktestPending(false);
    setBacktestOpen(false);
    setActiveView("backtests");
  };

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand-lockup">
          <button className="icon-button mobile-menu" aria-label="打开导航" onClick={() => setMobileNavOpen((open) => !open)}>
            <Menu size={18} />
          </button>
          <div className="brand-mark" aria-hidden="true"><span /><span /><span /></div>
          <div>
            <div className="brand-name">ORBIT</div>
            <div className="brand-subtitle">TRADING SYSTEM</div>
          </div>
        </div>

        <button className="command-trigger" onClick={() => setCommandOpen(true)}>
          <Search size={15} />
          <span>搜索策略、运行或命令</span>
          <kbd><Command size={11} /> K</kbd>
        </button>

        <div className="topbar-actions">
          <div className={`connection-pill ${dataMode === "live" && !runtimeConnected ? "demo" : dataMode}`}>
            <span className="connection-dot" />
            {dataMode === "live" ? runtimeConnected ? "后端已连接" : "同步中断 · 上次快照" : "演示数据"}
          </div>
          <span className="clock">{clock ? clock.toLocaleTimeString("zh-CN", { hour12: false }) : "--:--:--"}</span>
          <button className="icon-button" aria-label="刷新状态" onClick={() => refresh()}>
            <RefreshCw size={17} className={refreshing ? "spin" : ""} />
          </button>
          <button className="icon-button notification-button" aria-label="通知">
            <Bell size={17} /><span />
          </button>
          <div className="avatar" aria-label="当前操作员">OP</div>
        </div>
      </header>

      <aside className={`sidebar ${mobileNavOpen ? "open" : ""}`}>
        <nav aria-label="主导航">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button key={item.id} className={activeView === item.id ? "active" : ""} onClick={() => navigate(item.id)}>
                <Icon size={19} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
        <div className="sidebar-bottom">
          <div className="environment-mark">{snapshot.runtime.executionMode}</div>
        </div>
      </aside>

      <section className="workspace">
        <div className="workspace-heading">
          <div>
            <div className="eyebrow"><span /> SYSTEM COMMAND CENTER</div>
            <h1>{activeView === "overview" ? "交易驾驶舱" : activeView === "strategies" ? "策略矩阵" : activeView === "events" ? "事件管道" : "回测实验室"}</h1>
            <p>{activeView === "overview" ? "关键运行信号、资金状态与系统健康度，一屏掌握。" : activeView === "strategies" ? "查看当前策略配置、运行状态与决策边界。" : activeView === "events" ? "观察有界队列、消费吞吐与背压健康度。" : "以可复现数据验证策略，不让直觉直接接管资金。"}</p>
          </div>
          <div className="heading-actions">
            <button className="secondary-button" onClick={() => refresh()}><RefreshCw size={15} />同步状态</button>
            <button className="primary-button" onClick={openBacktest}><Plus size={16} />新建回测</button>
          </div>
        </div>

        {dataMode === "live" && !runtimeConnected && <p className="inline-error" role="status">状态同步中断，当前显示上次真实快照。请同步后再启动自动化或切换策略；停止命令仍会提交后端确认。</p>}

        {activeView === "overview" && (
          <>
            <section className="metric-grid" aria-label="核心指标">
              <MetricCard icon={WalletCards} label="模拟资产净值" value={paperMode && paperCurrent ? number(paperCurrent.equity, 2) : "—"} delta={paperCurrent && paperMode ? paperCurrent.quoteCcy : "待开始模拟"} detail={paperCurrent && paperMode ? `初始资金 ${number(paperCurrent.initialCash, 2)} · ${paperCurrent.valuationStale ? "估值待更新" : "当前模拟会话"}` : paperMode ? "下方设置资金，开始独立模拟" : "当前为实盘模式"} />
              <MetricCard icon={TrendingUp} label="已实现盈亏" value={paperMode && paperCurrent ? number(paperCurrent.realizedPnl, 2) : "—"} delta={paperCurrent && paperMode ? paperCurrent.quoteCcy : "暂无模拟"} detail={paperCurrent && paperMode ? `本次模拟 · 手续费 ${number(paperCurrent.totalFeesQuote, 4)}` : "基于模拟实际成交统计"} />
              <MetricCard icon={Gauge} label="风险利用率" value={dataMode === "demo" ? "38.2%" : "—"} delta={dataMode === "demo" ? "演示值" : "暂无数据"} detail={dataMode === "demo" ? "上限 72%" : "风险利用率尚未接入"} progress={dataMode === "demo" ? 38.2 : undefined} />
              <MetricCard icon={Clock3} label="下次策略决策" value={decisionLoop?.nextRunAt ? timeAgoFuture(decisionLoop.nextRunAt, clock) : "待调度"} delta={!task ? "任务未读取" : task.running ? "运行中" : "已暂停"} positive={Boolean(task?.running)} detail="decision loop" />
            </section>

            <PaperSimulationPanel dataMode={dataMode} executionMode={dataMode === "live" && !runtimeConnected ? "运行模式未确认" : snapshot.runtime.executionMode} refreshToken={paperRefreshToken} onCurrentChange={setPaperCurrent} />
            <TradingAiPanel key={dataMode} dataMode={dataMode} />

            <div className="overview-grid">
              <section className="panel market-panel">
                <div className="panel-heading market-heading">
                  <div className="instrument-title">
                    <div className="coin-mark">{market.instrumentId.startsWith("BTC-") ? "₿" : market.instrumentId.split("-")[0].slice(0, 1)}</div>
                    <div><h2>{market.instrumentId.replaceAll("-", " / ")}</h2><p>OKX · 现货 · {market.bar}</p></div>
                  </div>
                  <div className="market-price"><strong>${number(chart.latest?.close)}</strong><span className={chart.change < 0 ? "market-down" : ""}><ArrowUpRight size={13} /> {chart.change >= 0 ? "+" : ""}{chart.change.toFixed(2)}%</span></div>
                </div>
                <div className="chart-toolbar">
                  <div>{["1m", "5m", "15m", "1H", "4H"].map((bar) => <button key={bar} className={chartBar === bar ? "active" : ""} onClick={() => { if (bar !== chartBar) { setStreamState("connecting"); setChartBar(bar); } }}>{bar}</button>)}</div>
                  <div className="ohlc"><span>O {number(chart.latest?.open)}</span><span>H {number(chart.latest?.high)}</span><span>L {number(chart.latest?.low)}</span><span className="up">C {number(chart.latest?.close)}</span></div>
                </div>
                <div className="chart" aria-label={`${market.instrumentId} ${market.bar} 实时 K 线图`}>
                  <div className="chart-grid-lines"><span /><span /><span /><span /><span /></div>
                  <div className="price-scale">{chart.priceScale.map((price, index) => <span key={index}>{number(price, 0)}</span>)}</div>
                  <div className="candles">
                    {chart.items.map(({ candle, open, high, low, close }) => {
                      const rising = close >= open;
                      const wickTop = ((chart.max - high) / chart.range) * 100;
                      const wickBottom = ((chart.max - low) / chart.range) * 100;
                      const bodyTop = ((chart.max - Math.max(open, close)) / chart.range) * 100;
                      const bodyHeight = Math.max(1.4, (Math.abs(close - open) / chart.range) * 100);
                      return <span key={candle.ts} className={`candle ${rising ? "rise" : "fall"}`} title={clientReady ? `${new Date(Number(candle.ts)).toLocaleString("zh-CN")} O ${candle.open} H ${candle.high} L ${candle.low} C ${candle.close}` : undefined} style={{ "--wick-top": `${wickTop}%`, "--wick-height": `${Math.max(1, wickBottom - wickTop)}%`, "--body-top": `${bodyTop}%`, "--body-height": `${bodyHeight}%` } as React.CSSProperties} />;
                    })}
                  </div>
                  {chart.latest && <div className="current-price-line" style={{ top: `${chart.priceTop}%` }}><span>{number(chart.latest.close)}</span></div>}
                  <div className="chart-time">{chart.times.map((time, index) => <span key={`${time}-${index}`}>{time}</span>)}</div>
                </div>
                <div className="market-footer">
                  <span><i className={market.source === "DEMO" ? "neutral" : marketFresh ? "healthy" : "warning"} /> {marketStatus}</span>
                  <span>窗口成交额 <b>{compact(chart.quoteVolume)} USDT</b></span>
                  <span>数据源 <b className={marketFresh ? "positive-text" : ""}>{market.source}</b></span>
                </div>
              </section>

              <section className="panel decision-panel">
                <div className="panel-heading">
                  <div><span className="panel-kicker"><Sparkles size={13} /> LATEST DECISION</span><h2>策略决策</h2></div>
                  <span className="timestamp">{decision?.strategyConfigVersion ? `配置 v${decision.strategyConfigVersion} · ` : ""}{timeAgo(decision?.timestamp, clock)}</span>
                </div>
                <div className="decision-orb-wrap">
                  <div className="decision-orb"><span>建议</span><strong>{decision?.action ?? "—"}</strong><i /></div>
                  <div className="decision-score"><span>置信度</span><strong>{pct(decision?.confidence, 0)}</strong></div>
                </div>
                <div className="decision-stats">
                  <div><span>预期净优势</span><strong>{decision?.expectedNetEdgePercent?.toFixed(2) ?? "—"}%</strong></div>
                  <div><span>胜率估计</span><strong>{pct(decision?.winProbability, 0)}</strong></div>
                  <div><span>执行结果</span><strong className="muted-value">{decision?.executionStatus ?? "—"}</strong></div>
                </div>
                <div className="reasoning"><BrainCircuit size={17} /><p>{decision?.reason ?? "等待首次策略决策。"}</p></div>
                {decisionIndicatorEntries(decision?.metadata).length > 0 && <details className="backtest-parameters"><summary>已收盘 K 线技术指标</summary><div className="decision-stats" aria-label="决策技术指标">{decisionIndicatorEntries(decision?.metadata).map((entry) => <div key={entry.key}><span>{entry.label}</span><strong>{entry.value}</strong></div>)}</div></details>}
                <button className="text-button" onClick={() => navigate("strategies")}>查看决策上下文 <ChevronRight size={14} /></button>
              </section>
            </div>

            <section className="panel event-strip">
              <div className="event-summary">
                <div className="event-icon"><SquareActivity size={21} /></div>
                <div><span className="panel-kicker">EVENT PIPELINE</span><h2>事件总线健康</h2></div>
                <span className="status-badge healthy"><Check size={12} /> HEALTHY</span>
              </div>
              <div className="queue-meter">
                <div><span>队列深度</span><b>{snapshot.events.queueDepth} / {snapshot.events.queueCapacity}</b></div>
                <div className="meter"><span style={{ width: `${Math.max(2, queueUtilization)}%` }} /></div>
                <small>剩余容量 {snapshot.events.queueCapacity - snapshot.events.queueDepth}</small>
              </div>
              <div className="event-number"><span>已接收</span><strong>{compact(snapshot.events.accepted)}</strong></div>
              <div className="event-number"><span>已消费</span><strong>{compact(snapshot.events.consumed)}</strong></div>
              <div className="event-number"><span>丢弃</span><strong className={snapshot.events.dropped ? "warning-text" : "positive-text"}>{snapshot.events.dropped}</strong></div>
              <button className="icon-button" aria-label="查看事件详情" onClick={() => navigate("events")}><ChevronRight size={17} /></button>
            </section>
          </>
        )}

        {activeView === "strategies" && <StrategiesView snapshot={snapshot} onActivate={activateStrategy} pendingId={strategyPending} dataMode={dataMode} onConfigurationSaved={(configuration) => { setSnapshot((current) => ({ ...current, strategies: current.strategies.map((strategy) => strategy.id === configuration.strategyId ? { ...strategy, params: configuration.params } : strategy) })); setPaperRefreshToken((current) => current + 1); }} />}
        {activeView === "events" && <EventsView events={snapshot.events} queueUtilization={queueUtilization} />}
        {activeView === "backtests" && <BacktestsView runs={snapshot.backtests} onCreate={openBacktest} clock={clock} />}
      </section>

      <aside className="right-rail">
        <section className="rail-card system-card">
          <div className="rail-card-heading"><span>系统状态</span><Activity size={16} /></div>
          <div className="system-state"><span className="pulse-ring"><i /></span><div><strong>{dataMode === "live" && !runtimeConnected ? "系统状态未确认" : snapshot.runtime.lastError ? "需要关注" : "系统运行稳定"}</strong><small>{dataMode === "live" ? runtimeConnected ? "来自实时后端" : "上次真实快照 · 同步中断" : "安全演示快照"}</small></div></div>
          {snapshot.runtime.lastError && <p className="runtime-error danger-text">{snapshot.runtime.lastError}</p>}
          <div className="health-list">
            <div><span>事件总线</span><b><i className={snapshot.events.running ? "healthy" : "warning"} />{snapshot.events.running ? "正常" : "停止"}</b></div>
            <div><span>WebSocket 行情</span><b><i className={!snapshot.runtime.marketDataStale ? "healthy" : "warning"} />{snapshot.runtime.marketDataStale ? "未就绪" : "正常"}</b></div>
            <div><span>执行模式</span><b><i className="neutral" />{snapshot.runtime.executionMode}</b></div>
          </div>
        </section>

        <section className="rail-card automation-card">
          <div className="rail-card-heading"><span>交易自动化</span><Bot size={16} /></div>
          <div className="automation-switch-row">
            <div><strong>{!task ? "未读取到交易任务" : task.running ? "循环运行中" : "当前已暂停"}</strong><small>{task ? "启动不会绕过后端安全门" : "同步任务状态后可操作"}</small></div>
            <button className={`toggle ${task?.running ? "on" : ""}`} onClick={toggleTask} disabled={!task || taskPending || (dataMode === "live" && !runtimeConnected && !task.running)} aria-label={task?.running ? "停止交易自动化" : "启动交易自动化"} aria-pressed={task?.running}>
              <span>{taskPending ? <LoaderCircle size={12} className="spin" /> : task?.running ? <Check size={12} /> : null}</span>
            </button>
          </div>
          <div className="loop-list">
            <LoopRow icon={BrainCircuit} name="策略决策" detail="每 30 分钟" when={decisionLoop?.nextRunAt ? timeAgoFuture(decisionLoop.nextRunAt, clock) : "待调度"} active={Boolean(task?.running)} />
            <LoopRow icon={Zap} name="事件扫描" detail="每 60 秒" when={eventLoop?.nextRunAt ? timeAgoFuture(eventLoop.nextRunAt, clock) : "待调度"} active={Boolean(task?.running)} />
          </div>
        </section>

        <section className="rail-card strategy-card">
          <div className="rail-card-heading"><span>可用策略</span><button onClick={() => navigate("strategies")}>全部 {snapshot.strategies.length}</button></div>
          <div className="mini-strategy-list">
            {snapshot.strategies.slice(0, 3).map((strategy, index) => (
              <button key={strategy.id} onClick={() => navigate("strategies")}>
                <span className={`strategy-glyph glyph-${index + 1}`}><Layers3 size={15} /></span>
                <span><strong>{strategy.id}</strong><small>{strategy.type} · {strategy.bar}</small></span>
                <i className={strategy.id === snapshot.runtime.activeStrategyId ? "healthy" : "neutral"} title={strategy.id === snapshot.runtime.activeStrategyId ? "当前策略" : "候选策略"} />
              </button>
            ))}
          </div>
        </section>

        <section className="rail-card activity-card">
          <div className="rail-card-heading"><span>最近活动</span><Clock3 size={16} /></div>
          <div className="activity-list">
            <ActivityItem color="green" title={decision ? "策略决策完成" : "策略决策"} detail={decision ? `${decision.action ?? "HOLD"} · ${timeAgo(decision.timestamp, clock)}` : "暂无决策记录"} />
            <ActivityItem color="blue" title="行情事件已消费" detail={`累计 ${compact(snapshot.events.consumed)} 条`} />
            <ActivityItem color="violet" title="最近回测" detail={snapshot.backtests[0] ? `${snapshot.backtests[0].request.strategyId} · ${snapshot.backtests[0].status} · ${pct(snapshot.backtests[0].totalReturn)}` : "暂无记录"} />
          </div>
        </section>
      </aside>

      {commandOpen && (
        <div className="modal-backdrop" onMouseDown={() => setCommandOpen(false)}>
          <section className="command-menu" role="dialog" aria-modal="true" aria-label="命令菜单" onMouseDown={(event) => event.stopPropagation()}>
            <div className="command-search"><Search size={18} /><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入命令或页面名称…" /><kbd>ESC</kbd></div>
            <div className="command-group"><span>快速跳转</span>{filteredCommands.map((item) => <button key={item.label} onClick={() => { if (item.command === "backtest") openBacktest(); else if (item.command === "refresh") void refresh(); else if (item.command === "overview" || item.command === "strategies" || item.command === "events") setActiveView(item.command); setCommandOpen(false); setQuery(""); }}><span><Command size={14} />{item.label}</span><small>{item.hint}</small></button>)}</div>
          </section>
        </div>
      )}

      {backtestOpen && (
        <div className="modal-backdrop" onMouseDown={() => setBacktestOpen(false)}>
          <section className="form-modal" role="dialog" aria-modal="true" aria-labelledby="backtest-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="form-modal-heading"><div><span className="panel-kicker">REPRODUCIBLE LAB</span><h2 id="backtest-title">创建策略回测</h2><p>使用后端的历史 K 线与执行模型验证当前策略。</p></div><button className="icon-button" onClick={() => setBacktestOpen(false)} aria-label="关闭"><X size={18} /></button></div>
            <form onSubmit={submitBacktest}>
              <label><span>策略</span><select name="strategyId" value={backtestSelection.strategyId} onChange={(event) => setBacktestSelection(backtestSelectionForStrategy(snapshot.strategies.find((strategy) => strategy.id === event.target.value)))}>{snapshot.strategies.map((strategy) => <option key={strategy.id} value={strategy.id}>{strategy.id}</option>)}</select></label>
              <div className="form-row"><label><span>交易标的</span><input name="instId" required value={backtestInstrument} onChange={(event) => setBacktestInstrument(event.target.value.toUpperCase())} placeholder="例如 BTC-USDT" /></label><label><span>K 线周期</span><select name="bar" value={backtestSelection.bar} onChange={(event) => setBacktestSelection((current) => ({ ...current, bar: event.target.value }))}>{(snapshot.strategies.find((strategy) => strategy.id === backtestSelection.strategyId)?.type === "intraday-technical" ? ["15m"] : ["1m", "5m", "15m"]).map((bar) => <option key={bar}>{bar}</option>)}</select></label></div>
              <div className="form-row"><label><span>开始时间（本地时间）</span><input name="from" type="datetime-local" required defaultValue={backtestRange.from} /></label><label><span>结束时间（本地时间）</span><input name="to" type="datetime-local" required defaultValue={backtestRange.to} /></label></div>
              <label><span>初始资金（{backtestInstrument.split("-")[1] || snapshot.runtime.quoteCcy || "USDT"}）</span><input name="initialCash" type="number" min="0" step="any" required defaultValue={paperCurrent?.initialCash ?? 1000} /></label>
              <div className="safety-note"><ShieldCheck size={17} /><p><strong>安全执行</strong>回测仅使用模拟 Broker，不会触发真实下单。</p></div>
              <div className="form-actions"><button type="button" className="secondary-button" onClick={() => setBacktestOpen(false)}>取消</button><button type="submit" className="primary-button" disabled={backtestPending}>{backtestPending ? <LoaderCircle size={15} className="spin" /> : <Play size={15} />}开始回测</button></div>
            </form>
          </section>
        </div>
      )}

      {toast && <div className="toast" role="status"><Check size={15} />{toast}</div>}
    </main>
  );
}

function MetricCard({ icon: Icon, label, value, delta, positive, detail, progress }: { icon: typeof Activity; label: string; value: string; delta: string; positive?: boolean; detail: string; progress?: number }) {
  return <article className="metric-card"><div className="metric-top"><span className="metric-icon"><Icon size={17} /></span><span className={`metric-delta ${positive ? "positive" : ""}`}>{delta}</span></div><span className="metric-label">{label}</span><strong>{value}</strong>{progress != null ? <div className="metric-progress"><span style={{ width: `${progress}%` }} /></div> : null}<small>{detail}</small></article>;
}

function LoopRow({ icon: Icon, name, detail, when, active }: { icon: typeof Activity; name: string; detail: string; when: string; active: boolean }) {
  return <div className="loop-row"><span><Icon size={15} /></span><div><strong>{name}</strong><small>{detail}</small></div><b className={active ? "" : "paused"}>{active ? when : "暂停"}</b></div>;
}

function ActivityItem({ color, title, detail }: { color: string; title: string; detail: string }) {
  return <div className="activity-item"><span className={color} /><div><strong>{title}</strong><small>{detail}</small></div></div>;
}

function StrategiesView({ snapshot, onActivate, pendingId, dataMode, onConfigurationSaved }: { snapshot: Snapshot; onActivate: (strategyId: string) => void; pendingId: string | null; dataMode: "live" | "demo"; onConfigurationSaved: (configuration: StrategyConfiguration) => void }) {
  const [editingId, setEditingId] = useState<string | null>(null);
  return <div className="detail-layout">
    <section className="panel detail-hero"><div><span className="panel-kicker"><BrainCircuit size={13} /> STRATEGY REGISTRY</span><h2>当前策略：{snapshot.runtime.activeStrategyId ?? "尚未选择"}</h2><p>参数保存后从下一次新决策开始生效；已开始的决策、历史成交和原有持仓保留原配置版本。</p></div><div className="hero-stat"><span>当前执行模式</span><strong>{snapshot.runtime.executionMode}</strong><small>{snapshot.runtime.liveEnabled ? "实盘门已开启" : `实盘门保持关闭 · rev ${snapshot.runtime.activeStrategyRevision ?? 0}`}</small></div></section>
    <section className="strategy-grid">{snapshot.strategies.map((strategy, index) => {
      const running = snapshot.runtime.activeStrategyId === strategy.id;
      const pending = pendingId === strategy.id;
      return <article className={`panel strategy-detail-card ${running ? "active-strategy" : ""}`} key={strategy.id}>
        <div className="strategy-detail-top"><span className={`strategy-glyph glyph-${(index % 3) + 1}`}><Layers3 size={18} /></span><span className={`status-badge ${running ? "healthy" : "idle"}`}>{running ? "ACTIVE" : strategy.enabled ? "READY" : "DISABLED"}</span></div>
        <h3>{strategy.id}</h3><p>{strategy.type === "intraday-technical" ? `${intradayModes.find((mode) => mode.value === strategy.params?.mode)?.label ?? "日内技术策略"} · 15 分钟现货做多 · 仅已收盘 K 线` : `${strategy.type} strategy`}</p>
        {strategy.type === "intraday-technical" && <p>{intradayModes.find((mode) => mode.value === strategy.params?.mode)?.description}</p>}
        <div className="strategy-metadata"><div><span>时间周期</span><strong>{strategy.bar}</strong></div><div><span>配置参数</span><strong>{Object.keys(strategy.params ?? {}).length}</strong></div><div><span>决策参与</span><strong>{running ? "当前" : "候选"}</strong></div></div>
        <button className="strategy-activate" disabled={!strategy.enabled || running || Boolean(pendingId)} onClick={() => onActivate(strategy.id)}>{pending ? <LoaderCircle size={14} className="spin" /> : running ? <Check size={14} /> : <Zap size={14} />}{running ? "当前策略" : dataMode === "live" ? "设为当前策略" : "演示切换"}</button>
        <button className="strategy-edit" aria-expanded={editingId === strategy.id} onClick={() => setEditingId(editingId === strategy.id ? null : strategy.id)}><Layers3 size={14} />{editingId === strategy.id ? "收起参数与版本" : "修改参数 / 查看版本"}<ChevronRight size={14} /></button>
      </article>;
    })}</section>
    {editingId && <ConfigurationEditor key={`${dataMode}-${editingId}`} strategyId={editingId} connected={dataMode === "live"} onSaved={onConfigurationSaved} />}
  </div>;
}

function displayTimestamp(value: ApiTimestamp | null | undefined) {
  const milliseconds = apiTimestampMillis(value);
  return milliseconds == null ? "暂无记录" : new Date(milliseconds).toLocaleString("zh-CN", { hour12: false });
}

function ConfigurationEditor({ strategyId, connected, onSaved }: { strategyId: string; connected: boolean; onSaved: (configuration: StrategyConfiguration) => void }) {
  const [configuration, setConfiguration] = useState<StrategyConfiguration | null>(null);
  const [history, setHistory] = useState<StrategyConfiguration[]>([]);
  const [values, setValues] = useState<Record<string, string | boolean | null>>({});
  const [reason, setReason] = useState("");
  const [pending, setPending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [historyPending, setHistoryPending] = useState(false);
  const [historyHasMore, setHistoryHasMore] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const historyOffset = useRef(0);
  const configurationLoadGeneration = useRef(0);
  const historyAbort = useRef<AbortController | null>(null);
  const parameterFields = configuration ? parameterFieldsForStrategy(configuration.type) : [];

  useEffect(() => {
    const abort = new AbortController();
    configurationLoadGeneration.current += 1;
    historyAbort.current?.abort();
    const timer = window.setTimeout(() => {
      if (!connected) { setLoading(false); return; }
      setLoading(true);
      setError(null);
      setHistoryError(null);
      setHistoryPending(false);
      Promise.all([
        request<StrategyConfiguration>(`/api/trading/strategies/${encodeURIComponent(strategyId)}/configuration`, { signal: abort.signal }),
        request<StrategyConfiguration[]>(`/api/trading/strategies/${encodeURIComponent(strategyId)}/configuration/history?offset=0&limit=100`, { signal: abort.signal }),
      ]).then(([current, versions]) => {
        if (abort.signal.aborted) return;
        setConfiguration(current);
        setHistory(versions);
        historyOffset.current = versions.length;
        setHistoryHasMore(versions.length === 100);
        setValues(Object.fromEntries(Object.entries(current.params).map(([key, value]) => [key, typeof value === "boolean" || value == null ? value : String(value)])));
        setReason("");
      }).catch((failure) => { if (!abort.signal.aborted) setError(`配置读取失败：${errorMessage(failure)}`); }).finally(() => { if (!abort.signal.aborted) setLoading(false); });
    }, 0);
    return () => { window.clearTimeout(timer); abort.abort(); historyAbort.current?.abort(); };
  }, [strategyId, connected, reload]);

  const loadOlderVersions = async () => {
    if (!connected || loading || pending || historyPending || !historyHasMore) return;
    const abort = new AbortController();
    const generation = configurationLoadGeneration.current;
    historyAbort.current = abort;
    setHistoryPending(true); setHistoryError(null);
    try {
      const older = await request<StrategyConfiguration[]>(`/api/trading/strategies/${encodeURIComponent(strategyId)}/configuration/history?offset=${historyOffset.current}&limit=100`, { signal: abort.signal });
      if (abort.signal.aborted || configurationLoadGeneration.current !== generation) return;
      // Advance by fetched rows, including duplicates caused by newer versions inserted at the head.
      historyOffset.current += older.length;
      setHistory((current) => [...current, ...older.filter((item) => !current.some((existing) => existing.version === item.version))].sort((left, right) => right.version - left.version));
      setHistoryHasMore(older.length === 100);
    } catch (failure) {
      if (!abort.signal.aborted && configurationLoadGeneration.current === generation) setHistoryError(`更早版本读取失败：${errorMessage(failure)}`);
    } finally {
      if (!abort.signal.aborted && configurationLoadGeneration.current === generation) setHistoryPending(false);
    }
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!connected || !configuration || pending || historyPending) return;
    setError(null);
    setNotice(null);
    let payload;
    try { payload = buildStrategyParametersPayload({ expectedVersion: configuration.version, reason, params: values, type: configuration.type }); }
    catch (failure) { setError(errorMessage(failure)); return; }
    setPending(true);
    try {
      const updated = await request<StrategyConfiguration>(`/api/trading/strategies/${encodeURIComponent(strategyId)}/configuration`, { method: "PUT", body: JSON.stringify(payload) });
      setConfiguration(updated);
      if (!history.some((item) => item.version === updated.version)) historyOffset.current += 1;
      setHistory((current) => [updated, ...current.filter((item) => item.version !== updated.version)]);
      setValues(Object.fromEntries(Object.entries(updated.params).map(([key, value]) => [key, typeof value === "boolean" || value == null ? value : String(value)])));
      setReason("");
      setNotice(`已保存 v${updated.version}，后续新决策使用此版本。历史数据保留原版本。`);
      onSaved(updated);
    } catch (failure) {
      setError(failure instanceof ApiRequestError && failure.status === 409 ? "配置已被其他页面修改。你的输入尚未保存；先重新加载最新版本，再重新修改。" : `保存失败，参数未生效：${errorMessage(failure)}`);
    } finally { setPending(false); }
  };

  return <section className="panel configuration-panel" aria-label={`${strategyId} 参数配置`}>
    <div className="panel-heading"><div><h2>策略参数</h2><p className="section-description">{strategyId}{configuration && ` · 当前 v${configuration.version} · ${displayTimestamp(configuration.changedAt)}`}</p></div><button className="secondary-button" disabled={!connected || loading || pending} onClick={() => { setNotice(null); setReload((current) => current + 1); }}><RefreshCw size={14} />重新加载</button></div>
    {!connected ? <p className="empty-message">连接后端后可读取并修改参数。演示数据不会保存配置。</p> : loading ? <p className="empty-message"><LoaderCircle size={15} className="spin" />正在读取配置与历史版本…</p> : null}
    {error && <p className="inline-error" role="alert">{error}</p>}
    {notice && <p className="inline-success" role="status">{notice}</p>}
    {connected && configuration && !loading && <>
      {parameterFields.length === 0 ? <p className="empty-message">暂不支持编辑策略类型：{configuration.type}。可在下方查看完整历史参数。</p> : <form className="configuration-form" onSubmit={save}>
        <fieldset disabled={pending}><legend className="sr-only">参数值</legend><div className="parameter-grid">
          {configuration.type === "intraday-technical" && <label><span>日内入场模式</span><select name="mode" required value={String(values.mode ?? "")} onChange={(event) => setValues((current) => ({ ...current, mode: event.target.value }))}>{intradayModes.map((mode) => <option key={mode.value} value={mode.value}>{mode.label}</option>)}</select><small>{intradayModes.find((mode) => mode.value === values.mode)?.description}固定 15m，仅使用已收盘 K 线。</small></label>}
          {parameterFields.map((field) => <label key={field.key}><span>{field.label}</span><input name={field.key} type="number" required={!field.nullable} min={field.integer || field.allowZero ? field.min : undefined} max={field.max} step={field.step} value={String(values[field.key] ?? "")} onChange={(event) => setValues((current) => ({ ...current, [field.key]: event.target.value }))} /><small>{field.hint}</small></label>)}
          {configuration.type === "threshold-event" && <label className="checkbox-field"><input type="checkbox" checked={values["require-confirmed-candle"] === true || values["require-confirmed-candle"] === "true"} onChange={(event) => setValues((current) => ({ ...current, "require-confirmed-candle": event.target.checked }))} /><span>仅使用已收盘 K 线</span></label>}
        </div><label className="change-reason"><span>修改原因</span><input name="reason" required maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="例如：提高价量门槛，观察交易频率变化" /></label></fieldset>
        <div className="configuration-footer"><p>基于 v{configuration.version} 保存新版本。K 线窗口还受行情采集上限校验；下单数量仍受全局执行上限约束。{configuration.type === "intraday-technical" && "止损止盈按收盘价检查，可能发生跳空与滑点；交易频率取决于实际信号。"}</p><button className="primary-button" type="submit" disabled={pending || historyPending}>{pending ? <LoaderCircle size={15} className="spin" /> : <Check size={15} />}保存新版本</button></div>
      </form>}
      <div className="configuration-history"><h3>配置版本记录</h3><p className="section-description">展开版本可查看当时的完整参数，以及相对上一版本的变化。</p>
        {history.map((item, index) => {
          const previous = history[index + 1];
          return <details key={item.version} className="version-record"><summary><span className="version-badge">v{item.version}</span><strong>{item.reason}</strong><time>{displayTimestamp(item.changedAt)}</time></summary><dl>{Object.entries(item.params).map(([key, value]) => {
            const before = previous?.params[key];
            const changed = previous && String(before) !== String(value);
            return <div key={key} className={changed ? "parameter-changed" : ""}><dt>{parameterFieldsForStrategy(item.type).find((field) => field.key === key)?.label ?? (key === "require-confirmed-candle" ? "仅使用已收盘 K 线" : key === "mode" ? "日内入场模式" : key)}</dt><dd>{changed && <><del>{String(before ?? "默认")}</del><span aria-hidden="true"> → </span></>}{key === "mode" ? intradayModes.find((mode) => mode.value === value)?.label ?? String(value) : String(value ?? "默认")}</dd></div>;
          })}</dl></details>;
        })}
        {historyError && <p className="inline-error" role="alert">{historyError}</p>}
        {historyHasMore && <button className="secondary-button" disabled={historyPending || pending} onClick={() => void loadOlderVersions()}>{historyPending ? <LoaderCircle size={14} className="spin" /> : <Layers3 size={14} />}{historyPending ? "正在加载…" : "加载更早版本"}</button>}
      </div>
    </>}
  </section>;
}

type AiAdvisorStatus = { enabled: boolean; autoApplyEligible: boolean; telegramEnabled: boolean; reviewIntervalMs: number };
type AiAdvisorReview = { reviewId: string; startedAt: ApiTimestamp; outcome: string; detail: string; proposalJson?: string; notificationStatus: string };

function TradingAiPanel({ dataMode }: { dataMode: "live" | "demo" }) {
  const [status, setStatus] = useState<AiAdvisorStatus | null>(null);
  const [reviews, setReviews] = useState<AiAdvisorReview[]>([]);
  const [task, setTask] = useState<AutomationTask | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const changing = useRef(false);
  const loadGeneration = useRef(0);
  useEffect(() => {
    if (dataMode !== "live") return;
    const controller = new AbortController();
    let busy = false;
    const load = async () => {
      if (busy || changing.current) return;
      busy = true;
      const generation = loadGeneration.current;
      try {
        const [next, history, currentTask] = await Promise.all([
          request<AiAdvisorStatus>("/api/trading/ai/status", { signal: controller.signal }),
          request<AiAdvisorReview[]>("/api/trading/ai/reviews?limit=5", { signal: controller.signal }),
          request<AutomationTask>("/api/automation/tasks/trading-ai", { signal: controller.signal }),
        ]);
        if (!controller.signal.aborted && !changing.current && generation === loadGeneration.current) {
          setStatus(next); setReviews(history); setTask(currentTask); setError("");
        }
      } catch (e) { if (!controller.signal.aborted && generation === loadGeneration.current) setError(errorMessage(e)); }
      finally { busy = false; }
    };
    void load();
    const timer = window.setInterval(() => { void load(); }, 30_000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [dataMode, refresh]);

  const toggle = async () => {
    if (!task || changing.current) return;
    changing.current = true; loadGeneration.current += 1; setPending(true); setError("");
    try {
      const updated = await request<AutomationTask>(`/api/automation/tasks/trading-ai/${task.running ? "stop" : "start"}`, { method: "POST" });
      setTask(updated);
    } catch (e) { setError(errorMessage(e)); }
    finally { changing.current = false; setPending(false); setRefresh(value => value + 1); }
  };
  const outcomes: Record<string, string> = {
    ANALYZING: "评估中", READY: "建议已生成", APPLYING: "应用中／中断后需核对", APPLIED: "已自动调整",
    HELD: "保持策略", UNCHANGED: "无需变化", RECOMMENDED: "仅建议", LOW_CONFIDENCE: "信心不足，未调整",
    POSITION_NOT_FLAT: "等待空仓模拟会话", CONFLICT: "状态已变化，未调整", STALE: "评估过期，未调整",
    REJECTED: "校验未通过", FAILED: "评估失败", APPLY_UNKNOWN: "调整结果待核对", LEADERSHIP_LOST: "已失去执行租约", CANCELLED: "任务已暂停，未调整",
  };
  const notifications: Record<string, string> = { NOT_REQUIRED: "无需通知", DISABLED: "通知未启用", THROTTLED: "通知冷却中",
    SENDING: "投递中／中断后需核对", SENT: "已通知", UNKNOWN: "投递结果未知", CONFIG_ERROR: "通知配置错误" };
  return <section className="panel ai-advisor-panel">
    <div className="panel-heading">
      <div><h2>AI 策略顾问</h2><p className="panel-note">{dataMode === "demo" ? "演示模式不调用 AI" : !status ? "正在读取配置" : !status.enabled ? "未启用 · 配置环境变量后可开始评估" : `${task?.running ? "运行中" : "已暂停"} · ${status.autoApplyEligible ? "模拟空仓时自动调整" : "仅生成建议"} · 评估间隔 ${Math.round(status.reviewIntervalMs / 60000)} 分钟 · 独立 TG ${status.telegramEnabled ? "已启用" : "未启用"}`}</p></div>
      {dataMode === "live" && <button className="secondary-button" disabled={!task || !status?.enabled || pending} onClick={() => void toggle()}>{pending ? "处理中…" : task?.running ? "暂停 AI" : "启动 AI"}</button>}
    </div>
    {error && <p className="ai-advisor-error" role="alert">{error}（保留上次读取结果）</p>}
    {reviews.length === 0 && <p className="panel-note ai-advisor-empty">暂无评估记录。AI 不会修改策略代码；需要补充逻辑时会发送建议。</p>}
    {reviews.map(review => {
      let proposal: { marketRegime?: string; strategyId?: string; parameters?: Record<string, unknown>; logicSuggestion?: string } = {};
      try { proposal = JSON.parse(review.proposalJson || "{}") ?? {}; } catch { /* Keep the durable outcome visible. */ }
      return <details key={review.reviewId} className="ai-advisor-review">
        <summary><span>{outcomes[review.outcome] ?? review.outcome}</span><span>{displayTimestamp(review.startedAt)} · {notifications[review.notificationStatus] ?? review.notificationStatus}</span></summary>
        {proposal.marketRegime && <p>行情：{proposal.marketRegime}</p>}
        <p>{review.detail || "评估尚未完成；长时间无变化时请核对任务状态。"}</p>
        {proposal.strategyId && <p>目标策略：{proposal.strategyId}</p>}
        {proposal.parameters && Object.keys(proposal.parameters).length > 0 && <pre>{JSON.stringify(proposal.parameters, null, 2)}</pre>}
        {proposal.logicSuggestion && <p>逻辑建议：{proposal.logicSuggestion}</p>}
        <small>评估编号：{review.reviewId}</small>
      </details>;
    })}
  </section>;
}

function PaperSimulationPanel({ dataMode, executionMode, refreshToken, onCurrentChange }: { dataMode: "live" | "demo"; executionMode: string; refreshToken: number; onCurrentChange: (account: PaperAccount | null) => void }) {
  const [current, setCurrent] = useState<PaperAccount | null>(null);
  const [account, setAccount] = useState<PaperAccount | null>(null);
  const [sessions, setSessions] = useState<PaperAccount[]>([]);
  const [selection, setSelection] = useState("");
  const [equity, setEquity] = useState<PaperEquityPoint[]>([]);
  const [fills, setFills] = useState<PaperFill[]>([]);
  const [versionFilter, setVersionFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [blockingLoad, setBlockingLoad] = useState(true);
  const [pending, setPending] = useState(false);
  const [morePending, setMorePending] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState({ equity: false, fills: false, sessions: false });
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [initialCash, setInitialCash] = useState("10000");
  const [deleteTarget, setDeleteTarget] = useState<PaperAccount | null>(null);
  const loadAbort = useRef<AbortController | null>(null);
  const mutationPending = useRef(false);
  const visibleSession = useRef<string | null>(null);
  const loadGeneration = useRef(0);
  const loadedSelection = useRef<string | null>(null);
  const historyCache = useRef<{ sessionId: string; equity: PaperEquityPoint[]; fills: PaperFill[] } | null>(null);
  const available = dataMode === "live" && executionMode === "PAPER";

  useEffect(() => {
    if (mutationPending.current) return;
    const abort = new AbortController();
    loadAbort.current = abort;
    const timer = window.setTimeout(() => {
      if (abort.signal.aborted || mutationPending.current) return;
      loadGeneration.current += 1;
      if (!available) {
        loadedSelection.current = null;
        setCurrent(null); setAccount(null); setSessions([]); setEquity([]); setFills([]); setLoading(false); setBlockingLoad(false); onCurrentChange(null);
        return;
      }
      // A refresh of the same selection keeps the existing panel in place.
      // Switching sessions must not present the previous session as the new one.
      const keepVisible = loadedSelection.current === selection;
      setLoading(true); setBlockingLoad(!keepVisible); setError(null);
      if (!keepVisible) { loadedSelection.current = null; setAccount(null); setEquity([]); setFills([]); visibleSession.current = null; }
      const load = async () => {
        const [active, list] = await Promise.all([
          request<PaperAccount | null>("/api/trading/paper/account", { signal: abort.signal }),
          request<PaperAccount[]>("/api/trading/paper/sessions?limit=20", { signal: abort.signal }),
        ]);
        const selected = selection && selection !== active?.sessionId ? await request<PaperAccount>(`/api/trading/paper/sessions/${encodeURIComponent(selection)}`, { signal: abort.signal }) : active;
        const [points, trades] = selected ? await Promise.all([
          request<PaperEquityPoint[]>(`/api/trading/paper/sessions/${encodeURIComponent(selected.sessionId)}/equity?limit=100`, { signal: abort.signal }),
          request<PaperFill[]>(`/api/trading/paper/sessions/${encodeURIComponent(selected.sessionId)}/fills?limit=100`, { signal: abort.signal }),
        ]) : [[], []];
        if (abort.signal.aborted) return;
        setCurrent(active); onCurrentChange(active); setAccount(selected);
        setSessions(selected && !list.some((item) => item.sessionId === selected.sessionId) ? [...list, selected] : list);
        const cached = historyCache.current?.sessionId === selected?.sessionId ? historyCache.current : null;
        const mergedEquity = mergeContiguousHistory(points, cached?.equity ?? []) as PaperEquityPoint[];
        const mergedFills = mergeContiguousHistory(trades, cached?.fills ?? []) as PaperFill[];
        setEquity(mergedEquity); setFills(mergedFills); visibleSession.current = selected?.sessionId ?? null;
        historyCache.current = selected ? { sessionId: selected.sessionId, equity: mergedEquity, fills: mergedFills } : null;
        setHasMore({ equity: points.length === 100, fills: trades.length === 100, sessions: list.length === 20 });
        loadedSelection.current = selection;
      };
      load().catch((failure) => {
        if (!abort.signal.aborted) {
          if (!keepVisible) setCurrent(null);
          onCurrentChange(null);
          setError(`模拟数据读取失败：${errorMessage(failure)}。${keepVisible ? "当前显示上次读取的数据。" : ""}请重新加载后操作。`);
        }
      }).finally(() => { if (!abort.signal.aborted) { setLoading(false); setBlockingLoad(false); } });
    }, 0);
    return () => { window.clearTimeout(timer); abort.abort(); };
  }, [available, refreshToken, selection, reload, onCurrentChange]);

  const startSession = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!available || loading || pending || error) return;
    let payload;
    try { payload = buildPaperSessionPayload({ initialCash, expectedSessionId: current?.sessionId ?? null, expectedVersion: current?.version ?? null }); }
    catch (failure) { setError(errorMessage(failure)); return; }
    mutationPending.current = true; loadAbort.current?.abort(); loadGeneration.current += 1;
    setPending(true); setError(null); setNotice(null); setDeleteTarget(null);
    try {
      const created = await request<PaperAccount>("/api/trading/paper/sessions", { method: "POST", body: JSON.stringify(payload) });
      setCurrent(created); onCurrentChange(created); setAccount(created); setFills([]); setEquity([]); setVersionFilter(""); setSelection("");
      setNotice(`新模拟会话已创建，初始资金 ${number(created.initialCash, 2)} ${created.quoteCcy}。此前会话保留在历史中。`);
      setReload((value) => value + 1);
    } catch (failure) {
      setError(failure instanceof ApiRequestError && failure.status === 409 ? "模拟账户或运行状态已变化。新会话未创建；请重新加载后再试。" : `新会话创建失败：${errorMessage(failure)}`);
    } finally { mutationPending.current = false; setPending(false); }
  };

  const deleteSession = async () => {
    if (!available || !deleteTarget || loading || pending || morePending || error) return;
    const target = deleteTarget;
    mutationPending.current = true; loadAbort.current?.abort(); loadGeneration.current += 1;
    setPending(true); setError(null); setNotice(null);
    try {
      await request<void>(`/api/trading/paper/sessions/${encodeURIComponent(target.sessionId)}?expectedVersion=${target.version}`, { method: "DELETE" });
      if (current?.sessionId === target.sessionId) { setCurrent(null); onCurrentChange(null); }
      setAccount(null); setEquity([]); setFills([]); setSelection(""); setVersionFilter("");
      setSessions((items) => items.filter((item) => item.sessionId !== target.sessionId));
      historyCache.current = null; loadedSelection.current = null; visibleSession.current = null;
      setNotice(`模拟会话 ${target.sessionId} 及其成交、资金记录已删除。`);
      setReload((value) => value + 1);
    } catch (failure) {
      setError(failure instanceof ApiRequestError && failure.status === 409
        ? "模拟会话已变化或运行模式不允许删除。请重新加载；若仍有模拟成交，可先停止交易自动化后重试。"
        : `删除结果未确认：${errorMessage(failure)}。请重新加载核对会话，勿重复提交。`);
    } finally { mutationPending.current = false; setPending(false); setDeleteTarget(null); }
  };

  const loadMore = async (kind: "equity" | "fills" | "sessions") => {
    if (!account || morePending || loading || pending) return;
    const capturedSession = account.sessionId;
    const capturedGeneration = loadGeneration.current;
    const list = kind === "equity" ? equity : kind === "fills" ? fills : sessions;
    const beforeId = Math.min(...list.map((item) => item.id));
    if (!Number.isFinite(beforeId)) return;
    setMorePending(kind);
    try {
      if (kind === "sessions") {
        const older = await request<PaperAccount[]>(`/api/trading/paper/sessions?limit=20&beforeId=${beforeId}`);
        if (visibleSession.current !== capturedSession || loadGeneration.current !== capturedGeneration) return;
        setSessions((items) => [...items, ...older.filter((item) => !items.some((existing) => existing.id === item.id))]);
        setHasMore((value) => ({ ...value, sessions: older.length === 20 }));
      } else if (kind === "equity") {
        const older = await request<PaperEquityPoint[]>(`/api/trading/paper/sessions/${encodeURIComponent(capturedSession)}/equity?limit=100&beforeId=${beforeId}`);
        if (visibleSession.current !== capturedSession || loadGeneration.current !== capturedGeneration) return;
        setEquity((items) => { const merged = [...items, ...older.filter((item) => !items.some((existing) => existing.id === item.id))]; if (historyCache.current?.sessionId === capturedSession) historyCache.current.equity = merged; return merged; });
        setHasMore((value) => ({ ...value, equity: older.length === 100 }));
      } else {
        const older = await request<PaperFill[]>(`/api/trading/paper/sessions/${encodeURIComponent(capturedSession)}/fills?limit=100&beforeId=${beforeId}`);
        if (visibleSession.current !== capturedSession || loadGeneration.current !== capturedGeneration) return;
        setFills((items) => { const merged = [...items, ...older.filter((item) => !items.some((existing) => existing.id === item.id))]; if (historyCache.current?.sessionId === capturedSession) historyCache.current.fills = merged; return merged; });
        setHasMore((value) => ({ ...value, fills: older.length === 100 }));
      }
    } catch (failure) { setError(`历史记录读取失败：${errorMessage(failure)}`); }
    finally { setMorePending(null); }
  };

  const versions = Array.from(new Set([...equity, ...fills].filter((item) => item.strategyId && item.strategyConfigVersion != null).map((item) => `${item.strategyId}@${item.strategyConfigVersion}`))).sort();
  const shownFills = filterConfigurationEvents(fills, versionFilter) as PaperFill[];
  const shownEquity = filterConfigurationEvents(equity, versionFilter) as PaperEquityPoint[];

  return <section className="panel simulation-panel" aria-label="模拟资金与效果">
    <div className="panel-heading"><div><h2>模拟资金与效果</h2><p className="section-description">独立资金会话保留参数版本、成交和净值，观察策略实际模拟效果。</p></div>{available && <button className="secondary-button" disabled={pending || loading} onClick={() => setReload((value) => value + 1)}><RefreshCw size={14} />重新加载</button>}</div>
    {executionMode !== "PAPER" ? <p className="empty-message">当前为 {executionMode} 模式。模拟资金操作仅在 PAPER 模式开放。</p> : !available ? <p className="empty-message">连接后端后，在这里设置初始资金并开始模拟。演示快照不会创建资金会话。</p> : <>
      {error && <p className="inline-error" role="alert">{error}</p>}
      {notice && <p className="inline-success" role="status">{notice}</p>}
      {blockingLoad && <p className="empty-message" role="status"><LoaderCircle size={15} className="spin" />正在读取模拟资金与历史…</p>}
      {!loading && !current && !error && <p className="empty-message">尚未设置模拟资金。创建第一个会话后，后续模拟成交会计入这笔资金。</p>}
      <details className="session-create" open={!current}>
        <summary>{current ? "设置新初始资金，开始独立模拟" : "设置初始资金"}</summary>
        <form onSubmit={startSession}><label><span>初始资金（计价币）</span><input name="paperInitialCash" type="number" min="0.00000001" max="1000000000000" step="0.00000001" required value={initialCash} onChange={(event) => { setInitialCash(event.target.value); if (error?.startsWith("初始资金")) setError(null); }} disabled={pending || loading} /></label><button className="primary-button" type="submit" disabled={pending || loading || Boolean(error)}>{pending ? <LoaderCircle size={15} className="spin" /> : <Play size={15} />}创建新模拟会话</button></form>
        <p>新会话从零持仓开始。旧会话的资金、持仓与历史会保留为归档，不再继续模拟；当前资金不会被直接改写。</p>
      </details>
      {sessions.length > 0 && <div className="simulation-toolbar"><label><span>查看模拟会话</span><select aria-label="查看模拟会话" value={selection} disabled={loading || pending || Boolean(morePending)} onChange={(event) => { setSelection(event.target.value); setVersionFilter(""); setError(null); setNotice(null); setDeleteTarget(null); }}><option value="">当前会话{current ? ` · ${current.sessionId}` : ""}</option>{sessions.filter((item) => item.sessionId !== current?.sessionId).map((item) => <option key={item.sessionId} value={item.sessionId}>{displayTimestamp(item.createdAt)} · {number(item.initialCash, 2)} {item.quoteCcy} · {item.sessionId}</option>)}</select></label>{hasMore.sessions && <button className="secondary-button" disabled={loading || Boolean(morePending)} onClick={() => void loadMore("sessions")}>更早会话</button>}</div>}
      {account && !blockingLoad && <>
        <div className="session-identity"><span className={`status-badge ${account.status === "ACTIVE" ? "healthy" : "idle"}`}>{account.status === "ACTIVE" ? "当前模拟" : "历史归档"}</span><strong>{account.sessionId}</strong><span>{account.instId} · {account.quoteCcy}</span><span>{displayTimestamp(account.createdAt)} 开始</span><button className="secondary-button session-delete-button" disabled={loading || pending || Boolean(morePending) || Boolean(error)} onClick={() => setDeleteTarget(account)}><Trash2 size={13} />删除此会话</button></div>
        {deleteTarget && <div className="session-delete-confirm" role="group" aria-label="确认删除模拟会话">
          <strong>删除模拟会话 {deleteTarget.sessionId}？</strong>
          <p>会话的模拟资金、持仓、成交和资金曲线将永久删除，无法恢复。{deleteTarget.status === "ACTIVE" && "删除当前会话后需重新设置初始资金，才能继续产生模拟成交。"}</p>
          <div><button className="secondary-button session-delete-button" disabled={loading || pending || Boolean(error)} onClick={deleteSession}>{pending ? <LoaderCircle size={13} className="spin" /> : <Trash2 size={13} />}确认删除</button><button className="secondary-button" disabled={pending} onClick={() => setDeleteTarget(null)}>取消</button></div>
        </div>}
        <dl className="capital-summary">
          <div><dt>资产净值</dt><dd>{number(account.equity, 2)}<small>{account.quoteCcy}</small></dd></div>
          <div><dt>初始资金</dt><dd>{number(account.initialCash, 2)}</dd></div>
          <div><dt>可用现金</dt><dd>{number(account.cash, 2)}</dd></div>
          <div><dt>已实现盈亏</dt><dd className={account.realizedPnl < 0 ? "danger-text" : "positive-text"}>{number(account.realizedPnl, 2)}</dd></div>
          <div><dt>未实现盈亏</dt><dd className={account.unrealizedPnl == null ? "" : account.unrealizedPnl < 0 ? "danger-text" : "positive-text"}>{number(account.unrealizedPnl, 2)}</dd></div>
          <div><dt>会话累计收益率</dt><dd className={account.totalReturnPercent == null ? "" : account.totalReturnPercent < 0 ? "danger-text" : "positive-text"}>{account.totalReturnPercent == null ? "—" : `${Number(account.totalReturnPercent).toFixed(2)}%`}</dd></div>
        </dl>
        <p className={`valuation-note ${account.valuationStale ? "warning-text" : ""}`}>估值时间：{displayTimestamp(account.valuationAt)}{account.valuationStale ? " · 行情估值已过期，等待新价格" : ""}。持仓 {number(account.positionQuantity, 8)}，累计手续费 {number(account.totalFeesQuote, 4)} {account.quoteCcy}。</p>
        <div className="version-filter"><label><span>按成交 / 估值所属配置筛选</span><select aria-label="按配置版本筛选记录" value={versionFilter} onChange={(event) => setVersionFilter(event.target.value)}><option value="">全部配置版本</option>{versions.map((version) => <option key={version} value={version}>{version.replace(/@(\d+)$/, " · v$1")}</option>)}</select></label><p>版本筛选仅定位记录。净值包含延续持仓与行情变化，不代表该版本独立收益；需要独立比较时创建新模拟。</p></div>
        <PaperEquityChart points={equity} initialCash={Number(account.initialCash)} versionFilter={versionFilter} quoteCcy={account.quoteCcy} />
        <div className="ledger-grid">
          <section className="ledger-section"><h3>模拟成交 <small>{shownFills.length} 条已加载记录</small></h3><div className="ledger-table-wrap"><table className="ledger-table"><thead><tr><th>时间 / 配置</th><th>方向 / 数量</th><th>成交价</th><th>现金变化</th><th>手续费 / 实现盈亏</th></tr></thead><tbody>{shownFills.map((fill) => <tr key={fill.id}><td>{displayTimestamp(fill.createdAt)}<small>{fill.strategyId} · v{fill.strategyConfigVersion}</small></td><td>{fill.side}<small>{number(fill.filledBase, 8)}</small></td><td>{number(fill.price, 2)}</td><td className={fill.cashDelta < 0 ? "danger-text" : "positive-text"}>{number(fill.cashDelta, 2)}</td><td>{number(fill.feeQuote, 4)}<small>{number(fill.realizedPnl, 2)}</small></td></tr>)}</tbody></table>{!shownFills.length && <p className="empty-message">{versionFilter ? "已加载记录中，此配置暂无成交。" : "暂无模拟成交。等待策略产生并通过风控的买卖决策。"}</p>}</div>{hasMore.fills && <button className="secondary-button" disabled={Boolean(morePending)} onClick={() => void loadMore("fills")}>{morePending === "fills" ? "正在加载…" : "加载更早成交"}</button>}</section>
          <section className="ledger-section"><h3>资金变化记录 <small>{shownEquity.length} 条已加载记录</small></h3><div className="equity-record-list">{shownEquity.map((point) => <div key={point.id}><span>{displayTimestamp(point.timestamp)}<small>{point.kind === "INITIAL" ? "初始资金" : point.kind === "FILL" ? "模拟成交后" : "行情估值"}{point.strategyId && ` · ${point.strategyId} v${point.strategyConfigVersion}`}</small></span><strong>{number(point.equity, 2)}<small>现金 {number(point.cash, 2)}</small></strong></div>)}{!shownEquity.length && <p className="empty-message">{versionFilter ? "已加载记录中，此配置暂无估值。" : "等待首条资金记录。"}</p>}</div>{hasMore.equity && <button className="secondary-button" disabled={Boolean(morePending)} onClick={() => void loadMore("equity")}>{morePending === "equity" ? "正在加载…" : "加载更早资金记录"}</button>}</section>
        </div>
      </>}
    </>}
  </section>;
}

function PaperEquityChart({ points, initialCash, versionFilter, quoteCcy }: { points: PaperEquityPoint[]; initialCash: number; versionFilter: string; quoteCcy: string }) {
  const ordered = [...points].filter((item) => item.equity != null && Number.isFinite(Number(item.equity))).sort((a, b) => (apiTimestampMillis(a.timestamp) ?? 0) - (apiTimestampMillis(b.timestamp) ?? 0) || a.id - b.id);
  if (!ordered.length) return <p className="empty-message">资金曲线会在模拟开始后生成。</p>;
  const values = [...ordered.map((point) => Number(point.equity)), initialCash];
  const min = Math.min(...values);
  const max = Math.max(...values);
  const padding = Math.max((max - min) * .12, max * .001, 1);
  const low = min - padding;
  const high = max + padding;
  const start = apiTimestampMillis(ordered[0].timestamp) ?? 0;
  const end = apiTimestampMillis(ordered.at(-1)!.timestamp) ?? start;
  const x = (point: PaperEquityPoint) => end === start ? 410 : 22 + (((apiTimestampMillis(point.timestamp) ?? start) - start) / (end - start)) * 776;
  const y = (value: number) => 144 - ((value - low) / (high - low)) * 124;
  return <figure className="equity-chart"><figcaption><strong>会话资金曲线</strong><span>净值（{quoteCcy}） · 已加载 {ordered.length} 个快照</span></figcaption><div className="equity-chart-body"><div className="equity-chart-scale"><span>{number(high, 2)}</span><span>{number(low, 2)}</span></div><svg viewBox="0 0 820 166" role="img" aria-label={`模拟会话净值，从 ${number(ordered[0].equity, 2)} 到 ${number(ordered.at(-1)!.equity, 2)} ${quoteCcy}，虚线为初始资金`} preserveAspectRatio="none"><line x1="22" x2="798" y1={y(initialCash)} y2={y(initialCash)} className="initial-capital-line" /><polyline points={ordered.map((point) => `${x(point)},${y(Number(point.equity))}`).join(" ")} className={`equity-line ${versionFilter ? "filtered" : ""}`} />{ordered.filter((point) => !versionFilter || `${point.strategyId}@${point.strategyConfigVersion}` === versionFilter).map((point) => <circle key={point.id} cx={x(point)} cy={y(Number(point.equity))} r={ordered.length === 1 ? 4 : 2.6}><title>{`${displayTimestamp(point.timestamp)} · ${number(point.equity, 2)} ${quoteCcy} · ${point.strategyId ? `${point.strategyId} v${point.strategyConfigVersion}` : "初始资金"}`}</title></circle>)}</svg></div><div className="equity-chart-times"><span>{displayTimestamp(ordered[0].timestamp)}</span><span>{displayTimestamp(ordered.at(-1)!.timestamp)}</span></div><p>虚线为初始资金；{versionFilter ? "高亮点为选定配置的记录，背景线保留整段会话净值。" : "曲线记录整段会话净值。"}行情估值按分钟采样，成交额外留点。</p></figure>;
}

function EventsView({ events, queueUtilization }: { events: EventBusStatus; queueUtilization: number }) {
  const flow = [{ label: "发布入口", value: compact(events.accepted), icon: Zap }, { label: "有界队列", value: `${events.queueDepth}/${events.queueCapacity}`, icon: Boxes }, { label: "事件处理器", value: String(events.handlerCount), icon: BrainCircuit }, { label: "成功消费", value: compact(events.consumed), icon: Check }];
  return <div className="detail-layout"><section className="panel event-flow"><div className="panel-heading"><div><span className="panel-kicker"><SquareActivity size={13} /> BACKPRESSURE FLOW</span><h2>从生产者到消费者</h2></div><span className="status-badge healthy"><Check size={12} /> ACCEPTING</span></div><div className="flow-diagram">{flow.map((item, index) => { const Icon = item.icon; return <div className="flow-step-wrap" key={item.label}><div className="flow-step"><span><Icon size={19} /></span><small>{item.label}</small><strong>{item.value}</strong></div>{index < flow.length - 1 && <div className="flow-link"><i /><ChevronRight size={15} /></div>}</div>; })}</div></section><section className="event-detail-grid"><article className="panel queue-detail"><span className="panel-kicker">QUEUE UTILIZATION</span><div className="big-ring" style={{ "--progress": `${queueUtilization * 3.6}deg` } as React.CSSProperties}><div><strong>{queueUtilization.toFixed(1)}%</strong><span>占用率</span></div></div><p>队列优先保留新鲜行情。当前距离触发背压策略仍有充足余量。</p></article><article className="panel event-stat-list"><EventStat label="已接收事件" value={compact(events.accepted)} note="所有发布入口" /><EventStat label="成功消费" value={compact(events.consumed)} note={`${events.handlerCount} 个处理器`} /><EventStat label="丢弃事件" value={String(events.dropped)} note="DROP_OLDEST 策略" /><EventStat label="处理失败" value={String(events.failed)} note="已隔离，不阻塞总线" /></article></section></div>;
}

function EventStat({ label, value, note }: { label: string; value: string; note: string }) {
  return <div><span>{label}</span><strong>{value}</strong><small>{note}</small></div>;
}

function BacktestsView({ runs, onCreate, clock }: { runs: BacktestRun[]; onCreate: () => void; clock: Date | null }) {
  return <div className="detail-layout">
    <section className="panel backtest-summary"><div><span className="panel-kicker"><TestTube2 size={13} /> EXPERIMENT HISTORY</span><h2>最近回测</h2><p>每次运行保留入队时的配置版本、实际参数与绩效结果，便于复现和比较。日均完成交易按卖出 / 平仓次数除以请求自然日计算，包含结束强平。</p></div><button className="primary-button" onClick={onCreate}><Plus size={15} />新建回测</button></section>
    <section className="panel backtest-table-wrap"><table className="backtest-table"><thead><tr><th>运行 ID</th><th>策略 / 配置 / 标的</th><th>状态</th><th>收益率</th><th>最大回撤</th><th>胜率</th><th>交易数</th><th>日均完成交易</th><th>参数</th></tr></thead><tbody>{runs.map((run) => <tr key={run.runId}>
      <td><strong>{run.runId}</strong><small>{timeAgo(run.createdAt, clock)}</small></td>
      <td><strong>{run.request.strategyId}</strong><small>{run.strategyConfigVersion ? `配置 v${run.strategyConfigVersion}${Object.keys(run.request.parameterOverrides ?? {}).length ? " · 含回测参数覆盖" : ""}` : "历史配置版本未记录"}</small><small>{run.request.instId} · {run.request.bar}</small></td>
      <td><span className={`status-badge ${run.status === "SUCCEEDED" ? "healthy" : run.status === "FAILED" ? "danger" : "idle"}`}>{run.status}</span>{run.error && <small className="danger-text">{run.error}</small>}</td>
      <td className={(run.totalReturn ?? 0) >= 0 ? "positive-text" : "danger-text"}>{pct(run.totalReturn)}</td><td>{pct(run.maxDrawdown)}</td><td>{pct(run.winRate, 1)}</td><td>{run.tradeCount ?? "—"}</td><td>{number(run.closedTradesPerDay, 2)}</td>
      <td>{run.strategyConfigSnapshot ? <details className="backtest-parameters"><summary>实际参数</summary><dl>{Object.entries(run.strategyConfigSnapshot).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value ?? "默认")}</dd></div>)}</dl></details> : <span>—</span>}</td>
    </tr>)}</tbody></table></section>
  </div>;
}

function timeAgoFuture(value: ApiTimestamp, now: Date | null) {
  const timestamp = apiTimestampMillis(value);
  if (timestamp == null) return "待调度";
  if (now == null) return "—";
  const seconds = Math.max(0, Math.round((timestamp - now.getTime()) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}分 ${String(seconds % 60).padStart(2, "0")}秒`;
}
