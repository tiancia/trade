import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardList,
  Clock3,
  Dumbbell,
  HeartPulse,
  Hourglass,
  Loader2,
  Play,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Star,
  Users,
  Wallet,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import cityImage from './assets/city-100-days.svg';
import type {
  ModeSummary,
  Stats,
  TextGameActionDefinition,
  TextGameCatalog,
  TextGameChoice,
  TextGameInterlude,
  TextGameSession,
  ThemeSummary,
} from './types';

const STORAGE_KEY = 'text-game-session-id';
// Keep display order stable even when the backend later adds extra stats.
const STAT_ORDER = ['money', 'health', 'skill', 'network', 'reputation', 'risk'];

// UI metadata for known stat keys; unknown keys still render with a fallback icon.
const STAT_META: Record<string, { label: string; Icon: LucideIcon; tone: string }> = {
  money: { label: '现金', Icon: Wallet, tone: 'money' },
  health: { label: '健康', Icon: HeartPulse, tone: 'health' },
  skill: { label: '技能', Icon: Dumbbell, tone: 'skill' },
  network: { label: '人脉', Icon: Users, tone: 'network' },
  reputation: { label: '名声', Icon: Star, tone: 'reputation' },
  risk: { label: '风险', Icon: ShieldAlert, tone: 'risk' },
};

type BusyState = 'boot' | 'start' | 'choice' | 'interlude' | 'resolution' | 'advance' | 'restart' | null;

type RetryAction =
  | { type: 'boot' }
  | { type: 'start' }
  | { type: 'choice'; choiceId: string; turn: number }
  | { type: 'resolution' };

type ActionResultNotice = {
  feedback: string;
  statsDelta: Stats;
};

