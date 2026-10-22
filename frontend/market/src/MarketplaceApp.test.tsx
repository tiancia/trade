import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MarketplaceApp } from './MarketplaceApp';
import type { MarketplaceCategory, MarketplaceConversation, MarketplaceItem, MarketplaceMessage, MarketplaceSession } from './api';

const mockState = vi.hoisted(() => ({ storedSession: null as unknown }));
const api = vi.hoisted(() => ({
  clearStoredSession: vi.fn(),
  createConversation: vi.fn(),
  createItem: vi.fn(),
  createUploadIntent: vi.fn(),
  delistItem: vi.fn(),
  fetchCategories: vi.fn(),
  fetchConversations: vi.fn(),
  fetchItems: vi.fn(),
  fetchMe: vi.fn(),
  fetchMessages: vi.fn(),
  loadStoredSession: vi.fn(() => mockState.storedSession),
  login: vi.fn(),
  logout: vi.fn(),
  register: vi.fn(),
  saveStoredSession: vi.fn(),
  sendMessage: vi.fn(),
  uploadFileToOss: vi.fn(),
}));

vi.mock('./api', () => api);

const categories: MarketplaceCategory[] = [
  { id: 1, name: '手机数码', slug: 'phones-digital' },
  { id: 2, name: '图书文具', slug: 'books-stationery' },
];

const seller = { id: 1, username: 'seller', displayName: '卖家' };
const buyer = { id: 2, username: 'buyer', displayName: '买家' };

const sellerSession: MarketplaceSession = {
  token: 'seller-token',
  user: seller,
  expiresAt: '2026-07-19T00:00:00Z',
};

const buyerSession: MarketplaceSession = {
  token: 'buyer-token',
  user: buyer,
  expiresAt: '2026-07-19T00:00:00Z',
};

function item(overrides: Partial<MarketplaceItem> = {}): MarketplaceItem {
  return {
    id: 10,
    title: 'iPhone 15',
    description: '轻微使用痕迹',
    category: categories[0],
    imageUrl: 'https://cdn.example.com/phone.jpg',
    price: 3888,
    status: 'LISTED',
    seller,
    createdAt: '2026-06-19T08:00:00Z',
    updatedAt: '2026-06-19T08:00:00Z',
    ...overrides,
  };
}

function conversation(id: number, title: string): MarketplaceConversation {
  return { id, item: item({ id, title }), buyer, seller, lastMessage: null, updatedAt: '2026-10-21T00:00:00Z' };
}

