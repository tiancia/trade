"use client";

import Link from "next/link";
import { Activity, ArrowLeft, Bot, BookOpen, CirclePause, MessageSquare, RefreshCw, SlidersHorizontal } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { createJsonRequestHeaders, formatTradingTimestamp } from "../lib/trading-api.mjs";

type Loop = { id: string; lastRunCompletedAt?: string | number; nextRunAt?: string | number; lastRunSuccessful?: boolean | null; lastError?: string | null };
type ModuleStatus = {
  id: string; name: string; description: string; enabled: boolean; savedEnabled: boolean | null;
  task: { running: boolean; autoStart: boolean; loops: Loop[] };
  notices: string[]; error?: string | null;
};
const API_BASE = (process.env.NEXT_PUBLIC_TRADE_API_URL ?? "").replace(/\/$/, "");
const groups = [
  { name: "交易与分析", ids: ["trading", "trading-ai", "polymarket"] },
  { name: "内容与发布", ids: ["story", "weibo", "x"] },
];
const icons = { trading: Activity, "trading-ai": Bot, polymarket: Activity, story: BookOpen, weibo: MessageSquare, x: MessageSquare };
const loopNames: Record<string, string> = { decision: "策略决策", "event-scan": "事件扫描", reconciliation: "订单对账", review: "评估 / 审核", generation: "内容生成", publishing: "发布扫描" };

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init, cache: "no-store", headers: createJsonRequestHeaders(init),
    signal: init.signal ? AbortSignal.any([init.signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    if (response.status === 401 || response.status === 403) throw new Error("请检查操作员登录状态和管理权限后重试。");
    throw new Error(problem?.error ?? problem?.detail ?? problem?.message ?? `请求失败（${response.status}），请稍后重试。`);
  }
  return response.json();
}

