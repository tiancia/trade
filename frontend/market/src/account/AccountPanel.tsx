import { useState } from 'react';
import type { FormEvent } from 'react';
import { LogIn, LogOut, PackagePlus } from 'lucide-react';
import { login, register } from '../api';
import type { MarketplaceSession } from '../api';
import { messageOf } from '../errors';

type AccountPanelProps = {
  active: boolean;
  session: MarketplaceSession | null;
  onAuthenticated: (session: MarketplaceSession) => void;
  onLogout: () => Promise<void>;
  onPublish: () => void;
  onError: (message: string | null) => void;
};

export function AccountPanel({ active, session, onAuthenticated, onLogout, onPublish, onError }: AccountPanelProps) {
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');
  const [authForm, setAuthForm] = useState({ username: '', password: '', displayName: '' });
  const [busy, setBusy] = useState(false);

  async function handleAuthSubmit(event: FormEvent) {
    event.preventDefault();
    if (new TextEncoder().encode(authForm.password).length > 72) {
      onError('密码最多 72 字节，中文通常每字占 3 字节');
      return;
    }
    setBusy(true);
    onError(null);
    try {
      const nextSession = authMode === 'login'
        ? await login(authForm.username, authForm.password)
        : await register(authForm.username, authForm.password, authForm.displayName);
      setAuthForm({ username: '', password: '', displayName: '' });
      onAuthenticated(nextSession);
    } catch (cause) {
      onError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  }

  async function handleLogout() {
    setBusy(true);
    try {
      await onLogout();
    } finally {
      setBusy(false);
    }
  }

  if (!active) return null;
  return (
    <div className="marketplace-sheet-content">
      {session ? (
        <div className="marketplace-account-view">
          <span className="marketplace-account-avatar">{session.user.displayName.slice(0, 1)}</span>
          <div>
            <small>已登录</small>
            <h2>{session.user.displayName}</h2>
            <p>@{session.user.username}</p>
          </div>
          <button className="marketplace-secondary" type="button" onClick={onPublish}>
            <PackagePlus size={17} /> 发布闲置
          </button>
          <button className="marketplace-logout" type="button" disabled={busy} onClick={() => void handleLogout()}>
            <LogOut size={17} /> 退出登录
          </button>
        </div>
      ) : (
        <form onSubmit={(event) => void handleAuthSubmit(event)} className="marketplace-form">
          <div className="marketplace-segmented" role="tablist" aria-label="账号模式">
            <button
              type="button"
              role="tab"
              aria-selected={authMode === 'login'}
              className={authMode === 'login' ? 'active' : ''}
              onClick={() => setAuthMode('login')}
            >
              登录
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={authMode === 'register'}
              className={authMode === 'register' ? 'active' : ''}
              onClick={() => setAuthMode('register')}
            >
              注册
            </button>
          </div>
          <label>
            <span>用户名</span>
            <input
              value={authForm.username}
              autoComplete="username"
              required
              minLength={3}
              maxLength={32}
              pattern="[A-Za-z0-9_.-]{3,32}"
              placeholder="3-32 位字母、数字或符号"
              onChange={(event) => setAuthForm({ ...authForm, username: event.target.value })}
            />
          </label>
          {authMode === 'register' && (
            <label>
              <span>昵称</span>
              <input
                value={authForm.displayName}
                autoComplete="nickname"
                maxLength={40}
                placeholder="大家怎么称呼你"
                onChange={(event) => setAuthForm({ ...authForm, displayName: event.target.value })}
              />
            </label>
          )}
          <label>
            <span>密码</span>
            <input
              type="password"
              value={authForm.password}
              autoComplete={authMode === 'login' ? 'current-password' : 'new-password'}
              required
              minLength={8}
              maxLength={72}
              placeholder="至少 8 位，最多 72 字节"
              onChange={(event) => setAuthForm({ ...authForm, password: event.target.value })}
            />
          </label>
          <button className="marketplace-primary marketplace-wide" type="submit" disabled={busy}>
            <LogIn size={17} /> {busy ? '请稍候…' : authMode === 'login' ? '登录' : '创建账号'}
          </button>
        </form>
      )}
    </div>
  );
}