function chatMessage(id: number, conversationId: number, body: string): MarketplaceMessage {
  return { id, conversationId, sender: seller, body, createdAt: 1792540800 };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function setup(options: { session?: MarketplaceSession | null; items?: MarketplaceItem[]; conversations?: MarketplaceConversation[] } = {}) {
  let currentItems = options.items ?? [item()];
  mockState.storedSession = options.session ?? null;
  api.fetchCategories.mockResolvedValue(categories);
  api.fetchMe.mockImplementation(async (token: string) => token === sellerSession.token ? seller : buyer);
  api.fetchConversations.mockResolvedValue(options.conversations ?? []);
  api.fetchItems.mockImplementation(async (filters: { categoryId?: number | null; q?: string; mine?: boolean }) => {
    return currentItems.filter((entry) => {
      if (filters.mine && options.session && entry.seller.id !== options.session.user.id) return false;
      if (filters.categoryId && entry.category.id !== filters.categoryId) return false;
      if (filters.q && !`${entry.title} ${entry.description}`.toLowerCase().includes(filters.q.toLowerCase())) return false;
      return !filters.mine ? entry.status === 'LISTED' : true;
    });
  });
  api.createItem.mockImplementation(async (_token: string, payload: { title: string; description: string; categoryId: number; imageUrl: string; price: number | null }) => {
    const created = item({
      id: 99,
      title: payload.title,
      description: payload.description,
      category: categories.find((category) => category.id === payload.categoryId) ?? categories[0],
      imageUrl: payload.imageUrl,
      price: payload.price,
      seller: options.session?.user ?? seller,
    });
    currentItems = [created, ...currentItems];
    return created;
  });
  api.delistItem.mockImplementation(async (_token: string, id: number) => {
    currentItems = currentItems.map((entry) => entry.id === id ? { ...entry, status: 'DELISTED' as const } : entry);
    return currentItems.find((entry) => entry.id === id);
  });
  return render(<MarketplaceApp />);
}

describe('marketplace app', () => {
  beforeEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
    mockState.storedSession = null;
    api.createUploadIntent.mockResolvedValue({
      objectKey: 'marketplace/users/1/phone.png',
      publicUrl: 'https://cdn.example.com/uploaded.png',
      uploadUrl: 'https://bucket.oss-cn-hangzhou.aliyuncs.com',
      formFields: { key: 'marketplace/users/1/phone.png', policy: 'policy', 'x-oss-signature': 'signature' },
      expiration: '2026-06-19T09:00:00Z',
    });
    api.uploadFileToOss.mockResolvedValue('https://cdn.example.com/uploaded.png');
    api.fetchMessages.mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it('blocks publishing while logged out', async () => {
    setup({ session: null });

    await screen.findAllByText('iPhone 15');
    fireEvent.click(within(screen.getByRole('navigation', { name: '主导航' })).getByRole('button', { name: /发布闲置/ }));

    expect(await screen.findByText('登录后即可发布')).toBeVisible();
    expect(api.createUploadIntent).not.toHaveBeenCalled();
  });

  it('rejects passwords over the UTF-8 byte limit before sending authentication', async () => {
    setup();
    const nav = await screen.findByRole('navigation', { name: '主导航' });
    fireEvent.click(within(nav).getByRole('button', { name: /^登录$/ }));
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'buyer' } });
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: '密'.repeat(25) } });
    fireEvent.submit(screen.getByLabelText('密码').closest('form')!);

    expect(await screen.findByRole('alert')).toHaveTextContent('密码最多 72 字节');
    expect(api.login).not.toHaveBeenCalled();
    expect(api.register).not.toHaveBeenCalled();
    expect(screen.getByLabelText('密码')).toHaveAttribute('maxlength', '72');
  });

  it('publishes an item after uploading through an OSS intent', async () => {
    setup({ session: sellerSession, items: [] });

    await screen.findByRole('button', { name: /卖家/ });
    fireEvent.click(within(screen.getByRole('navigation', { name: '主导航' })).getByRole('button', { name: /发布闲置/ }));
    fireEvent.change(screen.getByLabelText('商品标题'), { target: { value: '机械键盘' } });
    fireEvent.change(screen.getByLabelText('分类'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('价格'), { target: { value: '199' } });
    fireEvent.change(screen.getByLabelText(/^商品描述/), { target: { value: '青轴，配件齐全' } });
    fireEvent.change(screen.getByLabelText('选择商品图片'), {
      target: { files: [new File(['image'], 'keyboard.png', { type: 'image/png' })] },
    });
    fireEvent.click(screen.getByRole('button', { name: /确认发布/ }));

    await waitFor(() => expect(api.createUploadIntent).toHaveBeenCalled());
    expect(api.uploadFileToOss).toHaveBeenCalled();
    expect(api.createItem).toHaveBeenCalledWith('seller-token', expect.objectContaining({
      title: '机械键盘',
      imageUrl: 'https://cdn.example.com/uploaded.png',
      price: 199,
    }));
    expect((await screen.findAllByText('机械键盘')).length).toBeGreaterThan(0);
  });

  it('filters items by category', async () => {
    setup({
      items: [
        item(),
        item({ id: 11, title: '算法书', category: categories[1], imageUrl: 'https://cdn.example.com/book.jpg' }),
      ],
    });

    expect((await screen.findAllByText('iPhone 15')).length).toBeGreaterThan(0);
    fireEvent.click(within(screen.getByLabelText('商品分类')).getByRole('button', { name: /图书文具/ }));

    await waitFor(() => expect(screen.queryByText('iPhone 15')).not.toBeInTheDocument());
    expect(screen.getAllByText('算法书').length).toBeGreaterThan(0);
  });

  it('shows delist action only for the seller', async () => {
    setup({ session: sellerSession, items: [item()] });

    await screen.findAllByText('iPhone 15');
    const detail = screen.getByLabelText('商品详情');
    expect(within(detail).getByRole('button', { name: /下架/ })).toBeVisible();
    fireEvent.click(within(detail).getByRole('button', { name: /下架/ }));

    await waitFor(() => expect(api.delistItem).toHaveBeenCalledWith('seller-token', 10));
  });

  it('polls chat and appends new messages', async () => {
    const camera = item({ id: 12, title: '尼康相机', seller });
    const conversation: MarketplaceConversation = {
      id: 30,
      item: camera,
      buyer,
      seller,
      lastMessage: null,
      updatedAt: '2026-06-19T08:00:00Z',
    };
    const polled: MarketplaceMessage = {
      id: 77,
      conversationId: 30,
      sender: seller,
      body: '今天可取。',
      createdAt: '2026-06-19T08:01:00Z',
    };
    api.createConversation.mockResolvedValue(conversation);
    api.fetchMessages.mockResolvedValueOnce([]).mockResolvedValueOnce([polled]);
    setup({ session: buyerSession, items: [camera], conversations: [conversation] });

    await screen.findAllByText('尼康相机');
    vi.useFakeTimers();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /联系卖家/ }));
      await Promise.resolve();
    });
    expect(screen.getAllByText('尼康相机').length).toBeGreaterThan(0);

    await act(async () => {
      vi.advanceTimersByTime(4000);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(screen.getByText('今天可取。')).toBeVisible();
  });

  it('rejects oversized images before requesting upload credentials', async () => {
    setup({ session: sellerSession, items: [] });

    await screen.findByRole('button', { name: /卖家/ });
    fireEvent.click(within(screen.getByRole('navigation', { name: '主导航' })).getByRole('button', { name: /发布闲置/ }));
    const oversized = new File(['image'], 'large.png', { type: 'image/png' });
    Object.defineProperty(oversized, 'size', { value: 10 * 1024 * 1024 + 1 });
    fireEvent.change(screen.getByLabelText('选择商品图片'), { target: { files: [oversized] } });

    expect(await screen.findByRole('alert')).toHaveTextContent('图片大小需要在 10 MB 以内');
    expect(api.createUploadIntent).not.toHaveBeenCalled();
  });

  it('ignores an old conversation response after switching to another conversation', async () => {
    const first = conversation(30, '会话 A 商品');
    const second = conversation(31, '会话 B 商品');
    const slow = deferred<MarketplaceMessage[]>();
    const fast = deferred<MarketplaceMessage[]>();
    api.fetchMessages.mockImplementation((_token: string, id: number) => id === first.id ? slow.promise : fast.promise);
    setup({ session: buyerSession, conversations: [first, second] });
    fireEvent.click(within(await screen.findByRole('navigation', { name: '主导航' })).getByRole('button', { name: /消息/ }));
    await waitFor(() => expect(api.fetchMessages).toHaveBeenCalledWith('buyer-token', first.id));
    fireEvent.click(within(screen.getByRole('navigation', { name: '会话列表' })).getByRole('button', { name: /会话 B 商品/ }));
    await act(async () => { fast.resolve([chatMessage(102, second.id, '属于 B 的消息')]); });
    expect(await screen.findByText('属于 B 的消息')).toBeVisible();
    await act(async () => { slow.resolve([chatMessage(101, first.id, '属于 A 的旧响应')]); });
    expect(screen.queryByText('属于 A 的旧响应')).not.toBeInTheDocument();
    expect(screen.getByText('属于 B 的消息')).toBeVisible();
    expect(document.querySelector('.marketplace-message-title')).toHaveTextContent('会话 B 商品');
  });

  it('keeps drafts per conversation and never appends a late send to the newly selected conversation', async () => {
    const first = conversation(30, '会话 A 商品');
    const second = conversation(31, '会话 B 商品');
    const sending = deferred<MarketplaceMessage>();
    api.sendMessage.mockReturnValue(sending.promise);
    setup({ session: buyerSession, conversations: [first, second] });
    fireEvent.click(within(await screen.findByRole('navigation', { name: '主导航' })).getByRole('button', { name: /消息/ }));
    const draft = await screen.findByRole('textbox', { name: '消息内容' });
    await waitFor(() => expect(api.fetchMessages).toHaveBeenCalledWith('buyer-token', first.id));
    fireEvent.change(draft, { target: { value: '发给 A 的内容' } });
    const list = screen.getByRole('navigation', { name: '会话列表' });
    fireEvent.click(within(list).getByRole('button', { name: /会话 B 商品/ }));
    await waitFor(() => expect(api.fetchMessages).toHaveBeenCalledWith('buyer-token', second.id));
    expect(draft).toHaveValue('');
    fireEvent.change(draft, { target: { value: 'B 尚未发送的草稿' } });
    fireEvent.click(within(list).getByRole('button', { name: /会话 A 商品/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled());
    expect(draft).toHaveValue('发给 A 的内容');
    fireEvent.click(screen.getByRole('button', { name: '发送消息' }));
    expect(api.sendMessage).toHaveBeenCalledWith('buyer-token', first.id, '发给 A 的内容');
    fireEvent.click(within(list).getByRole('button', { name: /会话 B 商品/ }));
    expect(draft).toHaveValue('B 尚未发送的草稿');
    await act(async () => { sending.resolve(chatMessage(103, first.id, '发给 A 的内容')); });
    expect(screen.queryByText('发给 A 的内容')).not.toBeInTheDocument();
    expect(draft).toHaveValue('B 尚未发送的草稿');
    fireEvent.click(within(list).getByRole('button', { name: /会话 A 商品/ }));
    expect(draft).toHaveValue('');
  });

  it('does not restore private messages from an in-flight response after logout', async () => {
    const first = conversation(30, '会话 A 商品');
    const slow = deferred<MarketplaceMessage[]>();
    api.fetchMessages.mockReturnValue(slow.promise);
    setup({ session: buyerSession, conversations: [first] });
    const nav = await screen.findByRole('navigation', { name: '主导航' });
    fireEvent.click(within(nav).getByRole('button', { name: /消息/ }));
    await waitFor(() => expect(api.fetchMessages).toHaveBeenCalled());
    fireEvent.click(within(nav).getByRole('button', { name: /买家/ }));
    fireEvent.click(screen.getByRole('button', { name: /退出登录/ }));
    await waitFor(() => expect(within(nav).getByRole('button', { name: /登录/ })).toBeVisible());
    await act(async () => { slow.resolve([chatMessage(104, first.id, '已退出账号的私信')]); });
    expect(screen.queryByText('已退出账号的私信')).not.toBeInTheDocument();
  });

  it('does not restore a logged-out account from a late startup verification', async () => {
    const verification = deferred<typeof buyer>();
    setup({ session: buyerSession });
    api.fetchMe.mockReturnValue(verification.promise);
    await waitFor(() => expect(api.fetchMe).toHaveBeenCalledWith('buyer-token'));
    const nav = screen.getByRole('navigation', { name: '主导航' });
    fireEvent.click(within(nav).getByRole('button', { name: /买家/ }));
    fireEvent.click(screen.getByRole('button', { name: /退出登录/ }));
    await waitFor(() => expect(within(nav).getByRole('button', { name: /^登录$/ })).toBeVisible());

    await act(async () => { verification.resolve(buyer); });

    expect(within(nav).getByRole('button', { name: /^登录$/ })).toBeVisible();
    expect(api.saveStoredSession).not.toHaveBeenCalled();
  });

  it('does not clear a new login when an old startup verification returns unauthorized', async () => {
    let rejectVerification!: (reason: unknown) => void;
    const verification = new Promise<never>((_resolve, reject) => { rejectVerification = reject; });
    setup({ session: buyerSession });
    api.fetchMe.mockReturnValue(verification);
    api.login.mockResolvedValue(sellerSession);
    await waitFor(() => expect(api.fetchMe).toHaveBeenCalledWith('buyer-token'));
    const nav = screen.getByRole('navigation', { name: '主导航' });
    fireEvent.click(within(nav).getByRole('button', { name: /买家/ }));
    fireEvent.click(screen.getByRole('button', { name: /退出登录/ }));
    await waitFor(() => expect(within(nav).getByRole('button', { name: /^登录$/ })).toBeVisible());
    fireEvent.click(within(nav).getByRole('button', { name: /^登录$/ }));
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'seller' } });
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'password123' } });
    fireEvent.submit(screen.getByLabelText('密码').closest('form')!);
    await waitFor(() => expect(within(nav).getByRole('button', { name: /卖家/ })).toBeVisible());
    api.clearStoredSession.mockClear();

    await act(async () => { rejectVerification({ status: 401 }); });

    expect(within(nav).getByRole('button', { name: /卖家/ })).toBeVisible();
    expect(api.clearStoredSession).not.toHaveBeenCalled();
    expect(api.saveStoredSession).toHaveBeenLastCalledWith(sellerSession);
  });

  it('does not skip unread incoming messages when a send response has a later ID', async () => {
    const first = conversation(30, '会话 A 商品');
    const initial = chatMessage(10, first.id, '已读消息');
    const incoming = chatMessage(11, first.id, '轮询前收到的消息');
    const sent = chatMessage(12, first.id, '我发送的消息');
    api.fetchMessages.mockResolvedValueOnce([initial]).mockResolvedValue([incoming, sent]);
    api.sendMessage.mockResolvedValue(sent);
    setup({ session: buyerSession, conversations: [first] });
    fireEvent.click(within(await screen.findByRole('navigation', { name: '主导航' })).getByRole('button', { name: /消息/ }));
    await screen.findByText('已读消息');
    fireEvent.change(screen.getByRole('textbox', { name: '消息内容' }), { target: { value: '我发送的消息' } });
    fireEvent.click(screen.getByRole('button', { name: '发送消息' }));
    await screen.findByText('我发送的消息');
    // Reopening is unnecessary: the next poll must continue from the last read, not the send ID.
    await waitFor(() => expect(api.fetchMessages).toHaveBeenCalledWith('buyer-token', first.id, 10), { timeout: 5000 });
    expect(await screen.findByText('轮询前收到的消息')).toBeVisible();
    expect(screen.getAllByText('我发送的消息')).toHaveLength(1);
  });
});
