import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
import { ArrowLeft, ArrowRight, BookOpen, Clock3, HeartPulse, RotateCcw, ShieldAlert, SkipForward, Sparkles, Trash2, Users, WalletCards } from 'lucide-react';
import coverArt from './assets/city-100-days.svg';
import type { EffectSummary, NumberMap, StorySummary, TextGameCatalog, TextGameChoice, TextGameSession } from './types';

const STORAGE_KEY = 'text-game-session-id-v2';
const ATTRIBUTE_LABELS: Record<string, string> = { cash: '现金', health: '健康', skill: '技能', network: '人脉', reputation: '名声', risk: '风险' };
const RELATION_LABELS: Record<string, string> = { linXia: '林夏', meiJie: '梅姐', zhouBo: '周伯' };

class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/text-game${path}`, {
    ...init,
    headers: init?.body ? { 'Content-Type': 'application/json', ...init.headers } : init?.headers,
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as { error?: string };
    throw new ApiError(response.status, payload.error || `请求失败（${response.status}）`);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export default function App() {
  const [catalog, setCatalog] = useState<TextGameCatalog | null>(null);
  const [session, setSession] = useState<TextGameSession | null>(null);
  const [showCatalog, setShowCatalog] = useState(false);
  const [busy, setBusy] = useState<'boot' | 'start' | 'continue' | 'delete' | null>('boot');
  const [pendingChoiceId, setPendingChoiceId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sceneBeat, setSceneBeat] = useState(0);
  const [resultBeat, setResultBeat] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const loadSession = useCallback(async (sessionId: string) => {
    const restored = await api<TextGameSession>(`/sessions/${sessionId}`);
    setSession(restored);
    localStorage.setItem(STORAGE_KEY, restored.sessionId);
    return restored;
  }, []);

  useEffect(() => {
    let active = true;
    async function boot() {
      try {
        const nextCatalog = await api<TextGameCatalog>('/catalog');
        if (!active) return;
        setCatalog(nextCatalog);
        const storedId = localStorage.getItem(STORAGE_KEY);
        if (storedId) {
          try { await loadSession(storedId); }
          catch (cause) {
            if (cause instanceof ApiError && cause.status === 404) localStorage.removeItem(STORAGE_KEY);
            else throw cause;
          }
        } else setShowCatalog(true);
      } catch (cause) {
        if (active) setError(messageOf(cause));
      } finally {
        if (active) setBusy(null);
      }
    }
    void boot();
    return () => { active = false; };
  }, [loadSession]);

  useEffect(() => {
    setSceneBeat(0);
    setResultBeat(0);
    window.setTimeout(() => headingRef.current?.focus(), 0);
  }, [session?.phase, session?.scene?.nodeId, session?.revision]);

  const sceneRead = Boolean(session?.scene && sceneBeat >= session.scene.text.length - 1);
  const resultRead = Boolean(session?.result && resultBeat >= session.result.text.length - 1);

  async function startStory(story: StorySummary) {
    if (busy || pendingChoiceId) return;
    setBusy('start'); setError(null);
    try {
      const previousId = session?.sessionId;
      const created = await api<TextGameSession>('/sessions', { method: 'POST', body: JSON.stringify({ storyKey: story.storyKey }) });
      setSession(created); setShowCatalog(false); localStorage.setItem(STORAGE_KEY, created.sessionId);
      if (previousId && previousId !== created.sessionId) void api<void>(`/sessions/${previousId}`, { method: 'DELETE' }).catch(() => undefined);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(null); }
  }

  async function choose(choice: TextGameChoice) {
    if (!session || !choice.enabled || busy || pendingChoiceId || !sceneRead) return;
    setPendingChoiceId(choice.id); setError(null);
    try {
      const next = await api<TextGameSession>(`/sessions/${session.sessionId}/choices`, {
        method: 'POST', body: JSON.stringify({ choiceId: choice.id, expectedRevision: session.revision }),
      });
      setSession(next);
    } catch (cause) { await handleMutationError(cause); }
    finally { setPendingChoiceId(null); }
  }

  async function continueGame() {
    if (!session || session.phase !== 'result' || busy || pendingChoiceId || !resultRead) return;
    setBusy('continue'); setError(null);
    try {
      const next = await api<TextGameSession>(`/sessions/${session.sessionId}/continue`, {
        method: 'POST', body: JSON.stringify({ expectedRevision: session.revision }),
      });
      setSession(next);
    } catch (cause) { await handleMutationError(cause); }
    finally { setBusy(null); }
  }

  async function handleMutationError(cause: unknown) {
    if (cause instanceof ApiError && cause.status === 409 && session) {
      try { await loadSession(session.sessionId); setError(`${cause.message}，已载入最新进度。`); return; }
      catch { /* Show the original mutation error. */ }
    }
    setError(messageOf(cause));
  }

  async function abandon() {
    if (!session || busy || pendingChoiceId) return;
    setBusy('delete'); setError(null);
    try {
      await api<void>(`/sessions/${session.sessionId}`, { method: 'DELETE' });
      localStorage.removeItem(STORAGE_KEY); setSession(null); setShowCatalog(true);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(null); }
  }

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      if (target?.matches('button, a, input, textarea, select') || !session || showCatalog || busy || pendingChoiceId) return;
      if ((event.key === ' ' || event.key === 'Enter') && session.phase === 'scene' && session.scene && !sceneRead) {
        event.preventDefault(); setSceneBeat((value) => Math.min(value + 1, session.scene!.text.length - 1));
      } else if ((event.key === ' ' || event.key === 'Enter') && session.phase === 'result' && session.result) {
        event.preventDefault();
        if (!resultRead) setResultBeat((value) => Math.min(value + 1, session.result!.text.length - 1)); else void continueGame();
      } else if (/^[1-4]$/.test(event.key) && session.phase === 'scene' && sceneRead && session.scene) {
        const choice = session.scene.choices[Number(event.key) - 1]; if (choice) void choose(choice);
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  if (busy === 'boot') return <LoadingScreen />;
  if (showCatalog || !session) return <CatalogScreen catalog={catalog} savedSession={session} busy={busy === 'start'} error={error} onStart={startStory} onResume={() => setShowCatalog(false)} />;

  return (
    <div className="app-shell">
      <header className="top-bar">
        <button className="text-button" onClick={() => setShowCatalog(true)}><ArrowLeft size={17} /> 剧情库</button>
        <div className="brand-lockup"><span>文字剧情实验室</span><strong>{session.story.title}</strong></div>
        <button className="text-button danger" disabled={busy === 'delete'} onClick={() => void abandon()}><Trash2 size={16} /> 放弃存档</button>
      </header>
      <main className="game-layout">
        <CompactStatus session={session} />
        <aside className="status-panel" aria-label="角色状态"><Timeline session={session} /><StatGrid values={session.attributes} /><RelationList values={session.relations} /></aside>
        <section className="story-panel">
          {error && <ErrorBanner message={error} onDismiss={() => setError(null)} />}
          <p className="sr-only" aria-live="polite">{pendingChoiceId ? '正在提交选择' : busy === 'continue' ? '正在进入下一幕' : ''}</p>
          {session.phase === 'scene' && session.scene && <SceneView session={session} beat={sceneBeat} headingRef={headingRef} pendingChoiceId={pendingChoiceId} onNextBeat={() => setSceneBeat((value) => Math.min(value + 1, session.scene!.text.length - 1))} onSkip={() => setSceneBeat(session.scene!.text.length - 1)} onChoice={choose} />}
          {session.phase === 'result' && session.result && <ResultView session={session} beat={resultBeat} headingRef={headingRef} busy={busy === 'continue'} onNextBeat={() => setResultBeat((value) => Math.min(value + 1, session.result!.text.length - 1))} onSkip={() => setResultBeat(session.result!.text.length - 1)} onContinue={() => void continueGame()} />}
          {session.phase === 'completed' && session.ending && <EndingView session={session} headingRef={headingRef} onRestart={() => setShowCatalog(true)} />}
        </section>
      </main>
    </div>
  );
}

function CatalogScreen({ catalog, savedSession, busy, error, onStart, onResume }: { catalog: TextGameCatalog | null; savedSession: TextGameSession | null; busy: boolean; error: string | null; onStart: (story: StorySummary) => void; onResume: () => void; }) {
  return (
    <div className="catalog-shell">
      <header className="catalog-hero">
        <div><p className="eyebrow">预制剧情 · 即时响应 · 自动存档</p><h1>在关键选择里，走完另一种人生</h1><p>每个故事都经过完整路径校验。没有生成等待，也不会在刷新后丢失进度。</p></div>
        {savedSession && <button className="resume-card" onClick={onResume}><span>继续存档</span><strong>{savedSession.story.title}</strong><small>第 {Math.min(savedSession.progress.turn + 1, savedSession.progress.maxTurns)} / {savedSession.progress.maxTurns} 回合</small><ArrowRight size={20} /></button>}
      </header>
      {error && <ErrorBanner message={error} />}
      <section className="catalog-grid" aria-label="可玩剧情">
        {catalog?.stories.map((story) => (
          <article className="story-card" key={story.storyKey}>
            <img src={story.coverImage || coverArt} onError={(event) => { event.currentTarget.src = coverArt; }} alt="城市天际线插画" />
            <div className="story-card-body">
              <div className="tag-row">{story.tags.map((tag) => <span key={tag}>{tag}</span>)}</div>
              <h2>{story.title}</h2><p>{story.summary}</p>
              <div className="story-meta"><span><Clock3 size={16} /> {story.durationMinutes} 分钟</span><span><BookOpen size={16} /> {story.maxChoices} 回合</span><span>版本 {story.version}</span></div>
              <button className="primary-button" disabled={busy} onClick={() => onStart(story)}>{busy ? '正在创建存档…' : savedSession?.story.storyKey === story.storyKey ? '重新开始' : '开始故事'}<ArrowRight size={18} /></button>
            </div>
          </article>
        ))}
        {!catalog?.stories.length && <div className="empty-state">当前没有已发布剧情。</div>}
      </section>
    </div>
  );
}

function SceneView({ session, beat, headingRef, pendingChoiceId, onNextBeat, onSkip, onChoice }: { session: TextGameSession; beat: number; headingRef: RefObject<HTMLHeadingElement>; pendingChoiceId: string | null; onNextBeat: () => void; onSkip: () => void; onChoice: (choice: TextGameChoice) => void; }) {
  const scene = session.scene!; const finished = beat >= scene.text.length - 1;
  return (
    <article className="narrative-card">
      <div className="narrative-scroll">
        <SceneHeader session={session} headingRef={headingRef} title={scene.title} />
        <div className="prose" aria-live="polite">{scene.text.slice(0, beat + 1).map((paragraph, index) => <p className="beat" key={index}>{paragraph}</p>)}</div>
      </div>
      {finished ? (
        <section className="decision-dock" aria-label="可选行动">
          <div className="decision-heading"><span>关键抉择</span><small>按数字键快速选择</small></div>
          <div className="choice-list">{scene.choices.map((choice, index) => <button className="choice-button" key={choice.id} disabled={!choice.enabled || Boolean(pendingChoiceId)} onClick={() => onChoice(choice)} aria-describedby={!choice.enabled ? `${choice.id}-reason` : undefined}><span className="choice-index">{index + 1}</span><span><strong>{choice.label}</strong>{choice.hint && <small>{choice.hint}</small>}</span>{pendingChoiceId === choice.id ? <span className="button-spinner" /> : <ArrowRight size={19} />}{!choice.enabled && <em id={`${choice.id}-reason`}>{choice.disabledReason}</em>}</button>)}</div>
        </section>
      ) : <ReadingActions onNext={onNextBeat} onSkip={onSkip} label="继续阅读" />}
    </article>
  );
}

function ResultView({ session, beat, headingRef, busy, onNextBeat, onSkip, onContinue }: { session: TextGameSession; beat: number; headingRef: RefObject<HTMLHeadingElement>; busy: boolean; onNextBeat: () => void; onSkip: () => void; onContinue: () => void; }) {
  const result = session.result!; const finished = beat >= result.text.length - 1;
  return <article className="narrative-card result-card"><div className="narrative-scroll"><SceneHeader session={session} headingRef={headingRef} title="选择的回声" /><div className="prose" aria-live="polite">{result.text.slice(0, beat + 1).map((paragraph, index) => <p className="beat" key={index}>{paragraph}</p>)}</div><EffectPanel effects={result.effects} /></div>{finished ? <div className="bottom-action-bar"><button className="primary-button wide" disabled={busy} onClick={onContinue}>{busy ? '正在进入下一幕…' : session.progress.turn >= session.progress.maxTurns ? '查看结局' : '进入下一幕'}<ArrowRight size={19} /></button></div> : <ReadingActions onNext={onNextBeat} onSkip={onSkip} label="查看后果" />}</article>;
}

function EndingView({ session, headingRef, onRestart }: { session: TextGameSession; headingRef: RefObject<HTMLHeadingElement>; onRestart: () => void; }) {
  const ending = session.ending!;
  return <article className="narrative-card ending-card"><div className="narrative-scroll"><div className="ending-grade">结局评价 <strong>{ending.grade}</strong></div><h1 ref={headingRef} tabIndex={-1}>{ending.title}</h1><div className="prose">{ending.text.map((paragraph, index) => <p className="beat" key={index}>{paragraph}</p>)}</div><div className="echo-list"><h2><Sparkles size={19} /> 选择回响</h2>{ending.echoes.map((echo) => <p key={echo}>{echo}</p>)}</div></div><div className="bottom-action-bar"><button className="primary-button" onClick={onRestart}><RotateCcw size={18} /> 返回剧情库</button></div></article>;
}

function SceneHeader({ session, headingRef, title }: { session: TextGameSession; headingRef: RefObject<HTMLHeadingElement>; title: string; }) {
  return <header className="scene-header"><div><span>第 {session.progress.chapterNumber} 章</span><span>{session.progress.date}</span></div><h1 ref={headingRef} tabIndex={-1}>{title}</h1><p>{session.progress.chapterTitle} · 第 {Math.min(session.progress.turn + 1, session.progress.maxTurns)} / {session.progress.maxTurns} 回合</p></header>;
}

function ReadingActions({ onNext, onSkip, label }: { onNext: () => void; onSkip: () => void; label: string; }) {
  return <div className="bottom-action-bar reading-actions"><button className="secondary-button" onClick={onSkip}><SkipForward size={17} /> 跳过文本</button><button className="primary-button" onClick={onNext}>{label}<ArrowRight size={18} /></button><small>空格或 Enter 继续</small></div>;
}

function Timeline({ session }: { session: TextGameSession; }) {
  return <section className="timeline-card"><p className="panel-kicker">章节进度</p><div className="timeline">{[1, 2, 3].map((chapter) => <div className={chapter < session.progress.chapterNumber ? 'done' : chapter === session.progress.chapterNumber ? 'active' : ''} key={chapter}><span>{chapter}</span><i /></div>)}</div><strong>{session.progress.chapterTitle}</strong><small>已完成 {session.progress.turn} 次关键选择</small></section>;
}

function CompactStatus({ session }: { session: TextGameSession; }) {
  const progress = Math.min(100, Math.max(0, (session.progress.turn / session.progress.maxTurns) * 100));
  const keys = ['cash', 'health', 'risk'];
  return <section className="compact-status" aria-label="关键状态"><div className="compact-progress"><span>第 {session.progress.chapterNumber} 章 · {session.progress.turn + 1}/{session.progress.maxTurns}</span><i><b style={{ width: `${progress}%` }} /></i></div>{keys.map((key) => { const value = session.attributes[key]; return value === undefined ? null : <span className={`compact-stat ${key === 'risk' || value < 0 ? 'warning' : ''}`} key={key}>{ATTRIBUTE_LABELS[key]} <strong>{value}</strong></span>; })}</section>;
}

function StatGrid({ values }: { values: NumberMap; }) {
  const icons: Record<string, ReactNode> = { cash: <WalletCards size={17} />, health: <HeartPulse size={17} />, skill: <BookOpen size={17} />, network: <Users size={17} />, reputation: <Sparkles size={17} />, risk: <ShieldAlert size={17} /> };
  return <section className="stats-card"><p className="panel-kicker">当前状态</p><div className="stat-grid">{Object.entries(values).map(([key, value]) => <div className={key === 'risk' ? 'risk-stat' : ''} key={key}><span>{icons[key]} {ATTRIBUTE_LABELS[key] || key}</span><strong>{value}</strong></div>)}</div></section>;
}

function RelationList({ values }: { values: NumberMap; }) {
  return <section className="relations-card"><p className="panel-kicker">人物关系</p>{Object.entries(values).map(([key, value]) => <div className="relation-row" key={key}><span>{RELATION_LABELS[key] || key}</span><div><i style={{ width: `${Math.max(0, Math.min(100, value))}%` }} /></div><strong>{value}</strong></div>)}</section>;
}

function EffectPanel({ effects }: { effects: EffectSummary; }) {
  const entries = useMemo(() => [...Object.entries(effects.attributes).map(([key, value]) => ({ label: ATTRIBUTE_LABELS[key] || key, value })), ...Object.entries(effects.relations).map(([key, value]) => ({ label: `${RELATION_LABELS[key] || key}关系`, value }))].filter((item) => item.value !== 0), [effects]);
  if (!entries.length && !Object.keys(effects.flags).length) return null;
  return <section className="effect-panel" aria-label="状态变化"><h2>本次变化</h2><div>{entries.map((entry) => <span className={entry.value > 0 ? 'positive' : 'negative'} key={entry.label}>{entry.label} {signed(entry.value)}</span>)}</div>{Object.keys(effects.flags).length > 0 && <small>你的选择改变了后续剧情条件。</small>}</section>;
}

function ErrorBanner({ message, onDismiss }: { message: string; onDismiss?: () => void; }) { return <div className="error-banner" role="alert"><span>{message}</span>{onDismiss && <button onClick={onDismiss}>关闭</button>}</div>; }
function LoadingScreen() { return <div className="loading-screen"><span className="large-spinner" /><strong>正在读取存档</strong></div>; }
function signed(value: number) { return value > 0 ? `+${value}` : String(value); }
function messageOf(cause: unknown) { return cause instanceof Error ? cause.message : '发生未知错误，请稍后重试。'; }