export default function ModuleControlsPage() {
  const [modules, setModules] = useState<ModuleStatus[] | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [connected, setConnected] = useState(false);
  const [refreshing, setRefreshing] = useState(true);
  const [pending, setPending] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const revision = useRef(0);
  const busy = useRef(false);
  const readAbort = useRef<AbortController | null>(null);
  const writeAbort = useRef<AbortController | null>(null);

  const refresh = useCallback(async () => {
    if (busy.current) return;
    const current = ++revision.current;
    readAbort.current?.abort();
    const controller = new AbortController();
    readAbort.current = controller;
    setRefreshing(true);
    try {
      const data = await request<ModuleStatus[]>("/api/automation/modules", { signal: controller.signal });
      if (current !== revision.current || controller.signal.aborted) return;
      setModules(data); setConnected(true); setError(""); setUpdatedAt(Date.now());
    } catch (failure) {
      if (current !== revision.current || controller.signal.aborted) return;
      setConnected(false);
      setError(failure instanceof Error ? failure.message : "无法读取模块状态，请重试。");
    } finally {
      if (current === revision.current) setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(() => void refresh(), 0);
    const timer = window.setInterval(() => { if (!document.hidden) void refresh(); }, 10000);
    return () => {
      window.clearTimeout(initial); window.clearInterval(timer); revision.current += 1;
      readAbort.current?.abort(); writeAbort.current?.abort();
    };
  }, [refresh]);

  async function toggle(module: ModuleStatus, enabled = !(module.savedEnabled ?? (module.enabled && module.task.running))) {
    if (busy.current || !connected) return;
    busy.current = true; ++revision.current; readAbort.current?.abort();
    const controller = new AbortController();
    writeAbort.current = controller;
    setPending(module.id); setRefreshing(false); setMessage(""); setError("");
    try {
      const result = await request<ModuleStatus>(`/api/automation/modules/${encodeURIComponent(module.id)}`, {
        method: "PUT", body: JSON.stringify({ enabled }), signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      setModules(current => current?.map(item => item.id === result.id ? result : item) ?? null);
      setMessage(result.error ?? `${module.name}已${enabled ? "开启" : "关闭"}，重启后保留此选择。`);
    } catch (failure) {
      if (controller.signal.aborted) return;
      setMessage(`操作未确认：${failure instanceof Error ? failure.message : "请刷新后检查实际状态。"}`);
      setConnected(false);
    } finally {
      busy.current = false;
      if (!controller.signal.aborted) { setPending(null); void refresh(); }
    }
  }

  const running = modules?.filter(item => item.enabled && item.task.running).length ?? 0;
  return (
    <main className="module-page">
      <header className="module-topbar">
        <Link href="/" className="module-back"><ArrowLeft size={17} />交易驾驶舱</Link>
        <span className="module-brand">ORBIT <span>系统管理</span></span>
        <span className={`module-connection ${connected ? "online" : ""}`}><i />{connected ? "后端已连接" : "等待后端连接"}</span>
      </header>
      <div className="module-body">
        <div className="module-heading">
          <div><div className="module-title-icon"><SlidersHorizontal size={23} /></div><h1>功能管理</h1><p>在一个地方管理后台模块，随时开启或暂停。</p></div>
          <button className="secondary-button" disabled={refreshing || pending !== null} onClick={() => void refresh()}><RefreshCw size={16} className={refreshing ? "spin" : ""} />{refreshing ? "正在同步" : "刷新状态"}</button>
        </div>
        <div className="module-summary">
          <span><Activity size={17} /><strong>{modules ? running : "—"}</strong> 个模块运行中</span>
          <span><CirclePause size={17} /><strong>{modules ? modules.length - running : "—"}</strong> 个模块未运行</span>
          <span className="module-updated">{updatedAt ? `更新于 ${formatTradingTimestamp(updatedAt, "Asia/Shanghai")}` : "正在读取真实运行状态"}</span>
        </div>
        <p className="module-scope">开关会同时更新模块和后台任务，并保存到数据库。当前后端实例立即生效，重启后恢复。关闭不撤回已发出的请求。</p>
        {error && <div className="module-alert" role="alert">{error} {modules ? "下方保留上次状态，开关已暂时禁用。" : "连接成功后才能管理模块。"}<button onClick={() => void refresh()} disabled={refreshing}>重新连接</button></div>}
        {message && <div className="module-feedback" role="status">{message}</div>}
        {!modules && !error && <div className="module-empty" role="status">正在加载模块和运行状态…</div>}
        {modules?.length === 0 && <div className="module-empty">后端尚未提供可管理的模块。</div>}
        {groups.map(group => {
          const items = modules?.filter(item => group.ids.includes(item.id)) ?? [];
          if (!items.length) return null;
          return <section className="module-group" key={group.name} aria-label={group.name}>
            <h2>{group.name}<span>{items.length} 个模块</span></h2>
            <div className="module-list">{items.map(module => {
              const Icon = icons[module.id as keyof typeof icons] ?? SlidersHorizontal;
              const active = module.enabled && module.task.running;
              const selected = module.savedEnabled ?? active;
              const failed = module.task.loops.some(loop => loop.lastRunSuccessful === false);
              return <article className="module-row" key={module.id}>
                <div className="module-row-main">
                  <div className={`module-icon ${active ? "active" : ""}`}><Icon size={21} /></div>
                  <div className="module-description"><h3>{module.name}</h3><p>{module.description}</p></div>
                  <span className={`module-state ${module.error || failed ? "warning" : active ? "active" : ""}`}>{module.error ? "操作异常" : active ? failed ? "运行异常" : "运行中" : module.enabled ? "已暂停" : "已关闭"}</span>
                  <button className={`module-switch ${selected ? "on" : ""}`} role="switch" aria-checked={selected} aria-label={`${module.name}开关`} disabled={!connected || pending !== null} onClick={() => void toggle(module)}><span /></button>
                  <span className="module-action-label">{pending === module.id ? "保存中" : selected ? "开启" : "关闭"}</span>
                </div>
                <div className="module-row-details">
                  {module.error && <p className="module-row-error" role="alert">{module.error}</p>}
                  {(module.error || (selected && !active)) && <button className="secondary-button" disabled={!connected || pending !== null} onClick={() => void toggle(module, selected)}>{module.error ? "重试已保存设置" : "恢复运行"}</button>}
                  <div className="module-persistence">{module.savedEnabled === null ? `跟随启动配置；重启${module.task.autoStart ? "自动启动" : "保持暂停"}` : `已保存：重启后${module.savedEnabled ? "开启" : "关闭"}`}</div>
                  <details><summary>运行详情与生效范围</summary>
                    <ul className="module-notices">{module.notices.map(notice => <li key={notice}>{notice}</li>)}</ul>
                    <div className="module-loops">{module.task.loops.map(loop => <div key={loop.id}><strong>{loopNames[loop.id] ?? loop.id}</strong><span>{loop.lastRunSuccessful === false ? "上次失败" : loop.lastRunSuccessful === true ? "上次完成" : "尚未执行"}</span><span>{loop.lastRunCompletedAt ? formatTradingTimestamp(loop.lastRunCompletedAt) : "—"}</span>{loop.lastError && <p>{loop.lastError}</p>}</div>)}</div>
                  </details>
                  {module.id === "trading" && <p className="module-trading-note">关闭同时停止自动对账和策略止损检查，不会平仓或撤销订单。</p>}
                </div>
              </article>;
            })}</div>
          </section>;
        })}
        <footer className="module-footer">实盘交易、真实发布、AI 自动应用和外部凭据仍由各自的授权配置决定。模块开启后，具体执行仍需满足这些条件。</footer>
      </div>
    </main>
  );
}
