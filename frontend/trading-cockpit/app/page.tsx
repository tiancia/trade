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
  Settings2,
  ShieldCheck,
  Sparkles,
  SquareActivity,
  TestTube2,
  TrendingUp,
  WalletCards,
  X,
  Zap,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

type View = "overview" | "strategies" | "events" | "backtests";

type Strategy = {
  id: string;
  type: string;
  enabled: boolean;
  bar: string;
  params?: Record<string, unknown>;
};

type RuntimeStatus = {
  executionMode: "PAPER" | "LIVE" | string;
  liveEnabled: boolean;
  runningStrategyIds: string[];
  lastDecision?: {
    decisionId?: string;
    strategyId?: string;
    timestamp?: string;
    action?: string;
    reason?: string;
    confidence?: number;
    winProbability?: number;
    expectedNetEdgePercent?: number;
    lastPrice?: number;
    executionStatus?: string;
  } | null;
  lastError?: string | null;
  lastRunStartedAt?: string | null;
  lastRunCompletedAt?: string | null;
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
  nextRunAt?: string | null;
  lastRunStartedAt?: string | null;
  lastRunCompletedAt?: string | null;
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
  status: string;
  request: {
    strategyId: string;
    instId: string;
    bar: string;
    initialCash?: number;
  };
  createdAt: string;
  totalReturn?: number | null;
  maxDrawdown?: number | null;
  winRate?: number | null;
  tradeCount?: number;
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

const API_BASE = (process.env.NEXT_PUBLIC_TRADE_API_URL ?? "").replace(/\/$/, "");

const demoSnapshot: Snapshot = {
  runtime: {
    executionMode: "PAPER",
    liveEnabled: false,
    runningStrategyIds: ["btc-threshold-v2", "momentum-15m"],
    lastDecision: {
      decisionId: "dec-7f4c91",
      strategyId: "btc-threshold-v2",
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
    { id: "btc-threshold-v2", type: "threshold", enabled: true, bar: "1m" },
    { id: "momentum-15m", type: "momentum", enabled: true, bar: "15m" },
    { id: "mean-reversion-lab", type: "mean-reversion", enabled: false, bar: "5m" },
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
      status: "COMPLETED",
      request: { strategyId: "btc-threshold-v2", instId: "BTC-USDT", bar: "1m" },
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
      status: "COMPLETED",
      request: { strategyId: "momentum-15m", instId: "BTC-USDT", bar: "15m" },
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

const candles = [
  [52, 78, 57, 72], [45, 70, 63, 50], [49, 81, 54, 75], [36, 69, 61, 42],
  [31, 60, 39, 53], [23, 56, 48, 30], [18, 52, 24, 45], [27, 63, 55, 34],
  [34, 71, 41, 64], [29, 62, 57, 36], [22, 53, 29, 47], [15, 45, 38, 21],
  [19, 57, 25, 51], [26, 64, 55, 32], [33, 73, 40, 67], [28, 61, 53, 35],
  [17, 54, 24, 48], [10, 43, 36, 17], [14, 51, 20, 45], [21, 58, 50, 28],
  [27, 68, 34, 61], [19, 55, 48, 26], [12, 47, 18, 40], [16, 60, 23, 53],
  [23, 64, 55, 30], [17, 49, 42, 23], [10, 42, 16, 36], [7, 36, 29, 13],
] as const;

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
  if (value == null) return "—";
  return `${(Math.abs(value) <= 1 ? value * 100 : value).toFixed(digits)}%`;
}

function timeAgo(value?: string | null) {
  if (!value) return "暂无记录";
  const seconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  return `${Math.floor(seconds / 3600)} 小时前`;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return response.json() as Promise<T>;
}

export default function Home() {
  const [activeView, setActiveView] = useState<View>("overview");
  const [snapshot, setSnapshot] = useState<Snapshot>(demoSnapshot);
  const [dataMode, setDataMode] = useState<"live" | "demo">("demo");
  const [refreshing, setRefreshing] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [backtestOpen, setBacktestOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [clock, setClock] = useState(new Date());
  const [taskPending, setTaskPending] = useState(false);
  const [backtestPending, setBacktestPending] = useState(false);
  const [query, setQuery] = useState("");

  const refresh = useCallback(async (quiet = false) => {
    if (!quiet) setRefreshing(true);
    const endpoints = await Promise.allSettled([
      request<RuntimeStatus>("/api/trading/runtime/status"),
      request<EventBusStatus>("/api/trading/runtime/events"),
      request<Strategy[]>("/api/trading/strategies"),
      request<AutomationTask[]>("/api/automation/tasks"),
      request<BacktestRun[]>("/api/trading/backtests?offset=0&limit=12"),
    ]);

    const successful = endpoints.filter((item) => item.status === "fulfilled").length;
    if (successful > 0) {
      setSnapshot((current) => ({
        runtime: endpoints[0].status === "fulfilled" ? endpoints[0].value : current.runtime,
        events: endpoints[1].status === "fulfilled" ? endpoints[1].value : current.events,
        strategies: endpoints[2].status === "fulfilled" ? endpoints[2].value : current.strategies,
        tasks: endpoints[3].status === "fulfilled" ? endpoints[3].value : current.tasks,
        backtests: endpoints[4].status === "fulfilled" ? endpoints[4].value : current.backtests,
      }));
      setDataMode("live");
      if (!quiet) setToast(`已同步 ${successful}/5 个运行时数据源`);
    } else {
      setDataMode("demo");
      if (!quiet) setToast("后端暂未连接，继续展示安全的演示快照");
    }
    setRefreshing(false);
  }, []);

  useEffect(() => {
    refresh(true);
    const clockTimer = window.setInterval(() => setClock(new Date()), 1000);
    const refreshTimer = window.setInterval(() => refresh(true), 30_000);
    return () => {
      window.clearInterval(clockTimer);
      window.clearInterval(refreshTimer);
    };
  }, [refresh]);

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

  const task = snapshot.tasks.find((item) => item.id === "trading") ?? snapshot.tasks[0];
  const decisionLoop = task?.loops.find((loop) => loop.id === "decision");
  const eventLoop = task?.loops.find((loop) => loop.id === "event-scan");
  const queueUtilization = Math.min(100, (snapshot.events.queueDepth / Math.max(1, snapshot.events.queueCapacity)) * 100);
  const decision = snapshot.runtime.lastDecision;

  const filteredCommands = useMemo(() => {
    const commands = [
      { label: "打开运行总览", hint: "Overview", action: () => setActiveView("overview") },
      { label: "查看策略矩阵", hint: "Strategies", action: () => setActiveView("strategies") },
      { label: "检查事件背压", hint: "Events", action: () => setActiveView("events") },
      { label: "新建策略回测", hint: "Backtest", action: () => setBacktestOpen(true) },
      { label: "同步后端状态", hint: "Refresh", action: () => refresh() },
    ];
    return commands.filter((item) => item.label.toLowerCase().includes(query.toLowerCase()));
  }, [query, refresh]);

  const navigate = (view: View) => {
    setActiveView(view);
    setMobileNavOpen(false);
  };

  const toggleTask = async () => {
    if (!task) return;
    setTaskPending(true);
    if (dataMode === "live") {
      try {
        const next = await request<AutomationTask>(`/api/automation/tasks/${task.id}/${task.running ? "stop" : "start"}`, { method: "POST" });
        setSnapshot((current) => ({ ...current, tasks: current.tasks.map((item) => item.id === next.id ? next : item) }));
        setToast(next.running ? "交易自动化已启动" : "交易自动化已安全停止");
      } catch {
        setToast("任务状态更新失败，请检查后端连接");
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

  const submitBacktest = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBacktestPending(true);
    const data = new FormData(event.currentTarget);
    const payload = {
      strategyId: String(data.get("strategyId")),
      instId: String(data.get("instId")),
      bar: String(data.get("bar")),
      initialCash: Number(data.get("initialCash")),
      feeRate: 0.001,
      slippageRate: 0.0002,
      forceCloseAtEnd: true,
      includeUnconfirmed: false,
      maxCandles: 10_000,
    };

    if (dataMode === "live") {
      try {
        const run = await request<BacktestRun>("/api/trading/backtests", { method: "POST", body: JSON.stringify(payload) });
        setSnapshot((current) => ({ ...current, backtests: [run, ...current.backtests] }));
        setToast(`回测 ${run.runId} 已进入队列`);
      } catch {
        setToast("回测提交失败，请检查参数或后端状态");
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
          <div className={`connection-pill ${dataMode}`}>
            <span className="connection-dot" />
            {dataMode === "live" ? "后端已连接" : "演示数据"}
          </div>
          <span className="clock">{clock.toLocaleTimeString("zh-CN", { hour12: false })}</span>
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
          <button onClick={() => setToast("系统设置面板将在下一版本开放")}><Settings2 size={19} /><span>设置</span></button>
          <div className="environment-mark">PAPER</div>
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
            <button className="primary-button" onClick={() => setBacktestOpen(true)}><Plus size={16} />新建回测</button>
          </div>
        </div>

        {activeView === "overview" && (
          <>
            <section className="metric-grid" aria-label="核心指标">
              <MetricCard icon={WalletCards} label="模拟资产净值" value="$52,841.30" delta="+3.82%" positive detail="本月 +$1,942.18" />
              <MetricCard icon={TrendingUp} label="已实现盈亏" value="+$1,284.62" delta="+12.4%" positive detail="过去 30 天" />
              <MetricCard icon={Gauge} label="风险利用率" value="38.2%" delta="安全" positive detail="上限 72%" progress={38.2} />
              <MetricCard icon={Clock3} label="下次策略决策" value={decisionLoop?.nextRunAt ? timeAgoFuture(decisionLoop.nextRunAt) : "待调度"} delta={task?.running ? "运行中" : "已暂停"} positive={Boolean(task?.running)} detail="decision loop" />
            </section>

            <div className="overview-grid">
              <section className="panel market-panel">
                <div className="panel-heading market-heading">
                  <div className="instrument-title">
                    <div className="coin-mark">₿</div>
                    <div><h2>BTC / USDT</h2><p>OKX · 永续合约 · 1m</p></div>
                  </div>
                  <div className="market-price"><strong>$67,842.1</strong><span><ArrowUpRight size={13} /> 2.41%</span></div>
                </div>
                <div className="chart-toolbar">
                  <div><button className="active">1m</button><button>5m</button><button>15m</button><button>1H</button><button>4H</button></div>
                  <div className="ohlc"><span>O 67,714.2</span><span>H 68,014.7</span><span>L 67,592.0</span><span className="up">C 67,842.1</span></div>
                </div>
                <div className="chart" aria-label="BTC USDT 模拟 K 线图">
                  <div className="chart-grid-lines"><span /><span /><span /><span /><span /></div>
                  <div className="price-scale"><span>68,400</span><span>68,100</span><span>67,800</span><span>67,500</span><span>67,200</span></div>
                  <div className="candles">
                    {candles.map(([high, low, open, close], index) => {
                      const rising = close < open;
                      const bodyTop = Math.min(open, close);
                      const bodyHeight = Math.max(4, Math.abs(close - open));
                      return <span key={index} className={`candle ${rising ? "rise" : "fall"}`} style={{ "--wick-top": `${high}%`, "--wick-height": `${low - high}%`, "--body-top": `${bodyTop}%`, "--body-height": `${bodyHeight}%` } as React.CSSProperties} />;
                    })}
                  </div>
                  <div className="current-price-line"><span>67,842.1</span></div>
                  <div className="chart-time"><span>18:00</span><span>19:00</span><span>20:00</span><span>21:00</span><span>22:00</span></div>
                </div>
                <div className="market-footer">
                  <span><i className="healthy" /> 行情数据新鲜</span>
                  <span>24h Vol <b>18.4K BTC</b></span>
                  <span>Funding <b className="positive-text">0.0100%</b></span>
                </div>
              </section>

              <section className="panel decision-panel">
                <div className="panel-heading">
                  <div><span className="panel-kicker"><Sparkles size={13} /> LATEST DECISION</span><h2>策略决策</h2></div>
                  <span className="timestamp">{timeAgo(decision?.timestamp)}</span>
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

        {activeView === "strategies" && <StrategiesView snapshot={snapshot} />}
        {activeView === "events" && <EventsView events={snapshot.events} queueUtilization={queueUtilization} />}
        {activeView === "backtests" && <BacktestsView runs={snapshot.backtests} onCreate={() => setBacktestOpen(true)} />}
      </section>

      <aside className="right-rail">
        <section className="rail-card system-card">
          <div className="rail-card-heading"><span>系统状态</span><Activity size={16} /></div>
          <div className="system-state"><span className="pulse-ring"><i /></span><div><strong>{snapshot.runtime.lastError ? "需要关注" : "系统运行稳定"}</strong><small>{dataMode === "live" ? "来自实时后端" : "安全演示快照"}</small></div></div>
          <div className="health-list">
            <div><span>事件总线</span><b><i className={snapshot.events.running ? "healthy" : "warning"} />{snapshot.events.running ? "正常" : "停止"}</b></div>
            <div><span>行情新鲜度</span><b><i className={!snapshot.runtime.marketDataStale ? "healthy" : "warning"} />{snapshot.runtime.marketDataStale ? "过期" : "正常"}</b></div>
            <div><span>执行模式</span><b><i className="neutral" />{snapshot.runtime.executionMode}</b></div>
          </div>
        </section>

        <section className="rail-card automation-card">
          <div className="rail-card-heading"><span>交易自动化</span><Bot size={16} /></div>
          <div className="automation-switch-row">
            <div><strong>{task?.running ? "循环运行中" : "当前已暂停"}</strong><small>启动不会绕过后端安全门</small></div>
            <button className={`toggle ${task?.running ? "on" : ""}`} onClick={toggleTask} disabled={taskPending} aria-label={task?.running ? "停止交易自动化" : "启动交易自动化"} aria-pressed={task?.running}>
              <span>{taskPending ? <LoaderCircle size={12} className="spin" /> : task?.running ? <Check size={12} /> : null}</span>
            </button>
          </div>
          <div className="loop-list">
            <LoopRow icon={BrainCircuit} name="策略决策" detail="每 30 分钟" when={decisionLoop?.nextRunAt ? timeAgoFuture(decisionLoop.nextRunAt) : "待调度"} active={Boolean(task?.running)} />
            <LoopRow icon={Zap} name="事件扫描" detail="每 60 秒" when={eventLoop?.nextRunAt ? timeAgoFuture(eventLoop.nextRunAt) : "待调度"} active={Boolean(task?.running)} />
          </div>
        </section>

        <section className="rail-card strategy-card">
          <div className="rail-card-heading"><span>活跃策略</span><button onClick={() => navigate("strategies")}>全部 {snapshot.strategies.length}</button></div>
          <div className="mini-strategy-list">
            {snapshot.strategies.slice(0, 3).map((strategy, index) => (
              <button key={strategy.id} onClick={() => navigate("strategies")}>
                <span className={`strategy-glyph glyph-${index + 1}`}><Layers3 size={15} /></span>
                <span><strong>{strategy.id}</strong><small>{strategy.type} · {strategy.bar}</small></span>
                <i className={strategy.enabled ? "healthy" : "neutral"} />
              </button>
            ))}
          </div>
        </section>

        <section className="rail-card activity-card">
          <div className="rail-card-heading"><span>最近活动</span><Clock3 size={16} /></div>
          <div className="activity-list">
            <ActivityItem color="green" title="策略决策完成" detail={`${decision?.action ?? "HOLD"} · ${timeAgo(decision?.timestamp)}`} />
            <ActivityItem color="blue" title="行情事件已消费" detail={`累计 ${compact(snapshot.events.consumed)} 条`} />
            <ActivityItem color="violet" title="最近回测完成" detail={snapshot.backtests[0] ? `${snapshot.backtests[0].request.strategyId} · ${pct(snapshot.backtests[0].totalReturn)}` : "暂无记录"} />
          </div>
        </section>
      </aside>

      {commandOpen && (
        <div className="modal-backdrop" onMouseDown={() => setCommandOpen(false)}>
          <section className="command-menu" role="dialog" aria-modal="true" aria-label="命令菜单" onMouseDown={(event) => event.stopPropagation()}>
            <div className="command-search"><Search size={18} /><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入命令或页面名称…" /><kbd>ESC</kbd></div>
            <div className="command-group"><span>快速跳转</span>{filteredCommands.map((item) => <button key={item.label} onClick={() => { item.action(); setCommandOpen(false); setQuery(""); }}><span><Command size={14} />{item.label}</span><small>{item.hint}</small></button>)}</div>
          </section>
        </div>
      )}

      {backtestOpen && (
        <div className="modal-backdrop" onMouseDown={() => setBacktestOpen(false)}>
          <section className="form-modal" role="dialog" aria-modal="true" aria-labelledby="backtest-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="form-modal-heading"><div><span className="panel-kicker">REPRODUCIBLE LAB</span><h2 id="backtest-title">创建策略回测</h2><p>使用后端的历史 K 线与执行模型验证当前策略。</p></div><button className="icon-button" onClick={() => setBacktestOpen(false)} aria-label="关闭"><X size={18} /></button></div>
            <form onSubmit={submitBacktest}>
              <label><span>策略</span><select name="strategyId" defaultValue={snapshot.strategies[0]?.id}>{snapshot.strategies.map((strategy) => <option key={strategy.id} value={strategy.id}>{strategy.id}</option>)}</select></label>
              <div className="form-row"><label><span>交易标的</span><select name="instId" defaultValue="BTC-USDT"><option>BTC-USDT</option><option>ETH-USDT</option></select></label><label><span>K 线周期</span><select name="bar" defaultValue="1m"><option>1m</option><option>5m</option><option>15m</option><option>1H</option></select></label></div>
              <label><span>初始资金（USDT）</span><input name="initialCash" type="number" min="100" step="100" defaultValue="10000" /></label>
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

function StrategiesView({ snapshot }: { snapshot: Snapshot }) {
  return <div className="detail-layout"><section className="panel detail-hero"><div><span className="panel-kicker"><BrainCircuit size={13} /> STRATEGY REGISTRY</span><h2>{snapshot.runtime.runningStrategyIds.length} 个策略正在参与决策</h2><p>注册与运行分离；只有启用且进入运行时的策略才会影响交易决策。</p></div><div className="hero-stat"><span>当前执行模式</span><strong>{snapshot.runtime.executionMode}</strong><small>{snapshot.runtime.liveEnabled ? "实盘门已开启" : "实盘门保持关闭"}</small></div></section><section className="strategy-grid">{snapshot.strategies.map((strategy, index) => { const running = snapshot.runtime.runningStrategyIds.includes(strategy.id); return <article className="panel strategy-detail-card" key={strategy.id}><div className="strategy-detail-top"><span className={`strategy-glyph glyph-${(index % 3) + 1}`}><Layers3 size={18} /></span><span className={`status-badge ${running ? "healthy" : "idle"}`}>{running ? "RUNNING" : strategy.enabled ? "READY" : "DISABLED"}</span></div><h3>{strategy.id}</h3><p>{strategy.type} strategy</p><div className="strategy-metadata"><div><span>时间周期</span><strong>{strategy.bar}</strong></div><div><span>配置参数</span><strong>{Object.keys(strategy.params ?? {}).length}</strong></div><div><span>决策参与</span><strong>{running ? "是" : "否"}</strong></div></div><button>查看配置 <ChevronRight size={14} /></button></article>; })}</section></div>;
}

function EventsView({ events, queueUtilization }: { events: EventBusStatus; queueUtilization: number }) {
  const flow = [{ label: "发布入口", value: compact(events.accepted), icon: Zap }, { label: "有界队列", value: `${events.queueDepth}/${events.queueCapacity}`, icon: Boxes }, { label: "事件处理器", value: String(events.handlerCount), icon: BrainCircuit }, { label: "成功消费", value: compact(events.consumed), icon: Check }];
  return <div className="detail-layout"><section className="panel event-flow"><div className="panel-heading"><div><span className="panel-kicker"><SquareActivity size={13} /> BACKPRESSURE FLOW</span><h2>从生产者到消费者</h2></div><span className="status-badge healthy"><Check size={12} /> ACCEPTING</span></div><div className="flow-diagram">{flow.map((item, index) => { const Icon = item.icon; return <div className="flow-step-wrap" key={item.label}><div className="flow-step"><span><Icon size={19} /></span><small>{item.label}</small><strong>{item.value}</strong></div>{index < flow.length - 1 && <div className="flow-link"><i /><ChevronRight size={15} /></div>}</div>; })}</div></section><section className="event-detail-grid"><article className="panel queue-detail"><span className="panel-kicker">QUEUE UTILIZATION</span><div className="big-ring" style={{ "--progress": `${queueUtilization * 3.6}deg` } as React.CSSProperties}><div><strong>{queueUtilization.toFixed(1)}%</strong><span>占用率</span></div></div><p>队列优先保留新鲜行情。当前距离触发背压策略仍有充足余量。</p></article><article className="panel event-stat-list"><EventStat label="已接收事件" value={compact(events.accepted)} note="所有发布入口" /><EventStat label="成功消费" value={compact(events.consumed)} note={`${events.handlerCount} 个处理器`} /><EventStat label="丢弃事件" value={String(events.dropped)} note="DROP_OLDEST 策略" /><EventStat label="处理失败" value={String(events.failed)} note="已隔离，不阻塞总线" /></article></section></div>;
}

function EventStat({ label, value, note }: { label: string; value: string; note: string }) {
  return <div><span>{label}</span><strong>{value}</strong><small>{note}</small></div>;
}

function BacktestsView({ runs, onCreate }: { runs: BacktestRun[]; onCreate: () => void }) {
  return <div className="detail-layout"><section className="panel backtest-summary"><div><span className="panel-kicker"><TestTube2 size={13} /> EXPERIMENT HISTORY</span><h2>最近回测</h2><p>每次运行都保留参数、进度与绩效结果，便于复现和比较。</p></div><button className="primary-button" onClick={onCreate}><Plus size={15} />新建回测</button></section><section className="panel backtest-table-wrap"><table className="backtest-table"><thead><tr><th>运行 ID</th><th>策略 / 标的</th><th>状态</th><th>收益率</th><th>最大回撤</th><th>胜率</th><th>交易数</th><th /></tr></thead><tbody>{runs.map((run) => <tr key={run.runId}><td><strong>{run.runId}</strong><small>{timeAgo(run.createdAt)}</small></td><td><strong>{run.request.strategyId}</strong><small>{run.request.instId} · {run.request.bar}</small></td><td><span className={`status-badge ${run.status === "COMPLETED" ? "healthy" : run.status === "FAILED" ? "danger" : "idle"}`}>{run.status}</span></td><td className={(run.totalReturn ?? 0) >= 0 ? "positive-text" : "danger-text"}>{pct(run.totalReturn)}</td><td>{pct(run.maxDrawdown)}</td><td>{pct(run.winRate, 1)}</td><td>{run.tradeCount ?? "—"}</td><td><button className="icon-button" aria-label={`查看回测 ${run.runId}`}><ChevronRight size={15} /></button></td></tr>)}</tbody></table></section></div>;
}

function timeAgoFuture(value: string) {
  const seconds = Math.max(0, Math.round((new Date(value).getTime() - Date.now()) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}分 ${String(seconds % 60).padStart(2, "0")}秒`;
}
