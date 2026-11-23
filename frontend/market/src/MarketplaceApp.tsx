import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowRight, CheckCircle2, Home, ImagePlus, MessageCircle, PackagePlus,
  ShieldCheck, Sparkles, Store, UserCircle, X,
} from 'lucide-react';
import { clearStoredSession, fetchCategories, fetchMe, loadStoredSession, logout, saveStoredSession } from './api';
import type { MarketplaceCategory, MarketplaceItem, MarketplaceSession } from './api';
import { AccountPanel } from './account/AccountPanel';
import { ChatDrawer } from './chat/ChatDrawer';
import { useMarketplaceChat } from './chat/useMarketplaceChat';
import { isUnauthorized, messageOf } from './errors';
import { ItemBrowser } from './listings/ItemBrowser';
import { PublishPanel } from './listings/PublishPanel';
import { useItemListings } from './listings/useItemListings';

type ActionSheet = 'account' | 'publish' | null;

export function MarketplaceApp() {
  const [session, setSession] = useState(loadStoredSession);
  const [categories, setCategories] = useState<MarketplaceCategory[]>([]);
  const [actionSheet, setActionSheet] = useState<ActionSheet>(null);
  const [booted, setBooted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const token = session?.token;
  const sessionRef = useRef(session);
  const isCurrentSession = useCallback((captured: string) => sessionRef.current?.token === captured, []);
  const updateSession = useCallback((next: MarketplaceSession | null) => {
    sessionRef.current = next;
    setSession(next);
    if (next) saveStoredSession(next);
    else clearStoredSession();
  }, []);
  const listings = useItemListings(token, booted, setError);
  const chat = useMarketplaceChat(token, isCurrentSession, setError);
  const { detailOpen, setDetailOpen } = listings;
  const { chatOpen, setChatOpen } = chat;

  useEffect(() => {
    let active = true;
    const initial = sessionRef.current;
    async function boot() {
      try {
        const nextCategories = await fetchCategories();
        if (!active) return;
        setCategories(nextCategories);
        if (initial?.token) {
          try {
            const user = await fetchMe(initial.token);
            // Startup verification belongs only to the session that requested it.
            if (active && isCurrentSession(initial.token)) {
              updateSession({ ...initial, user });
            }
          } catch (cause) {
            if (!active || !isCurrentSession(initial.token)) return;
            if (isUnauthorized(cause)) {
              updateSession(null);
            } else {
              setError(messageOf(cause));
            }
          }
        }
      } catch (cause) {
        if (active) setError(messageOf(cause));
      } finally {
        if (active) setBooted(true);
      }
    }
    void boot();
    return () => {
      active = false;
    };
  }, [isCurrentSession, updateSession]);

  useEffect(() => {
    if (!actionSheet && !chatOpen && !detailOpen) return;
    const isMobileDetail = detailOpen && window.innerWidth <= 760;
    if (!actionSheet && !chatOpen && !isMobileDetail) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (chatOpen) setChatOpen(false);
      else if (actionSheet) setActionSheet(null);
      else setDetailOpen(false);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [actionSheet, chatOpen, detailOpen, setChatOpen, setDetailOpen]);

  function handleAuthenticated(next: MarketplaceSession) {
    updateSession(next);
    listings.setMine(false);
    setActionSheet(null);
  }

  async function handleLogout() {
    if (!token) return;
    sessionRef.current = null;
    try {
      await logout(token);
    } catch {
      // Local logout should still clear stale browser state.
    } finally {
      updateSession(null);
      listings.setMine(false);
      setActionSheet(null);
    }
  }

  function openInbox() {
    if (!token) {
      setError('登录后才能查看消息');
      setActionSheet('account');
      return;
    }
    void chat.openInbox();
  }

  function handleContact(item: MarketplaceItem) {
    if (!token || !session) {
      setError('请先登录后联系卖家');
      setDetailOpen(false);
      setActionSheet('account');
      return;
    }
    if (item.seller.id === session.user.id) {
      setError('不能联系自己发布的商品');
      return;
    }
    void chat.contactSeller(item.id, () => setDetailOpen(false));
  }

  function toggleMine() {
    if (!token) {
      setError('请先登录后查看我的商品');
      setActionSheet('account');
      return;
    }
    listings.setMine(!listings.mine);
  }

  function scrollToMarket() {
    document.getElementById('market')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  return (
    <div className="marketplace-shell">
      <header className="marketplace-topbar">
        <button className="marketplace-brand" type="button" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}>
          <span className="marketplace-brand-mark"><Store size={20} /></span>
          <span>
            <strong>拾光集</strong>
            <small>让好物继续发光</small>
          </span>
        </button>

        <nav className="marketplace-desktop-nav" aria-label="主导航">
          <button type="button" onClick={scrollToMarket}>逛好物</button>
          <button type="button" onClick={openInbox}>
            <MessageCircle size={17} /> 消息
          </button>
          <button type="button" onClick={() => setActionSheet('account')}>
            <UserCircle size={17} /> {session ? session.user.displayName : '登录'}
          </button>
          <button className="marketplace-header-publish" type="button" onClick={() => setActionSheet('publish')}>
            <PackagePlus size={17} /> 发布闲置
          </button>
        </nav>

        <div className="marketplace-mobile-top-actions">
          <button type="button" onClick={openInbox} aria-label="消息">
            <MessageCircle size={20} />
          </button>
          <button type="button" onClick={() => setActionSheet('account')} aria-label={session ? '我的账号' : '登录'}>
            <UserCircle size={21} />
          </button>
        </div>
      </header>

      <main>
        <section className="marketplace-hero">
          <div className="marketplace-hero-copy">
            <span className="marketplace-eyebrow"><Sparkles size={15} /> 好物循环计划</span>
            <h1>好物不落灰，<br /><em>转手遇见新主人。</em></h1>
            <p>把暂时用不到的物品交给真正需要它的人。轻松发布、直接沟通，让每一次转手都简单一点。</p>
            <div className="marketplace-hero-actions">
              <button className="marketplace-primary marketplace-hero-primary" type="button" onClick={() => setActionSheet('publish')}>
                <ImagePlus size={18} /> 发布我的闲置
              </button>
              <button className="marketplace-ghost" type="button" onClick={scrollToMarket}>
                先逛一逛 <ArrowRight size={17} />
              </button>
            </div>
            <div className="marketplace-hero-metrics" aria-label="平台特点">
              <div><strong>{categories.length || 7}</strong><span>精选分类</span></div>
              <div><strong>1 对 1</strong><span>买卖沟通</span></div>
              <div><strong>随时</strong><span>自主下架</span></div>
            </div>
          </div>

          <div className="marketplace-hero-card" aria-label="交易提示">
            <div className="marketplace-hero-card-glow" />
            <span className="marketplace-hero-card-icon"><ShieldCheck size={28} /></span>
            <small>轻松转手 · 安心沟通</small>
            <h2>一张实拍图，<br />开启物品的新旅程</h2>
            <ul>
              <li><CheckCircle2 size={17} /> 私聊仅买卖双方可见</li>
              <li><CheckCircle2 size={17} /> 商品状态由卖家管理</li>
              <li><CheckCircle2 size={17} /> 图片直接安全上传</li>
            </ul>
          </div>
        </section>

        <ItemBrowser
          listings={listings}
          categories={categories}
          currentUserId={session?.user.id}
          contacting={chat.contacting}
          onToggleMine={toggleMine}
          onPublish={() => setActionSheet('publish')}
          onContact={handleContact}
        />
      </main>

      {error && (
        <div className="marketplace-toast" role="alert">
          <span>{error}</span>
          <button type="button" aria-label="关闭提示" onClick={() => setError(null)}><X size={17} /></button>
        </div>
      )}

      <button
        className={`marketplace-overlay ${actionSheet ? 'open' : ''}`}
        type="button"
        aria-label="关闭弹窗"
        onClick={() => setActionSheet(null)}
      />
      <aside className={`marketplace-action-sheet ${actionSheet ? 'open' : ''}`} aria-label="账号与发布">
        <header>
          <div>
            <span>{actionSheet === 'publish' ? 'SELL SOMETHING' : 'YOUR ACCOUNT'}</span>
            <strong>{actionSheet === 'publish' ? '发布闲置' : session ? '我的账号' : '欢迎来到拾光集'}</strong>
          </div>
          <button type="button" onClick={() => setActionSheet(null)} aria-label="关闭"><X size={19} /></button>
        </header>

        <AccountPanel
          active={actionSheet === 'account'}
          session={session}
          onAuthenticated={handleAuthenticated}
          onLogout={handleLogout}
          onPublish={() => setActionSheet('publish')}
          onError={setError}
        />
        <PublishPanel
          key={token}
          active={actionSheet === 'publish'}
          token={token}
          isCurrentSession={isCurrentSession}
          categories={categories}
          onLogin={() => setActionSheet('account')}
          onPublished={(item) => {
            listings.showPublished(item);
            setActionSheet(null);
          }}
          onError={setError}
        />
      </aside>

      <ChatDrawer chat={chat} currentUserId={session?.user.id} />

      <nav className="marketplace-mobile-nav" aria-label="移动端导航">
        <button type="button" onClick={scrollToMarket}>
          <Home size={20} /><span>逛一逛</span>
        </button>
        <button type="button" onClick={() => setActionSheet('publish')}>
          <span className="marketplace-mobile-publish"><PackagePlus size={23} /></span><span>发布</span>
        </button>
        <button type="button" onClick={openInbox}>
          <MessageCircle size={20} /><span>消息</span>
        </button>
        <button type="button" onClick={() => setActionSheet('account')}>
          <UserCircle size={21} /><span>{session ? '我的' : '登录'}</span>
        </button>
      </nav>
    </div>
  );
}
