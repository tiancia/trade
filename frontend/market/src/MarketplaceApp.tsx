import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  Camera,
  Grid2X2,
  Inbox,
  ListFilter,
  LogIn,
  LogOut,
  MessageCircle,
  PackagePlus,
  Search,
  Send,
  Store,
  Tag,
  Trash2,
  UploadCloud,
  UserCircle,
  X,
} from 'lucide-react';
import {
  clearStoredSession,
  createConversation,
  createItem,
  createUploadIntent,
  delistItem,
  fetchCategories,
  fetchConversations,
  fetchItems,
  fetchMe,
  fetchMessages,
  loadStoredSession,
  login,
  logout,
  register,
  saveStoredSession,
  sendMessage,
  uploadFileToOss,
} from './api';
import type {
  MarketplaceCategory,
  MarketplaceConversation,
  MarketplaceItem,
  MarketplaceMessage,
  MarketplaceSession,
} from './api';

type BusyState = 'boot' | 'auth' | 'items' | 'publish' | 'delist' | 'conversation' | 'message' | null;

const EMPTY_PUBLISH_FORM = {
  title: '',
  description: '',
  categoryId: '',
  price: '',
};

export function MarketplaceApp() {
  const [session, setSession] = useState<MarketplaceSession | null>(() => loadStoredSession());
  const [categories, setCategories] = useState<MarketplaceCategory[]>([]);
  const [items, setItems] = useState<MarketplaceItem[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | null>(null);
  const [query, setQuery] = useState('');
  const [mine, setMine] = useState(false);
  const [selectedItemId, setSelectedItemId] = useState<number | null>(null);
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');
  const [authForm, setAuthForm] = useState({ username: '', password: '', displayName: '' });
  const [publishForm, setPublishForm] = useState(EMPTY_PUBLISH_FORM);
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [conversations, setConversations] = useState<MarketplaceConversation[]>([]);
  const [activeConversation, setActiveConversation] = useState<MarketplaceConversation | null>(null);
  const [messages, setMessages] = useState<MarketplaceMessage[]>([]);
  const [messageDraft, setMessageDraft] = useState('');
  const [chatOpen, setChatOpen] = useState(false);
  const [busy, setBusy] = useState<BusyState>('boot');
  const [booted, setBooted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const messagesRef = useRef<MarketplaceMessage[]>([]);

  const token = session?.token;
  const selectedItem = useMemo(
    () => items.find((item) => item.id === selectedItemId) ?? items[0] ?? null,
    [items, selectedItemId],
  );

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  useEffect(() => {
    let active = true;
    async function boot() {
      try {
        const [nextCategories] = await Promise.all([fetchCategories()]);
        if (!active) return;
        setCategories(nextCategories);
        setPublishForm((form) => ({
          ...form,
          categoryId: form.categoryId || String(nextCategories[0]?.id ?? ''),
        }));
        if (session?.token) {
          try {
            const user = await fetchMe(session.token);
            if (active) {
              const verified = { ...session, user };
              setSession(verified);
              saveStoredSession(verified);
            }
          } catch {
            clearStoredSession();
            if (active) setSession(null);
          }
        }
      } catch (cause) {
        if (active) setError(messageOf(cause));
      } finally {
        if (active) {
          setBusy(null);
          setBooted(true);
        }
      }
    }
    void boot();
    return () => { active = false; };
  }, []);

  const refreshItems = useCallback(async () => {
    setBusy((value) => value ?? 'items');
    try {
      const nextItems = await fetchItems({ categoryId: selectedCategoryId, q: query, mine }, token);
      setItems(nextItems);
      setSelectedItemId((current) => current && nextItems.some((item) => item.id === current) ? current : nextItems[0]?.id ?? null);
    } catch (cause) {
      setError(messageOf(cause));
      if (mine && !token) setMine(false);
    } finally {
      setBusy((value) => value === 'items' ? null : value);
    }
  }, [mine, query, selectedCategoryId, token]);

  useEffect(() => {
    if (!booted) return;
    void refreshItems();
  }, [booted, refreshItems]);

  const refreshConversations = useCallback(async () => {
    if (!token) {
      setConversations([]);
      return;
    }
    try {
      setConversations(await fetchConversations(token));
    } catch (cause) {
      setError(messageOf(cause));
    }
  }, [token]);

  useEffect(() => {
    void refreshConversations();
  }, [refreshConversations]);

  useEffect(() => {
    if (!chatOpen || !activeConversation || !token) return;
    let stopped = false;
    async function poll() {
      const latest = messagesRef.current[messagesRef.current.length - 1];
      const latestId = latest?.id;
      try {
        const next = await fetchMessages(token!, activeConversation!.id, latestId);
        if (!stopped && next.length) appendMessages(next);
      } catch (cause) {
        if (!stopped) setError(messageOf(cause));
      }
    }
    const timer = window.setInterval(() => void poll(), 3000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [activeConversation, chatOpen, token]);

  async function handleAuthSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy('auth');
    setError(null);
    try {
      const nextSession = authMode === 'login'
        ? await login(authForm.username, authForm.password)
        : await register(authForm.username, authForm.password, authForm.displayName);
      setSession(nextSession);
      saveStoredSession(nextSession);
      setAuthForm({ username: '', password: '', displayName: '' });
      setMine(false);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  }

  async function handleLogout() {
    if (!token) return;
    setBusy('auth');
    try {
      await logout(token);
    } catch {
      // Local logout should still clear stale browser state.
    } finally {
      clearStoredSession();
      setSession(null);
      setMine(false);
      setConversations([]);
      setChatOpen(false);
      setBusy(null);
    }
  }

  async function handlePublish(event: FormEvent) {
    event.preventDefault();
    if (!token) {
      setError('请先登录后再发布商品');
      return;
    }
    if (!imageFile) {
      setError('请选择商品图片');
      return;
    }
    setBusy('publish');
    setError(null);
    try {
      const intent = await createUploadIntent(token, imageFile);
      const imageUrl = await uploadFileToOss(imageFile, intent);
      const created = await createItem(token, {
        title: publishForm.title,
        description: publishForm.description,
        categoryId: Number(publishForm.categoryId),
        imageUrl,
        price: publishForm.price ? Number(publishForm.price) : null,
      });
      setPublishForm({ ...EMPTY_PUBLISH_FORM, categoryId: publishForm.categoryId });
      setImageFile(null);
      setItems((current) => [created, ...current.filter((item) => item.id !== created.id)]);
      setMine(true);
      setSelectedItemId(created.id);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  }

  async function handleDelist(item: MarketplaceItem) {
    if (!token) return;
    setBusy('delist');
    setError(null);
    try {
      const updated = await delistItem(token, item.id);
      setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  }

  async function handleContact(item: MarketplaceItem) {
    if (!token || !session) {
      setError('请先登录后联系卖家');
      return;
    }
    if (item.seller.id === session.user.id) {
      setError('不能联系自己发布的商品');
      return;
    }
    setBusy('conversation');
    setError(null);
    try {
      const conversation = await createConversation(token, item.id);
      setActiveConversation(conversation);
      setChatOpen(true);
      setMessages(await fetchMessages(token, conversation.id));
      await refreshConversations();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  }

  async function openConversation(conversation: MarketplaceConversation) {
    if (!token) return;
    setActiveConversation(conversation);
    setChatOpen(true);
    setMessages(await fetchMessages(token, conversation.id));
  }

  async function handleSendMessage(event: FormEvent) {
    event.preventDefault();
    if (!token || !activeConversation || !messageDraft.trim()) return;
    setBusy('message');
    setError(null);
    try {
      const sent = await sendMessage(token, activeConversation.id, messageDraft);
      appendMessages([sent]);
      setMessageDraft('');
      await refreshConversations();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  }

  function appendMessages(next: MarketplaceMessage[]) {
    setMessages((current) => {
      const seen = new Set(current.map((message) => message.id));
      return [...current, ...next.filter((message) => !seen.has(message.id))];
    });
  }

  return (
    <div className="marketplace-shell">
      <header className="marketplace-topbar">
        <a className="marketplace-brand" href="/">
          <Store size={22} />
          <span>二手集市</span>
        </a>
        <div className="marketplace-top-actions">
          {session ? (
            <>
              <button className="marketplace-icon-text" type="button" onClick={() => setChatOpen(true)}>
                <MessageCircle size={17} /> 消息
              </button>
              <span className="marketplace-user"><UserCircle size={17} /> {session.user.displayName}</span>
              <button className="marketplace-icon-text" type="button" onClick={() => void handleLogout()} disabled={busy === 'auth'}>
                <LogOut size={17} /> 退出
              </button>
            </>
          ) : (
            <span className="marketplace-user muted"><UserCircle size={17} /> 未登录</span>
          )}
        </div>
      </header>

      <main className="marketplace-layout">
        <aside className="marketplace-sidebar">
          <section className="marketplace-panel auth-panel">
            <div className="marketplace-section-title"><LogIn size={17} /><h2>{session ? '账号' : '登录后发布'}</h2></div>
            {session ? (
              <div className="marketplace-account-card">
                <strong>{session.user.displayName}</strong>
                <span>@{session.user.username}</span>
              </div>
            ) : (
              <form onSubmit={(event) => void handleAuthSubmit(event)} className="marketplace-form">
                <div className="marketplace-segmented" role="tablist" aria-label="账号模式">
                  <button type="button" className={authMode === 'login' ? 'active' : ''} onClick={() => setAuthMode('login')}>登录</button>
                  <button type="button" className={authMode === 'register' ? 'active' : ''} onClick={() => setAuthMode('register')}>注册</button>
                </div>
                <label>用户名<input value={authForm.username} onChange={(event) => setAuthForm({ ...authForm, username: event.target.value })} /></label>
                {authMode === 'register' && <label>昵称<input value={authForm.displayName} onChange={(event) => setAuthForm({ ...authForm, displayName: event.target.value })} /></label>}
                <label>密码<input type="password" value={authForm.password} onChange={(event) => setAuthForm({ ...authForm, password: event.target.value })} /></label>
                <button className="marketplace-primary" type="submit" disabled={busy === 'auth'}>{authMode === 'login' ? '登录' : '创建账号'}</button>
              </form>
            )}
          </section>

          <section className="marketplace-panel">
            <div className="marketplace-section-title"><PackagePlus size={17} /><h2>发布商品</h2></div>
            <form onSubmit={(event) => void handlePublish(event)} className="marketplace-form publish-form">
              <label>标题<input value={publishForm.title} onChange={(event) => setPublishForm({ ...publishForm, title: event.target.value })} /></label>
              <label>分类<select value={publishForm.categoryId} onChange={(event) => setPublishForm({ ...publishForm, categoryId: event.target.value })}>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
              <label>价格<input inputMode="decimal" placeholder="可选" value={publishForm.price} onChange={(event) => setPublishForm({ ...publishForm, price: event.target.value })} /></label>
              <label>描述<textarea rows={4} value={publishForm.description} onChange={(event) => setPublishForm({ ...publishForm, description: event.target.value })} /></label>
              <label className="marketplace-upload">
                <Camera size={17} />
                <span>{imageFile ? imageFile.name : '选择图片'}</span>
                <input type="file" accept="image/*" onChange={(event) => setImageFile(event.target.files?.[0] ?? null)} />
              </label>
              <button className="marketplace-primary" type="submit" disabled={busy === 'publish'}>
                <UploadCloud size={17} /> {busy === 'publish' ? '发布中' : '发布'}
              </button>
            </form>
          </section>
        </aside>

        <section className="marketplace-main">
          <div className="marketplace-toolbar">
            <div className="marketplace-search">
              <Search size={18} />
              <input aria-label="搜索商品" placeholder="搜索标题或描述" value={query} onChange={(event) => setQuery(event.target.value)} />
            </div>
            <button className={`marketplace-toggle ${mine ? 'active' : ''}`} type="button" onClick={() => token ? setMine(!mine) : setError('请先登录后查看我的商品')}>
              <ListFilter size={17} /> 我的商品
            </button>
          </div>

          <div className="marketplace-categories" aria-label="商品分类">
            <button type="button" className={selectedCategoryId === null ? 'active' : ''} onClick={() => setSelectedCategoryId(null)}>
              <Grid2X2 size={16} /> 全部
            </button>
            {categories.map((category) => (
              <button key={category.id} type="button" className={selectedCategoryId === category.id ? 'active' : ''} onClick={() => setSelectedCategoryId(category.id)}>
                <Tag size={16} /> {category.name}
              </button>
            ))}
          </div>

          {error && <div className="marketplace-alert" role="alert"><span>{error}</span><button type="button" onClick={() => setError(null)}>关闭</button></div>}

          <div className="marketplace-content">
            <section className="marketplace-grid" aria-label="商品列表">
              {items.map((item) => (
                <article className={`marketplace-item-card ${selectedItem?.id === item.id ? 'selected' : ''}`} key={item.id}>
                  <button type="button" onClick={() => setSelectedItemId(item.id)} aria-label={`查看 ${item.title}`}>
                    <img src={item.imageUrl} alt={item.title} />
                    <span className={`marketplace-status ${item.status === 'LISTED' ? 'listed' : 'delisted'}`}>{item.status === 'LISTED' ? '在售' : '已下架'}</span>
                    <strong>{item.title}</strong>
                    <small>{item.category.name} · {item.seller.displayName}</small>
                    <b>{formatPrice(item.price)}</b>
                  </button>
                </article>
              ))}
              {!items.length && (
                <div className="marketplace-empty">
                  <Inbox size={30} />
                  <strong>暂无商品</strong>
                  <span>调整筛选或发布第一件商品。</span>
                </div>
              )}
            </section>

            <aside className="marketplace-detail" aria-label="商品详情">
              {selectedItem ? (
                <>
                  <img src={selectedItem.imageUrl} alt={selectedItem.title} />
                  <div className="marketplace-detail-body">
                    <div className="marketplace-detail-head">
                      <span>{selectedItem.category.name}</span>
                      <strong>{formatPrice(selectedItem.price)}</strong>
                    </div>
                    <h1>{selectedItem.title}</h1>
                    <p>{selectedItem.description}</p>
                    <dl>
                      <div><dt>卖家</dt><dd>{selectedItem.seller.displayName}</dd></div>
                      <div><dt>状态</dt><dd>{selectedItem.status === 'LISTED' ? '在售' : '已下架'}</dd></div>
                      <div><dt>更新</dt><dd>{formatDate(selectedItem.updatedAt)}</dd></div>
                    </dl>
                    <div className="marketplace-detail-actions">
                      <button className="marketplace-primary" type="button" disabled={selectedItem.status !== 'LISTED' || busy === 'conversation'} onClick={() => void handleContact(selectedItem)}>
                        <MessageCircle size={17} /> 联系卖家
                      </button>
                      {session?.user.id === selectedItem.seller.id && selectedItem.status === 'LISTED' && (
                        <button className="marketplace-danger" type="button" disabled={busy === 'delist'} onClick={() => void handleDelist(selectedItem)}>
                          <Trash2 size={17} /> 下架
                        </button>
                      )}
                    </div>
                  </div>
                </>
              ) : (
                <div className="marketplace-empty detail-empty"><Inbox size={30} /><strong>选择商品查看详情</strong></div>
              )}
            </aside>
          </div>
        </section>
      </main>

      <ChatDrawer
        open={chatOpen}
        conversations={conversations}
        activeConversation={activeConversation}
        messages={messages}
        currentUserId={session?.user.id ?? null}
        draft={messageDraft}
        busy={busy === 'message'}
        onClose={() => setChatOpen(false)}
        onOpenConversation={(conversation) => void openConversation(conversation)}
        onDraft={setMessageDraft}
        onSend={(event) => void handleSendMessage(event)}
      />
    </div>
  );
}

function ChatDrawer({ open, conversations, activeConversation, messages, currentUserId, draft, busy, onClose, onOpenConversation, onDraft, onSend }: {
  open: boolean;
  conversations: MarketplaceConversation[];
  activeConversation: MarketplaceConversation | null;
  messages: MarketplaceMessage[];
  currentUserId: number | null;
  draft: string;
  busy: boolean;
  onClose: () => void;
  onOpenConversation: (conversation: MarketplaceConversation) => void;
  onDraft: (value: string) => void;
  onSend: (event: FormEvent) => void;
}) {
  return (
    <aside className={`marketplace-chat ${open ? 'open' : ''}`} aria-label="聊天抽屉" aria-hidden={!open}>
      <header>
        <strong>消息</strong>
        <button type="button" onClick={onClose} aria-label="关闭聊天"><X size={18} /></button>
      </header>
      <div className="marketplace-chat-body">
        <nav aria-label="会话列表">
          {conversations.map((conversation) => (
            <button type="button" key={conversation.id} className={activeConversation?.id === conversation.id ? 'active' : ''} onClick={() => onOpenConversation(conversation)}>
              <strong>{conversation.item.title}</strong>
              <span>{conversation.lastMessage?.body ?? '暂无消息'}</span>
            </button>
          ))}
          {!conversations.length && <p className="marketplace-chat-empty">还没有会话</p>}
        </nav>
        <section className="marketplace-message-pane">
          {activeConversation ? (
            <>
              <div className="marketplace-message-title">
                <strong>{activeConversation.item.title}</strong>
                <span>{activeConversation.buyer.id === currentUserId ? activeConversation.seller.displayName : activeConversation.buyer.displayName}</span>
              </div>
              <div className="marketplace-messages">
                {messages.map((message) => (
                  <div className={`marketplace-message ${message.sender.id === currentUserId ? 'mine' : ''}`} key={message.id}>
                    <span>{message.sender.displayName}</span>
                    <p>{message.body}</p>
                  </div>
                ))}
                {!messages.length && <p className="marketplace-chat-empty">打开话题，发送第一条消息。</p>}
              </div>
              <form className="marketplace-message-form" onSubmit={onSend}>
                <input aria-label="消息内容" value={draft} onChange={(event) => onDraft(event.target.value)} placeholder="输入消息" />
                <button type="submit" disabled={busy || !draft.trim()}><Send size={17} /></button>
              </form>
            </>
          ) : (
            <div className="marketplace-empty detail-empty"><MessageCircle size={30} /><strong>选择会话</strong></div>
          )}
        </section>
      </div>
    </aside>
  );
}

function formatPrice(price: number | null) {
  return price == null ? '面议' : `¥${price.toLocaleString('zh-CN', { minimumFractionDigits: price % 1 ? 2 : 0, maximumFractionDigits: 2 })}`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function messageOf(cause: unknown) {
  return cause instanceof Error ? cause.message : '操作失败，请稍后重试';
}
