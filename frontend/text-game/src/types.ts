export type NumberMap = Record<string, number>;
export type FlagMap = Record<string, unknown>;

export interface TextGameCatalog { stories: StorySummary[]; }
export interface StorySummary {
  storyKey: string;
  title: string;
  summary: string;
  durationMinutes: number;
  maxChoices: number;
  tags: string[];
  coverImage?: string | null;
  version: number;
}
export interface StoryRef { storyKey: string; title: string; version: number; }
export type TextGamePhase = 'scene' | 'result' | 'completed';
export interface Progress { turn: number; maxTurns: number; chapterNumber: number; chapterTitle: string; date: string; }
export interface TextGameChoice { id: string; label: string; hint?: string | null; enabled: boolean; disabledReason?: string | null; }
export interface TextGameScene { nodeId: string; title: string; text: string[]; choices: TextGameChoice[]; }
export interface EffectSummary { attributes: NumberMap; relations: NumberMap; flags: FlagMap; }
export interface ChoiceResult { choiceId: string; text: string[]; effects: EffectSummary; }
export interface TextGameEnding { nodeId: string; title: string; grade: string; text: string[]; echoes: string[]; }
export interface TextGameSession {
  sessionId: string;
  story: StoryRef;
  revision: number;
  phase: TextGamePhase;
  progress: Progress;
  scene?: TextGameScene | null;
  result?: ChoiceResult | null;
  ending?: TextGameEnding | null;
  attributes: NumberMap;
  relations: NumberMap;
  flags: FlagMap;
}
