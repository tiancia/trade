import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import {
  ArrowRight,
  Camera,
  CheckCircle2,
  ChevronLeft,
  Grid2X2,
  Home,
  ImagePlus,
  Inbox,
  ListFilter,
  LogIn,
  LogOut,
  MessageCircle,
  PackagePlus,
  Search,
  Send,
  ShieldCheck,
  Sparkles,
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
import { formatDate, formatTime } from './time';

type BusyState = 'auth' | 'publish' | 'delist' | 'conversation' | 'message' | null;
type ActionSheet = 'account' | 'publish' | null;

const EMPTY_PUBLISH_FORM = {
  title: '',
  description: '',
  categoryId: '',
  price: '',
};
const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

export function MarketplaceApp() {
  const [session, setSession] = useState<MarketplaceSession | null>(() => loadStoredSession());
  const [categories, setCategories] = useState<MarketplaceCategory[]>([]);
  const [items, setItems] = useState<MarketplaceItem[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | null>(null);
  const [queryInput, setQueryInput] = useState('');
  const query = useDebouncedValue(queryInput, 320);
  const [mine, setMine] = useState(false);
  const [selectedItemId, setSelectedItemId] = useState<number | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [actionSheet, setActionSheet] = useState<ActionSheet>(null);
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');
  const [authForm, setAuthForm] = useState({ username: '', password: '', displayName: '' });
  const [publishForm, setPublishForm] = useState(EMPTY_PUBLISH_FORM);
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [imagePreview, setImagePreview] = useState<string | null>(null);
  const [conversations, setConversations] = useState<MarketplaceConversation[]>([]);
  const [activeConversation, setActiveConversation] = useState<MarketplaceConversation | null>(null);
  const [messages, setMessages] = useState<MarketplaceMessage[]>([]);
  const [messageDrafts, setMessageDrafts] = useState<Record<number, string>>({});
  const [chatLoading, setChatLoading] = useState(false);
  const [chatOpen, setChatOpen] = useState(false);
  const [busy, setBusy] = useState<BusyState>(null);
  const [booted, setBooted] = useState(false);
  const [itemsLoading, setItemsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const itemRequestRef = useRef(0);
  const chatRequestRef = useRef(0);
  const conversationIdRef = useRef<number | null>(null);
  const sessionTokenRef = useRef(session?.token);
  const messageCursorRef = useRef<number | undefined>(undefined);

  const token = session?.token;
  const messageDraft = activeConversation ? messageDrafts[activeConversation.id] ?? '' : '';
  const selectedItem = useMemo(
    () => items.find((item) => item.id === selectedItemId) ?? items[0] ?? null,
    [items, selectedItemId],
  );

  useEffect(() => {
    sessionTokenRef.current = token;
    chatRequestRef.current += 1;
    conversationIdRef.current = null;
    messageCursorRef.current = undefined;
    setActiveConversation(null);
    setMessages([]);
    setMessageDrafts({});
    setChatLoading(false);
  }, [token]);

  useEffect(() => {
    let active = true;
    async function boot() {
      try {
        const nextCategories = await fetchCategories();
        if (!active) return;
        setCategories(nextCategories);
        setPublishForm((form) => ({
          ...form,
          categoryId: form.categoryId || String(nextCategories[0]?.id ?? ''),
        }));
        if (session?.token) {
          try {
            const user = await fetchMe(session.token);
            // Startup verification belongs only to the session that requested it.
            if (active && sessionTokenRef.current === session.token) {
              const verified = { ...session, user };
              setSession(verified);
              saveStoredSession(verified);
            }
          } catch (cause) {
            if (!active || sessionTokenRef.current !== session.token) return;
            if (isUnauthorized(cause)) {
              sessionTokenRef.current = undefined;
              clearStoredSession();
              setSession(null);
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
  }, []);

  const refreshItems = useCallback(async () => {
    const requestId = ++itemRequestRef.current;
    setItemsLoading(true);
    try {
      const nextItems = await fetchItems({ categoryId: selectedCategoryId, q: query, mine }, token);
      if (requestId !== itemRequestRef.current) return;
      setItems(nextItems);
      setSelectedItemId((current) => (
        current && nextItems.some((item) => item.id === current)
          ? current
          : nextItems[0]?.id ?? null
      ));
    } catch (cause) {
      if (requestId !== itemRequestRef.current) return;
      setError(messageOf(cause));
      if (mine && !token) setMine(false);
    } finally {
      if (requestId === itemRequestRef.current) setItemsLoading(false);
    }
  }, [mine, query, selectedCategoryId, token]);

  useEffect(() => {
    if (!booted) return;
    void refreshItems();
  }, [booted, refreshItems]);

  const refreshConversations = useCallback(async () => {
    if (!token) {
      setConversations([]);
      return [] as MarketplaceConversation[];
    }
    try {
      const next = await fetchConversations(token);
      if (sessionTokenRef.current !== token) return [] as MarketplaceConversation[];
      setConversations(next);
      return next;
    } catch (cause) {
      if (sessionTokenRef.current === token) setError(messageOf(cause));
      return [] as MarketplaceConversation[];
    }
  }, [token]);

  useEffect(() => {
    void refreshConversations();
  }, [refreshConversations]);

  useEffect(() => {
    if (!chatOpen || !activeConversation || !token || chatLoading) return;
    let stopped = false;
    let polling = false;
    const conversationId = activeConversation.id;
    const generation = chatRequestRef.current;
    async function poll() {
      if (polling) return;
      polling = true;
      try {
        const next = await fetchMessages(token!, conversationId, messageCursorRef.current);
        if (!stopped && isCurrentChat(conversationId, generation, token!)) {
          const matching = next.filter((message) => message.conversationId === conversationId);
          appendMessages(conversationId, matching);
          // A send response may jump ahead of unseen incoming messages, so only reads advance this cursor.
          if (matching.length) messageCursorRef.current = Math.max(...matching.map((message) => message.id));
        }
      } catch {
        // A transient polling failure should not interrupt the conversation UI.
      } finally {
        polling = false;
      }
    }
    const timer = window.setInterval(() => void poll(), 4000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [activeConversation, chatOpen, token, chatLoading]);

  useEffect(() => {
    if (!chatOpen) return;
    if (typeof messagesEndRef.current?.scrollIntoView === 'function') {
      messagesEndRef.current.scrollIntoView({ block: 'end' });
    }
  }, [chatOpen, messages]);

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
  }, [actionSheet, chatOpen, detailOpen]);

  useEffect(() => {
    if (!imageFile) {
      setImagePreview(null);
      return;
    }
    if (typeof URL.createObjectURL !== 'function') return;
    const preview = URL.createObjectURL(imageFile);
    setImagePreview(preview);
    return () => {
      if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(preview);
    };
  }, [imageFile]);

  async function handleAuthSubmit(event: FormEvent) {
    event.preventDefault();
    if (new TextEncoder().encode(authForm.password).length > 72) {
      setError('密码最多 72 字节，中文通常每字占 3 字节');
      return;
    }
    setBusy('auth');
    setError(null);
    try {
      const nextSession = authMode === 'login'
        ? await login(authForm.username, authForm.password)
        : await register(authForm.username, authForm.password, authForm.displayName);
      sessionTokenRef.current = nextSession.token;
      setSession(nextSession);
      saveStoredSession(nextSession);
      setAuthForm({ username: '', password: '', displayName: '' });
      setMine(false);
      setActionSheet(null);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(null);
    }
  }

  async function handleLogout() {
    if (!token) return;
    chatRequestRef.current += 1;
    conversationIdRef.current = null;
    sessionTokenRef.current = undefined;
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
      setActiveConversation(null);
      setMessages([]);
      setChatOpen(false);
      setActionSheet(null);
      setBusy(null);
    }
  }

  function handleImageChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    if (!file) {
      setImageFile(null);
      return;
    }
    if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
      setError('请选择 JPG、PNG、WebP 或 GIF 图片');
      event.target.value = '';
      setImageFile(null);
      return;
    }
    if (file.size <= 0 || file.size > MAX_IMAGE_SIZE_BYTES) {
      setError('图片大小需要在 10 MB 以内');
      event.target.value = '';
      setImageFile(null);
      return;
    }
    setError(null);
    setImageFile(file);
  }

  async function handlePublish(event: FormEvent) {
    event.preventDefault();
    if (!token) {
      setError('请先登录后再发布商品');
      setActionSheet('account');
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
      setMine(true);
      setItems((current) => [created, ...current.filter((item) => item.id !== created.id)]);
      setSelectedItemId(created.id);
      setDetailOpen(true);
      setActionSheet(null);
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
      setDetailOpen(false);
      setActionSheet('account');
      return;
    }
    if (item.seller.id === session.user.id) {
      setError('不能联系自己发布的商品');
      return;
    }
    setBusy('conversation');
    setError(null);
    const generation = chatRequestRef.current;
    try {
      const conversation = await createConversation(token, item.id);
      if (chatRequestRef.current !== generation || sessionTokenRef.current !== token) return;
      setDetailOpen(false);
      setChatOpen(true);
      await openConversation(conversation);
      await refreshConversations();
    } catch (cause) {
      if (chatRequestRef.current === generation && sessionTokenRef.current === token) setError(messageOf(cause));
    } finally {
      setBusy((current) => current === 'conversation' ? null : current);
    }
  }

  async function openConversation(conversation: MarketplaceConversation) {
    if (!token || sessionTokenRef.current !== token) return;
    const generation = ++chatRequestRef.current;
    conversationIdRef.current = conversation.id;
    messageCursorRef.current = undefined;
    setActiveConversation(conversation);
    setMessages([]);
    setChatLoading(true);
    try {
      const next = await fetchMessages(token, conversation.id);
      if (!isCurrentChat(conversation.id, generation, token)) return;
      const matching = next.filter((message) => message.conversationId === conversation.id);
      setMessages(matching);
      if (matching.length) messageCursorRef.current = Math.max(...matching.map((message) => message.id));
    } catch (cause) {
      if (isCurrentChat(conversation.id, generation, token)) setError(messageOf(cause));
    } finally {
      if (isCurrentChat(conversation.id, generation, token)) setChatLoading(false);
    }
  }

  async function openInbox() {
    if (!token) {
      setError('登录后才能查看消息');
      setActionSheet('account');
      return;
    }
    setChatOpen(true);
    const nextConversations = await refreshConversations();
    if (sessionTokenRef.current === token && conversationIdRef.current === null && nextConversations.length) {
      await openConversation(nextConversations[0]);
    }
  }

  async function handleSendMessage(event: FormEvent) {
    event.preventDefault();
    const body = messageDraft.trim();
    if (!token || !activeConversation || !body || chatLoading || busy === 'message') return;
    const conversationId = activeConversation.id;
    const generation = chatRequestRef.current;
    const sentDraft = messageDraft;
    setBusy('message');
    setError(null);
    try {
      const sent = await sendMessage(token, conversationId, body);
      if (sessionTokenRef.current !== token) return;
      if (isCurrentChat(conversationId, generation, token)) appendMessages(conversationId, [sent]);
      setMessageDrafts((current) => current[conversationId] === sentDraft ? { ...current, [conversationId]: '' } : current);
      await refreshConversations();
    } catch (cause) {
      if (isCurrentChat(conversationId, generation, token)) setError(messageOf(cause));
    } finally {
      setBusy((current) => current === 'message' ? null : current);
    }
  }

  function isCurrentChat(conversationId: number, generation: number, capturedToken: string) {
    return chatRequestRef.current === generation && conversationIdRef.current === conversationId && sessionTokenRef.current === capturedToken;
  }

  function appendMessages(conversationId: number, next: MarketplaceMessage[]) {
    if (conversationIdRef.current !== conversationId) return;
    setMessages((current) => {
      if (conversationIdRef.current !== conversationId) return current;
      const seen = new Set(current.map((message) => message.id));
      return [...current, ...next.filter((message) => message.conversationId === conversationId && !seen.has(message.id))]
        .sort((left, right) => left.id - right.id);
    });
  }

  function selectItem(item: MarketplaceItem) {
    setSelectedItemId(item.id);
    setDetailOpen(true);
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
          <button type="button" onClick={() => void openInbox()}>
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
          <button type="button" onClick={() => void openInbox()} aria-label="消息">
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

        <section className="marketplace-market" id="market">
          <div className="marketplace-market-heading">
            <div>
              <span className="marketplace-kicker">MARKETPLACE</span>
              <h2>{mine ? '我的闲置' : '发现闲置好物'}</h2>
            </div>
            <span className="marketplace-result-count">
              {itemsLoading ? '正在寻找好物…' : `当前展示 ${items.length} 件`}
            </span>
          </div>

          <div className="marketplace-toolbar">
            <label className="marketplace-search">
              <Search size={19} />
              <input
                aria-label="搜索商品"
                placeholder="搜索想要的好物"
                value={queryInput}
                maxLength={80}
                onChange={(event) => setQueryInput(event.target.value)}
              />
              {queryInput && (
                <button type="button" aria-label="清空搜索" onClick={() => setQueryInput('')}>
                  <X size={16} />
                </button>
              )}
            </label>
            <button
              className={`marketplace-toggle ${mine ? 'active' : ''}`}
              type="button"
              onClick={() => {
                if (!token) {
                  setError('请先登录后查看我的商品');
                  setActionSheet('account');
                  return;
                }
                setMine(!mine);
              }}
            >
              <ListFilter size={17} /> {mine ? '查看全部' : '我的商品'}
            </button>
          </div>

          <div className="marketplace-categories" aria-label="商品分类">
            <button
              type="button"
              className={selectedCategoryId === null ? 'active' : ''}
              onClick={() => setSelectedCategoryId(null)}
            >
              <Grid2X2 size={16} /> 全部
            </button>
            {categories.map((category) => (
              <button
                key={category.id}
                type="button"
                className={selectedCategoryId === category.id ? 'active' : ''}
                onClick={() => setSelectedCategoryId(category.id)}
              >
                <Tag size={15} /> {category.name}
              </button>
            ))}
          </div>

          <div className="marketplace-content">
            <section className="marketplace-grid" aria-label="商品列表" aria-busy={itemsLoading}>
              {itemsLoading && items.length === 0 && Array.from({ length: 8 }, (_, index) => (
                <div className="marketplace-item-skeleton" key={index} aria-hidden="true">
                  <span /><i /><i /><b />
                </div>
              ))}

              {items.map((item) => (
                <article
                  className={`marketplace-item-card ${selectedItem?.id === item.id ? 'selected' : ''}`}
                  key={item.id}
                >
                  <button type="button" onClick={() => selectItem(item)} aria-label={`查看 ${item.title}`}>
                    <figure>
                      <img src={item.imageUrl} alt={item.title} loading="lazy" decoding="async" />
                      <span className={`marketplace-status ${item.status === 'LISTED' ? 'listed' : 'delisted'}`}>
                        {item.status === 'LISTED' ? '在售' : '已下架'}
                      </span>
                    </figure>
                    <span className="marketplace-item-copy">
                      <small>{item.category.name}</small>
                      <strong>{item.title}</strong>
                      <span className="marketplace-item-meta">{item.seller.displayName} · {formatDate(item.updatedAt)}</span>
                      <span className="marketplace-item-footer">
                        <b>{formatPrice(item.price)}</b>
                        <i>查看详情 <ArrowRight size={14} /></i>
                      </span>
                    </span>
                  </button>
                </article>
              ))}

              {!itemsLoading && !items.length && (
                <div className="marketplace-empty">
                  <span><Inbox size={30} /></span>
                  <strong>{mine ? '还没有发布过商品' : '没有找到相关好物'}</strong>
                  <p>{mine ? '拍张照片，让闲置开启下一段旅程。' : '换个关键词或分类试试看。'}</p>
                  {mine && (
                    <button className="marketplace-primary" type="button" onClick={() => setActionSheet('publish')}>
                      发布第一件闲置
                    </button>
                  )}
                </div>
              )}
            </section>

            <button
              className={`marketplace-detail-backdrop ${detailOpen ? 'open' : ''}`}
              type="button"
              aria-label="关闭商品详情"
              onClick={() => setDetailOpen(false)}
            />
            <aside className={`marketplace-detail ${detailOpen ? 'open' : ''}`} aria-label="商品详情">
              {selectedItem ? (
                <>
                  <div className="marketplace-detail-mobile-head">
                    <button type="button" onClick={() => setDetailOpen(false)} aria-label="返回商品列表">
                      <ChevronLeft size={21} />
                    </button>
                    <strong>商品详情</strong>
                    <span />
                  </div>
                  <figure className="marketplace-detail-image">
                    <img src={selectedItem.imageUrl} alt={selectedItem.title} />
                    <span className={`marketplace-status ${selectedItem.status === 'LISTED' ? 'listed' : 'delisted'}`}>
                      {selectedItem.status === 'LISTED' ? '在售' : '已下架'}
                    </span>
                  </figure>
                  <div className="marketplace-detail-body">
                    <div className="marketplace-detail-head">
                      <span>{selectedItem.category.name}</span>
                      <strong>{formatPrice(selectedItem.price)}</strong>
                    </div>
                    <h2>{selectedItem.title}</h2>
                    <p>{selectedItem.description || '卖家暂未补充更多描述。'}</p>
                    <div className="marketplace-seller-card">
                      <span className="marketplace-avatar">{selectedItem.seller.displayName.slice(0, 1)}</span>
                      <div>
                        <small>卖家</small>
                        <strong>{selectedItem.seller.displayName}</strong>
                      </div>
                      <span>{formatDate(selectedItem.updatedAt)} 更新</span>
                    </div>
                    <div className="marketplace-detail-actions">
                      {session?.user.id === selectedItem.seller.id && selectedItem.status === 'LISTED' ? (
                        <button
                          className="marketplace-danger"
                          type="button"
                          disabled={busy === 'delist'}
                          onClick={() => void handleDelist(selectedItem)}
                        >
                          <Trash2 size={17} /> {busy === 'delist' ? '处理中…' : '下架商品'}
                        </button>
                      ) : (
                        <button
                          className="marketplace-primary"
                          type="button"
                          disabled={selectedItem.status !== 'LISTED' || busy === 'conversation'}
                          onClick={() => void handleContact(selectedItem)}
                        >
                          <MessageCircle size={17} />
                          {busy === 'conversation' ? '正在打开…' : '联系卖家'}
                        </button>
                      )}
                    </div>
                  </div>
                </>
              ) : (
                <div className="marketplace-empty marketplace-detail-empty">
                  <span><Inbox size={30} /></span>
                  <strong>选择一件商品查看详情</strong>
                </div>
              )}
            </aside>
          </div>
        </section>
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

        {actionSheet === 'account' && (
          <div className="marketplace-sheet-content">
            {session ? (
              <div className="marketplace-account-view">
                <span className="marketplace-account-avatar">{session.user.displayName.slice(0, 1)}</span>
                <div>
                  <small>已登录</small>
                  <h2>{session.user.displayName}</h2>
                  <p>@{session.user.username}</p>
                </div>
                <button className="marketplace-secondary" type="button" onClick={() => setActionSheet('publish')}>
                  <PackagePlus size={17} /> 发布闲置
                </button>
                <button className="marketplace-logout" type="button" disabled={busy === 'auth'} onClick={() => void handleLogout()}>
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
                <button className="marketplace-primary marketplace-wide" type="submit" disabled={busy === 'auth'}>
                  <LogIn size={17} /> {busy === 'auth' ? '请稍候…' : authMode === 'login' ? '登录' : '创建账号'}
                </button>
              </form>
            )}
          </div>
        )}

        {actionSheet === 'publish' && (
          <div className="marketplace-sheet-content">
            {!session ? (
              <div className="marketplace-login-required">
                <span><LogIn size={25} /></span>
                <h2>登录后即可发布</h2>
                <p>账号用来管理你的商品，并接收买家的消息。</p>
                <button className="marketplace-primary" type="button" onClick={() => setActionSheet('account')}>
                  去登录
                </button>
              </div>
            ) : (
              <form onSubmit={(event) => void handlePublish(event)} className="marketplace-form marketplace-publish-form">
                <label>
                  <span>商品标题</span>
                  <input
                    value={publishForm.title}
                    required
                    maxLength={120}
                    placeholder="品牌、型号和关键特点"
                    onChange={(event) => setPublishForm({ ...publishForm, title: event.target.value })}
                  />
                </label>
                <div className="marketplace-form-row">
                  <label>
                    <span>分类</span>
                    <select
                      value={publishForm.categoryId}
                      required
                      onChange={(event) => setPublishForm({ ...publishForm, categoryId: event.target.value })}
                    >
                      {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
                    </select>
                  </label>
                  <label>
                    <span>价格</span>
                    <input
                      type="number"
                      inputMode="decimal"
                      min="0"
                      max="9999999999.99"
                      step="0.01"
                      placeholder="留空则面议"
                      value={publishForm.price}
                      onChange={(event) => setPublishForm({ ...publishForm, price: event.target.value })}
                    />
                  </label>
                </div>
                <label>
                  <span>商品描述</span>
                  <textarea
                    rows={5}
                    value={publishForm.description}
                    maxLength={2000}
                    placeholder="成色、使用情况、配件和交易方式"
                    onChange={(event) => setPublishForm({ ...publishForm, description: event.target.value })}
                  />
                  <small>{publishForm.description.length}/2000</small>
                </label>
                <label className={`marketplace-upload ${imagePreview ? 'has-image' : ''}`}>
                  {imagePreview ? (
                    <img src={imagePreview} alt="待上传商品预览" />
                  ) : (
                    <span className="marketplace-upload-placeholder">
                      <Camera size={25} />
                      <strong>添加一张实拍图</strong>
                      <small>支持 JPG、PNG、WebP、GIF，最大 10 MB</small>
                    </span>
                  )}
                  <span className="marketplace-upload-action">
                    <UploadCloud size={16} /> {imageFile ? '更换图片' : '选择图片'}
                  </span>
                  <input type="file" aria-label="选择商品图片" accept="image/jpeg,image/png,image/webp,image/gif" onChange={handleImageChange} />
                </label>
                <button className="marketplace-primary marketplace-wide" type="submit" disabled={busy === 'publish'}>
                  <UploadCloud size={17} /> {busy === 'publish' ? '正在上传并发布…' : '确认发布'}
                </button>
              </form>
            )}
          </div>
        )}
      </aside>

      <ChatDrawer
        open={chatOpen}
        conversations={conversations}
        activeConversation={activeConversation}
        messages={messages}
        currentUserId={session?.user.id ?? null}
        draft={messageDraft}
        busy={busy === 'message' || chatLoading}
        messagesEndRef={messagesEndRef}
        onClose={() => setChatOpen(false)}
        onOpenConversation={(conversation) => void openConversation(conversation)}
        onDraft={(value) => {
          if (activeConversation) setMessageDrafts((current) => ({ ...current, [activeConversation.id]: value }));
        }}
        onSend={(event) => void handleSendMessage(event)}
      />

      <nav className="marketplace-mobile-nav" aria-label="移动端导航">
        <button type="button" onClick={scrollToMarket}>
          <Home size={20} /><span>逛一逛</span>
        </button>
        <button type="button" onClick={() => setActionSheet('publish')}>
          <span className="marketplace-mobile-publish"><PackagePlus size={23} /></span><span>发布</span>
        </button>
        <button type="button" onClick={() => void openInbox()}>
          <MessageCircle size={20} /><span>消息</span>
        </button>
        <button type="button" onClick={() => setActionSheet('account')}>
          <UserCircle size={21} /><span>{session ? '我的' : '登录'}</span>
        </button>
      </nav>
    </div>
  );
}

function ChatDrawer({
  open,
  conversations,
  activeConversation,
  messages,
  currentUserId,
  draft,
  busy,
  messagesEndRef,
  onClose,
  onOpenConversation,
  onDraft,
  onSend,
}: {
  open: boolean;
  conversations: MarketplaceConversation[];
  activeConversation: MarketplaceConversation | null;
  messages: MarketplaceMessage[];
  currentUserId: number | null;
  draft: string;
  busy: boolean;
  messagesEndRef: React.RefObject<HTMLDivElement>;
  onClose: () => void;
  onOpenConversation: (conversation: MarketplaceConversation) => void;
  onDraft: (value: string) => void;
  onSend: (event: FormEvent) => void;
}) {
  return (
    <>
      <button
        className={`marketplace-chat-backdrop ${open ? 'open' : ''}`}
        type="button"
        aria-label="关闭消息"
        onClick={onClose}
      />
      <aside className={`marketplace-chat ${open ? 'open' : ''}`} aria-label="聊天抽屉" aria-hidden={!open}>
        <header>
          <div>
            <span>MESSAGES</span>
            <strong>我的消息</strong>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭聊天"><X size={19} /></button>
        </header>
        <div className="marketplace-chat-body">
          <nav aria-label="会话列表">
            {conversations.map((conversation) => {
              const partner = conversation.buyer.id === currentUserId ? conversation.seller : conversation.buyer;
              return (
                <button
                  type="button"
                  key={conversation.id}
                  className={activeConversation?.id === conversation.id ? 'active' : ''}
                  onClick={() => onOpenConversation(conversation)}
                >
                  <img src={conversation.item.imageUrl} alt="" />
                  <span>
                    <strong>{conversation.item.title}</strong>
                    <small>{partner.displayName} · {conversation.lastMessage?.body ?? '开始聊聊吧'}</small>
                  </span>
                </button>
              );
            })}
            {!conversations.length && (
              <div className="marketplace-chat-empty">
                <MessageCircle size={24} />
                <strong>还没有会话</strong>
                <span>在商品详情中联系卖家后，会话会出现在这里。</span>
              </div>
            )}
          </nav>
          <section className="marketplace-message-pane">
            {activeConversation ? (
              <>
                <div className="marketplace-message-title">
                  <img src={activeConversation.item.imageUrl} alt="" />
                  <span>
                    <strong>{activeConversation.item.title}</strong>
                    <small>
                      与 {activeConversation.buyer.id === currentUserId
                        ? activeConversation.seller.displayName
                        : activeConversation.buyer.displayName} 沟通中
                    </small>
                  </span>
                </div>
                <div className="marketplace-messages" aria-live="polite">
                  {messages.map((message) => (
                    <div className={`marketplace-message ${message.sender.id === currentUserId ? 'mine' : ''}`} key={message.id}>
                      <span>{message.sender.displayName} · {formatTime(message.createdAt)}</span>
                      <p>{message.body}</p>
                    </div>
                  ))}
                  {!messages.length && <p className="marketplace-conversation-start">还没有消息，友好地打个招呼吧。</p>}
                  <div ref={messagesEndRef} />
                </div>
                <form className="marketplace-message-form" onSubmit={onSend}>
                  <input
                    aria-label="消息内容"
                    value={draft}
                    maxLength={1000}
                    autoComplete="off"
                    onChange={(event) => onDraft(event.target.value)}
                    placeholder="输入消息…"
                  />
                  <button type="submit" aria-label="发送消息" disabled={busy || !draft.trim()}>
                    <Send size={18} />
                  </button>
                </form>
              </>
            ) : (
              <div className="marketplace-chat-placeholder">
                <span><MessageCircle size={30} /></span>
                <strong>选择一段会话</strong>
                <p>与买家或卖家的消息会显示在这里。</p>
              </div>
            )}
          </section>
        </div>
      </aside>
    </>
  );
}

function useDebouncedValue<T>(value: T, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [delay, value]);
  return debounced;
}

function formatPrice(price: number | null) {
  return price == null
    ? '面议'
    : `¥${price.toLocaleString('zh-CN', {
        minimumFractionDigits: price % 1 ? 2 : 0,
        maximumFractionDigits: 2,
      })}`;
}

function messageOf(cause: unknown) {
  if (cause instanceof Error) {
    const translations: Record<string, string> = {
      'username or password is invalid': '用户名或密码不正确',
      'password must be at least 8 characters and at most 72 UTF-8 bytes': '密码至少 8 个字符、最多 72 字节，中文通常每字占 3 字节',
      'username is already registered': '这个用户名已经被注册',
      'marketplace session is invalid or expired': '登录状态已过期，请重新登录',
      'item is no longer listed': '该商品已下架',
      'Aliyun STS request failed': '图片服务暂时不可用，请稍后重试',
    };
    return translations[cause.message] ?? cause.message;
  }
  return '操作失败，请稍后重试';
}

function isUnauthorized(cause: unknown) {
  return typeof cause === 'object'
    && cause !== null
    && 'status' in cause
    && cause.status === 401;
}