// Thin API wrapper for the text-game backend. It keeps response parsing and
// backend error-message extraction out of the component event handlers.
async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(`/api/text-game${path}`, {
    ...init,
    headers,
  });

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = (await response.json()) as { error?: string };
      message = body.error || message;
    } catch {
      // Keep the HTTP status text when the backend returns no JSON body.
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export default function App() {
  const [catalog, setCatalog] = useState<TextGameCatalog | null>(null);
  const [selectedThemeId, setSelectedThemeId] = useState('');
  const [selectedModeId, setSelectedModeId] = useState('');
  const [session, setSession] = useState<TextGameSession | null>(null);
  const [busy, setBusy] = useState<BusyState>('boot');
  const [pendingChoiceId, setPendingChoiceId] = useState<string | null>(null);
  const [pendingActionId, setPendingActionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retryAction, setRetryAction] = useState<RetryAction | null>(null);
  const [advanceNotice, setAdvanceNotice] = useState<ActionResultNotice | null>(null);
  // The polling effect needs the freshest session without restarting the timer
  // on every local state change.
  const sessionRef = useRef<TextGameSession | null>(null);
  // Stores the last interlude action result so it can be merged into the notice
  // shown when the async main-scene resolution finally advances the turn.
  const lastInterludeResultRef = useRef<ActionResultNotice | null>(null);

  useEffect(() => {
    void bootstrap();
  }, []);

  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  useEffect(() => {
    if (!session || session.completed || session.resolution?.status !== 'pending') {
      return;
    }

    // The backend resolves the selected choice asynchronously, while the player
    // can keep taking interlude actions. Poll until that pending resolution is
    // ready, failed, or already committed by another request.
    let cancelled = false;
    const refresh = async () => {
      try {
        const latest = await api<TextGameSession>(`/sessions/${session.sessionId}`);
        if (cancelled) {
          return;
        }
        rememberInterludeLog(latest);
        const currentSession = sessionRef.current;
        if (currentSession && latest.turn > currentSession.turn) {
          setAdvanceNotice({
            feedback: lastInterludeResultRef.current?.feedback ?? '主线已推进，等待期行动和主线结果已经结算。',
            statsDelta: diffStats(currentSession.stats, latest.stats),
          });
          lastInterludeResultRef.current = null;
        }
        sessionRef.current = latest;
        setSession(latest);
        if (latest.phase === 'error' && latest.resolution?.error) {
          setError(latest.resolution.error);
          setRetryAction({ type: 'resolution' });
        } else if (retryAction?.type === 'resolution') {
          setError(null);
          setRetryAction(null);
        }
      } catch (caught) {
        if (!cancelled) {
          setError(errorMessage(caught));
        }
      }
    };

    const timer = window.setInterval(refresh, 1000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [retryAction?.type, session?.completed, session?.resolution?.status, session?.sessionId]);

  const selectedTheme = useMemo(
    () => catalog?.themes.find((theme) => theme.id === selectedThemeId) ?? catalog?.themes[0],
    [catalog, selectedThemeId],
  );

  const selectedMode = useMemo(
    () => catalog?.modes.find((mode) => mode.id === selectedModeId) ?? catalog?.modes[0],
    [catalog, selectedModeId],
  );

  async function bootstrap() {
    setBusy('boot');
    setError(null);
    try {
      const nextCatalog = await api<TextGameCatalog>('/catalog');
      setCatalog(nextCatalog);
      setSelectedThemeId((current) => current || nextCatalog.themes[0]?.id || '');
      setSelectedModeId((current) => current || nextCatalog.modes[0]?.id || '');

      const storedSessionId = localStorage.getItem(STORAGE_KEY);
      if (storedSessionId) {
        try {
          // Resume lightweight in-browser sessions after a refresh; missing or
          // expired backend sessions are treated as a clean start.
          const restored = await api<TextGameSession>(`/sessions/${storedSessionId}`);
          setSession(restored);
        } catch {
          localStorage.removeItem(STORAGE_KEY);
        }
      }
      setRetryAction(null);
    } catch (caught) {
      setError(errorMessage(caught));
      setRetryAction({ type: 'boot' });
    } finally {
      setBusy(null);
    }
  }

  async function startNewGame() {
    if (!selectedTheme || !selectedMode) {
      return;
    }
    setBusy('start');
    setError(null);
    try {
      const nextSession = await api<TextGameSession>('/sessions', {
        method: 'POST',
        body: JSON.stringify({
          themeId: selectedTheme.id,
          modeId: selectedMode.id,
        }),
      });
      setSession(nextSession);
      setAdvanceNotice(null);
      lastInterludeResultRef.current = null;
      localStorage.setItem(STORAGE_KEY, nextSession.sessionId);
      setRetryAction(null);
    } catch (caught) {
      setError(errorMessage(caught));
      setRetryAction({ type: 'start' });
    } finally {
      setBusy(null);
    }
  }

  async function restartGame() {
    setBusy('restart');
    setError(null);
    const existingSessionId = session?.sessionId;
    try {
      if (existingSessionId) {
        await api<void>(`/sessions/${existingSessionId}`, { method: 'DELETE' });
      }
      localStorage.removeItem(STORAGE_KEY);
      setSession(null);
      setAdvanceNotice(null);
      lastInterludeResultRef.current = null;
      await startNewGame();
    } catch (caught) {
      setError(errorMessage(caught));
      setRetryAction({ type: 'start' });
      setBusy(null);
    }
  }

  async function submitChoice(choice: TextGameChoice, turn = session?.turn ?? 0) {
    if (!session || busy) {
      return;
    }
    setBusy('choice');
    setPendingChoiceId(choice.id);
    setError(null);
    setAdvanceNotice(null);
    lastInterludeResultRef.current = null;
    try {
      const nextSession = await api<TextGameSession>(`/sessions/${session.sessionId}/choices`, {
        method: 'POST',
        body: JSON.stringify({ choiceId: choice.id, turn }),
      });
      setSession(nextSession);
      localStorage.setItem(STORAGE_KEY, nextSession.sessionId);
      setRetryAction(null);
    } catch (caught) {
      setError(errorMessage(caught));
      setRetryAction({ type: 'choice', choiceId: choice.id, turn });
    } finally {
      setPendingChoiceId(null);
      setBusy(null);
    }
  }

  async function submitInterludeAction(action: TextGameActionDefinition) {
    if (!session || !session.interlude || !session.resolution?.turn || busy) {
      return;
    }
    setBusy('interlude');
    setPendingActionId(action.id);
    setError(null);
    try {
      const nextSession = await api<TextGameSession>(`/sessions/${session.sessionId}/interlude-actions`, {
        method: 'POST',
        body: JSON.stringify({
          actionId: action.id,
          turn: session.resolution.turn,
          step: session.interlude.nextStep,
        }),
      });
      rememberInterludeLog(nextSession);
      if (nextSession.turn > session.turn) {
        setAdvanceNotice({
          feedback: `主线已推进，「${action.label}」的影响已经并入新的局面。`,
          statsDelta: diffStats(session.stats, nextSession.stats),
        });
        lastInterludeResultRef.current = null;
      }
      setSession(nextSession);
      setRetryAction(null);
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setPendingActionId(null);
      setBusy(null);
    }
  }

  async function retryResolution() {
    if (!session || busy) {
      return;
    }
    setBusy('resolution');
    setError(null);
    try {
      const nextSession = await api<TextGameSession>(`/sessions/${session.sessionId}/resolution/retry`, {
        method: 'POST',
      });
      setSession(nextSession);
      setRetryAction(null);
    } catch (caught) {
      setError(errorMessage(caught));
      setRetryAction({ type: 'resolution' });
    } finally {
      setBusy(null);
    }
  }

  async function advanceResolution() {
    if (!session || busy || !session.resolution?.canAdvance) {
      return;
    }
    setBusy('advance');
    setError(null);
    try {
      const nextSession = await api<TextGameSession>(`/sessions/${session.sessionId}/resolution/advance`, {
        method: 'POST',
      });
      setAdvanceNotice({
        feedback: nextSession.lastResult ?? '主线结果已经结算，新的局面已经展开。',
        statsDelta: diffStats(session.stats, nextSession.stats),
      });
      lastInterludeResultRef.current = null;
      setSession(nextSession);
      setRetryAction(null);
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  function retry() {
    if (!retryAction) {
      return;
    }
    if (retryAction.type === 'boot') {
      void bootstrap();
      return;
    }
    if (retryAction.type === 'start') {
      void startNewGame();
      return;
    }
    if (retryAction.type === 'resolution') {
      void retryResolution();
      return;
    }
    const choice = session?.scene.choices.find((item) => item.id === retryAction.choiceId);
    if (choice) {
      void submitChoice(choice, retryAction.turn);
    }
  }

  function rememberInterludeLog(nextSession: TextGameSession) {
    const entry = latestInterludeLog(nextSession.interlude);
    if (!entry) {
      return;
    }
    lastInterludeResultRef.current = {
      feedback: entry.feedback,
      statsDelta: entry.statsDelta,
    };
  }

  const progressPercent = session ? Math.round((session.turn / session.maxTurns) * 100) : 0;
  const isBusy = busy !== null;
  const inInterlude = Boolean(
    session?.interlude && ['interlude', 'settling', 'error'].includes(session.phase),
  );

  return (
    <div className="app-shell">
      <header className="top-bar">
        <div>
          <p className="eyebrow">选择驱动文字游戏</p>
          <h1>人生模拟器：100天翻身</h1>
        </div>
        {session && (
          <button className="icon-text-button" type="button" onClick={() => void restartGame()} disabled={isBusy}>
            <RotateCcw size={18} />
            重新开始
          </button>
        )}
      </header>

      {error && (
        <div className="error-banner" role="alert">
          <AlertTriangle size={18} />
          <span>{error}</span>
          {retryAction && (
            <button type="button" onClick={retry} disabled={isBusy}>
              <RefreshCw size={16} />
              重试
            </button>
          )}
        </div>
      )}

      {!session ? (
        <StartScreen
          catalog={catalog}
          selectedTheme={selectedTheme}
          selectedMode={selectedMode}
          selectedThemeId={selectedThemeId}
          selectedModeId={selectedModeId}
          busy={busy}
          onThemeChange={setSelectedThemeId}
          onModeChange={setSelectedModeId}
          onStart={() => void startNewGame()}
        />
      ) : (
        <main className="game-layout">
          <section className="story-surface" aria-live="polite">
            <div className="progress-header">
              <div>
                <span className="stage-pill">{session.stage.name}</span>
                <h2>{inInterlude ? interludeTitle(session) : session.scene.title}</h2>
              </div>
              <div className="day-counter">
                <Clock3 size={17} />
                第 {session.day} 天
              </div>
            </div>

            <div className="progress-track" aria-label={`回合进度 ${session.turn}/${session.maxTurns}`}>
              <div style={{ width: `${progressPercent}%` }} />
            </div>
            <div className="turn-line">
              <span>{session.turn}/{session.maxTurns} 回合</span>
              <span>{progressPercent}%</span>
            </div>

            {session.lastResult && <div className="result-strip">{session.lastResult}</div>}
            {advanceNotice && <AdvanceNotice notice={advanceNotice} />}

            {session.completed && session.ending ? (
              <EndingView session={session} onRestart={() => void restartGame()} disabled={isBusy} />
            ) : inInterlude && session.interlude ? (
              <InterludePanel
                session={session}
                interlude={session.interlude}
                busy={busy}
                pendingActionId={pendingActionId}
                onAction={(action) => void submitInterludeAction(action)}
                onRetryResolution={() => void retryResolution()}
                onAdvanceResolution={() => void advanceResolution()}
              />
            ) : (
              <DecisionPanel
                session={session}
                busy={busy}
                pendingChoiceId={pendingChoiceId}
                onChoice={(choice) => void submitChoice(choice)}
              />
            )}
          </section>

          <aside className="status-surface">
            <img className="city-image" src={cityImage} alt="" />
            <div className="mode-block">
              <p>{selectedMode?.name ?? '短局'}</p>
              <span>
                {session.maxTurns} 次决策 / {selectedMode?.totalDays ?? 100} 天
              </span>
            </div>
            <StatsPanel stats={session.stats} />
          </aside>
        </main>
      )}

      {busy === 'boot' && (
        <div className="boot-loading">
          <Loader2 className="spin" size={24} />
          加载中
        </div>
      )}
    </div>
  );
}

function DecisionPanel({
  session,
  busy,
  pendingChoiceId,
  onChoice,
}: {
  session: TextGameSession;
  busy: BusyState;
  pendingChoiceId: string | null;
  onChoice: (choice: TextGameChoice) => void;
}) {
  return (
    <>
      <article className="scene-text" key={session.turn}>
        {session.scene.text}
      </article>
      <div className="choice-list">
        {session.scene.choices.map((choice) => (
          <button
            className="choice-button"
            type="button"
            key={choice.id}
            onClick={() => onChoice(choice)}
            disabled={busy !== null}
          >
            <span className="choice-id">{choice.id}</span>
            <span className="choice-copy">
              <strong>{choice.label}</strong>
              {choice.hint && <small>{choice.hint}</small>}
            </span>
            {busy === 'choice' && pendingChoiceId === choice.id && <Loader2 className="spin" size={18} />}
          </button>
        ))}
      </div>
    </>
  );
}

function InterludePanel({
  session,
  interlude,
  busy,
  pendingActionId,
  onAction,
  onRetryResolution,
  onAdvanceResolution,
}: {
  session: TextGameSession;
  interlude: TextGameInterlude;
  busy: BusyState;
  pendingActionId: string | null;
  onAction: (action: TextGameActionDefinition) => void;
  onRetryResolution: () => void;
  onAdvanceResolution: () => void;
}) {
  const resolution = session.resolution;
  const isError = session.phase === 'error' || resolution.status === 'error';
  const isSettling = session.phase === 'settling';
  const actionDisabled = busy !== null || isError;

  return (
    <div className="interlude-panel">
      <div className="interlude-status">
        <span className={`resolution-badge ${resolution.status}`}>
          {resolution.status === 'pending' && <Loader2 className="spin" size={15} />}
          {resolution.status === 'ready' && <CheckCircle2 size={15} />}
          {resolution.status === 'error' && <AlertTriangle size={15} />}
          {resolutionLabel(resolution.status)}
        </span>
        <span>
          {interlude.completedSteps}/{interlude.totalSteps} 插曲日
        </span>
      </div>
      <p className="interlude-wait-note">{interludeStatusText(session, interlude)}</p>

      <div className="interlude-progress" aria-label={`插曲进度 ${interlude.completedSteps}/${interlude.totalSteps}`}>
        {Array.from({ length: interlude.totalSteps }, (_, index) => (
          <span className={index < interlude.completedSteps ? 'filled' : ''} key={index} />
        ))}
      </div>

      {interlude.recentFeedback && <div className="feedback-strip">{interlude.recentFeedback}</div>}
      {resolution.canAdvance && !isError && (
        <div className="advance-ready">
          <div>
            <strong>主线已就绪</strong>
            <span>可以进入下一幕，也可以继续做插曲行动。</span>
          </div>
          <button type="button" onClick={onAdvanceResolution} disabled={busy !== null}>
            {busy === 'advance' ? <Loader2 className="spin" size={17} /> : <Play size={17} />}
            进入下一幕
          </button>
        </div>
      )}

      {isError ? (
        <div className="resolution-error">
          <AlertTriangle size={19} />
          <span>{resolution.error ?? '主线生成失败'}</span>
          <button type="button" onClick={onRetryResolution} disabled={busy !== null}>
            {busy === 'resolution' ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
            重试生成
          </button>
        </div>
      ) : (
        <>
          <div className="interlude-heading">
            {isSettling ? <Hourglass size={18} /> : <ClipboardList size={18} />}
            <span>{isSettling ? '整理状态' : `第 ${interlude.currentDay} 天插曲行动`}</span>
          </div>
          <div className="interlude-actions">
            {interlude.actions.map((action) => (
              <button
                className="interlude-action-button"
                type="button"
                key={action.id}
                onClick={() => onAction(action)}
                disabled={actionDisabled}
              >
                <span className="action-copy">
                  <strong>{action.label}</strong>
                  {action.hint && <small>{action.hint}</small>}
                  <DeltaLine statsDelta={isSettling ? {} : action.statsDelta} />
                </span>
                {busy === 'interlude' && pendingActionId === action.id ? (
                  <Loader2 className="spin" size={18} />
                ) : (
                  <CheckCircle2 size={18} />
                )}
              </button>
            ))}
          </div>
        </>
      )}

      {interlude.log.length > 0 && (
        <div className="interlude-log">
          <h3>插曲日志</h3>
          {interlude.log.slice(-4).map((entry) => (
            <div className="log-row" key={`${entry.step}-${entry.actionId}`}>
              <span className="log-meta">
                第 {entry.day} 天
                <strong>{entry.actionLabel}</strong>
              </span>
              <div className="log-content">
                <p>{entry.feedback}</p>
                <DeltaLine statsDelta={entry.statsDelta} />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function AdvanceNotice({ notice }: { notice: ActionResultNotice }) {
  return (
    <div className="advance-notice">
      <CheckCircle2 size={18} />
      <div>
        <strong>主线已推进</strong>
        <p>{notice.feedback}</p>
        <DeltaLine statsDelta={notice.statsDelta} />
      </div>
    </div>
  );
}

function DeltaLine({ statsDelta }: { statsDelta: Stats }) {
  const entries = Object.entries(statsDelta).filter(([, value]) => value !== 0);
  if (entries.length === 0) {
    return <small className="delta-line empty">不改变属性</small>;
  }

  return (
    <small className="delta-line">
      {entries.map(([key, value]) => (
        <span className={value > 0 ? 'positive' : 'negative'} key={key}>
          {STAT_META[key]?.label ?? key} {formatDelta(key, value)}
        </span>
      ))}
    </small>
  );
}

function StartScreen({
  catalog,
  selectedTheme,
  selectedMode,
  selectedThemeId,
  selectedModeId,
  busy,
  onThemeChange,
  onModeChange,
  onStart,
}: {
  catalog: TextGameCatalog | null;
  selectedTheme?: ThemeSummary;
  selectedMode?: ModeSummary;
  selectedThemeId: string;
  selectedModeId: string;
  busy: BusyState;
  onThemeChange: (value: string) => void;
  onModeChange: (value: string) => void;
  onStart: () => void;
}) {
  const disabled = busy !== null || !selectedTheme || !selectedMode;

  return (
    <main className="start-layout">
      <section className="start-surface">
        <img src={cityImage} alt="" />
        <div className="start-content">
          <span className="stage-pill">100天 / 20次选择</span>
          <h2>{selectedTheme?.name ?? '人生模拟器'}</h2>
          <p>{selectedTheme?.description ?? '正在读取游戏目录'}</p>

          <div className="selectors">
            <label>
              <span>主题</span>
              <select value={selectedThemeId} onChange={(event) => onThemeChange(event.target.value)}>
                {catalog?.themes.map((theme) => (
                  <option key={theme.id} value={theme.id}>
                    {theme.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>模式</span>
              <select value={selectedModeId} onChange={(event) => onModeChange(event.target.value)}>
                {catalog?.modes.map((mode) => (
                  <option key={mode.id} value={mode.id}>
                    {mode.name}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <button className="primary-action" type="button" onClick={onStart} disabled={disabled}>
            {busy === 'start' ? <Loader2 className="spin" size={19} /> : <Play size={19} />}
            开始新局
          </button>
        </div>
      </section>
    </main>
  );
}

function EndingView({
  session,
  onRestart,
  disabled,
}: {
  session: TextGameSession;
  onRestart: () => void;
  disabled: boolean;
}) {
  const ending = session.ending;
  if (!ending) {
    return null;
  }

  return (
    <div className="ending-block">
      <div className="ending-title-row">
        <span className="grade-badge">{ending.grade}</span>
        <h3>{ending.title}</h3>
      </div>
      <p>{ending.summary}</p>
      <ul>
        {ending.echoes.map((echo) => (
          <li key={echo}>{echo}</li>
        ))}
      </ul>
      <button className="primary-action compact" type="button" onClick={onRestart} disabled={disabled}>
        <RotateCcw size={18} />
        再来一局
      </button>
    </div>
  );
}

function StatsPanel({ stats }: { stats: Stats }) {
  const previousStatsRef = useRef<Stats | null>(null);
  const [recentDeltas, setRecentDeltas] = useState<Stats>({});

  useEffect(() => {
    const previousStats = previousStatsRef.current;
    previousStatsRef.current = stats;

    if (!previousStats) {
      return;
    }

    const nextDeltas = diffStats(previousStats, stats);
    if (!hasStatsDelta(nextDeltas)) {
      return;
    }

    // Flash only the latest delta; the persisted value always comes from props.
    setRecentDeltas(nextDeltas);
    const timer = window.setTimeout(() => setRecentDeltas({}), 1800);
    return () => window.clearTimeout(timer);
  }, [stats]);

  const keys = [...STAT_ORDER, ...Object.keys(stats).filter((key) => !STAT_ORDER.includes(key))];

  return (
    <div className="stats-panel">
      <h3>属性</h3>
      {keys.map((key) => {
        const value = stats[key] ?? 0;
        const meta = STAT_META[key] ?? { label: key, Icon: Star, tone: 'default' };
        const Icon = meta.Icon;
        const width = statWidth(key, value);
        const delta = recentDeltas[key] ?? 0;
        return (
          <div className={`stat-row ${meta.tone} ${delta !== 0 ? 'changed' : ''}`} key={key}>
            <div className="stat-head">
              <span>
                <Icon size={16} />
                {meta.label}
              </span>
              <strong>
                {formatStat(key, value)}
                {delta !== 0 && (
                  <em className={delta > 0 ? 'positive' : 'negative'}>{formatDelta(key, delta)}</em>
                )}
              </strong>
            </div>
            <div className="stat-bar">
              <div style={{ width: `${width}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

function interludeTitle(session: TextGameSession) {
  if (session.phase === 'error') {
    return '主线生成遇到问题';
  }
  if (session.phase === 'settling') {
    return '整理状态';
  }
  return `第 ${session.day} 天插曲行动`;
}

function interludeStatusText(session: TextGameSession, interlude: TextGameInterlude) {
  if (session.phase === 'error' || session.resolution.status === 'error') {
    return '主线推演中断，重试成功后可以继续当前等待期。';
  }
  if (session.phase === 'settling') {
    return session.resolution.status === 'pending'
      ? '插曲日已完成，正在整理状态；这些轻量行动不会继续改变属性。'
      : '主线已就绪，整理完成后会进入新的局面。';
  }
  if (session.resolution.status === 'ready') {
    const remaining = Math.max(0, interlude.totalSteps - interlude.completedSteps);
    return remaining > 0
      ? `主线已就绪，可以立即进入下一幕；也可以再完成 ${remaining} 个插曲日调整属性。`
      : '主线已就绪，可以进入下一幕；继续等待只会做轻量整理，不再改变属性。';
  }
  return 'AI 正在推演主线，你可以用插曲行动调整真实属性。';
}

function resolutionLabel(status: string) {
  if (status === 'pending') {
    return '主线生成中';
  }
  if (status === 'ready') {
    return '主线已就绪';
  }
  if (status === 'error') {
    return '生成失败';
  }
  return '未生成';
}

function latestInterludeLog(interlude?: TextGameInterlude | null) {
  if (!interlude?.log.length) {
    return null;
  }
  return interlude.log[interlude.log.length - 1];
}

function diffStats(previous: Stats, next: Stats) {
  const keys = new Set([...Object.keys(previous), ...Object.keys(next)]);
  const delta: Stats = {};
  keys.forEach((key) => {
    const value = (next[key] ?? 0) - (previous[key] ?? 0);
    if (value !== 0) {
      delta[key] = value;
    }
  });
  return delta;
}

function hasStatsDelta(statsDelta: Stats) {
  return Object.values(statsDelta).some((value) => value !== 0);
}

function statWidth(key: string, value: number) {
  if (key === 'money') {
    return Math.max(4, Math.min(100, ((value + 5000) / 25000) * 100));
  }
  return Math.max(4, Math.min(100, value));
}

function formatStat(key: string, value: number) {
  if (key === 'money') {
    const sign = value >= 0 ? '' : '-';
    return `${sign}¥${Math.abs(value).toLocaleString('zh-CN')}`;
  }
  return value.toString();
}

function formatDelta(key: string, value: number) {
  const sign = value > 0 ? '+' : '';
  if (key === 'money') {
    return `${sign}${value < 0 ? '-' : ''}¥${Math.abs(value).toLocaleString('zh-CN')}`;
  }
  return `${sign}${value}`;
}

function errorMessage(caught: unknown) {
  return caught instanceof Error ? caught.message : '请求失败';
}
