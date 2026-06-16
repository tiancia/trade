import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import type { TextGameCatalog, TextGameSession } from './types';

const catalog: TextGameCatalog = {
  stories: [{
    storyKey: '100-days-comeback', title: '100天翻身', summary: '测试剧情', durationMinutes: 12,
    maxChoices: 6, tags: ['都市'], version: 1,
  }],
};

const sceneSession: TextGameSession = {
  sessionId: 'session-1', story: { storyKey: '100-days-comeback', title: '100天翻身', version: 1 },
  revision: 0, phase: 'scene',
  progress: { turn: 0, maxTurns: 6, chapterNumber: 1, chapterTitle: '谷底', date: '第1天' },
  scene: {
    nodeId: 'opening', title: '最后一张催款单', text: ['第一段文字。', '第二段文字。'],
    choices: [
      { id: 'open', label: '可用选项', hint: '向前走', enabled: true },
      { id: 'locked', label: '禁用选项', enabled: false, disabledReason: '技能不足' },
    ],
  },
  attributes: { cash: -30, health: 60 }, relations: { linXia: 5 }, flags: {},
};

const resultSession: TextGameSession = {
  ...sceneSession,
  revision: 1,
  phase: 'result',
  scene: null,
  progress: { ...sceneSession.progress, turn: 1 },
  result: {
    choiceId: 'open', text: ['后果第一段。', '后果第二段。'],
    effects: { attributes: { cash: 20 }, relations: { linXia: 5 }, flags: { route: 'test' } },
  },
};

describe('text game app', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('reveals scene beats before choices and shows disabled reasons', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/catalog')) return json(catalog);
      if (url.endsWith('/sessions') && init?.method === 'POST') return json(sceneSession);
      throw new Error(`Unexpected request: ${url}`);
    }));

    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /开始故事/ }));
    expect(await screen.findByText('第一段文字。')).toBeVisible();
    expect(screen.queryByText('第二段文字。')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /可用选项/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /继续阅读/ }));
    expect(await screen.findByText('第二段文字。')).toBeVisible();
    expect(screen.getByRole('button', { name: /可用选项/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: /禁用选项/ })).toBeDisabled();
    expect(screen.getByText('技能不足')).toBeVisible();
  });

  it('restores a result, reveals consequences, and confirms the next scene', async () => {
    localStorage.setItem('text-game-session-id-v2', 'session-1');
    const nextScene: TextGameSession = {
      ...sceneSession,
      revision: 2,
      scene: { ...sceneSession.scene!, nodeId: 'next', title: '下一幕标题' },
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/catalog')) return json(catalog);
      if (url.endsWith('/sessions/session-1') && !init?.method) return json(resultSession);
      if (url.endsWith('/sessions/session-1/continue') && init?.method === 'POST') return json(nextScene);
      throw new Error(`Unexpected request: ${url}`);
    }));

    render(<App />);
    expect(await screen.findByText('后果第一段。')).toBeVisible();
    expect(screen.queryByText('后果第二段。')).not.toBeInTheDocument();
    expect(screen.getByText('现金 +20')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /查看后果/ }));
    expect(await screen.findByText('后果第二段。')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /进入下一幕/ }));
    expect(await screen.findByRole('heading', { name: '下一幕标题' })).toBeVisible();
  });

  it('announces API errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: '目录不可用' }), {
      status: 503, headers: { 'Content-Type': 'application/json' },
    })));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('目录不可用');
    await waitFor(() => expect(screen.queryByText('正在读取存档')).not.toBeInTheDocument());
  });
});

function json(value: unknown) {
  return Promise.resolve(new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }));
}
