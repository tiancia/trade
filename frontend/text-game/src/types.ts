export type Stats = Record<string, number>;

// Catalog data drives the start screen selectors; detailed definitions stay on
// the backend so the frontend only needs summaries.
export interface TextGameCatalog {
  themes: ThemeSummary[];
  modes: ModeSummary[];
}

export interface ThemeSummary {
  id: string;
  name: string;
  description: string;
}

export interface ModeSummary {
  id: string;
  name: string;
  description: string;
  maxTurns: number;
  totalDays: number;
}

export interface TextGameStage {
  id: string;
  name: string;
}

export interface TextGameChoice {
  id: string;
  label: string;
  hint?: string | null;
}

export interface TextGameScene {
  title: string;
  text: string;
  choices: TextGameChoice[];
}

export interface TextGameEnding {
  title: string;
  grade: string;
  summary: string;
  echoes: string[];
  finalStats: Stats;
}

// Phase describes the screen-level workflow; resolutionStatus describes only
// the async AI generation attached to a submitted choice.
export type TextGamePhase = 'decision' | 'interlude' | 'settling' | 'completed' | 'error';

export type TextGameResolutionStatus = 'none' | 'pending' | 'ready' | 'error';

// Interlude actions are deterministic client-visible options that can adjust
// stats while the backend prepares the next main scene.
export interface TextGameActionDefinition {
  id: string;
  label: string;
  hint?: string | null;
  statsDelta: Stats;
  feedbackTemplates?: string[];
  minStats?: Stats;
  maxStats?: Stats;
}

export interface TextGameResolution {
  status: TextGameResolutionStatus;
  turn?: number | null;
  error?: string | null;
  canAdvance: boolean;
}

// Log entries are shown in the waiting/interlude panel and also let the client
// compute the notice displayed when a pending resolution advances the turn.
export interface TextGameInterludeLogEntry {
  turn: number;
  step: number;
  day: number;
  actionId: string;
  actionLabel: string;
  feedback: string;
  statsDelta: Stats;
  statsAfter: Stats;
  settling: boolean;
}

export interface TextGameInterlude {
  turn: number;
  completedSteps: number;
  totalSteps: number;
  currentDay: number;
  nextStep: number;
  actions: TextGameActionDefinition[];
  recentFeedback?: string | null;
  log: TextGameInterludeLogEntry[];
}

// Full session snapshot returned by every text-game endpoint. The frontend
// treats it as the single source of truth instead of patching local fragments.
export interface TextGameSession {
  sessionId: string;
  themeId: string;
  modeId: string;
  phase: TextGamePhase;
  turn: number;
  maxTurns: number;
  day: number;
  stage: TextGameStage;
  stats: Stats;
  lastResult?: string | null;
  scene: TextGameScene;
  ending?: TextGameEnding | null;
  resolution: TextGameResolution;
  interlude?: TextGameInterlude | null;
  completed: boolean;
}
