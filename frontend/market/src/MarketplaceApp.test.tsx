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
      bucket: 'bucket',
      region: 'oss-cn-hangzhou',
      credentials: {
        accessKeyId: 'ak',
        accessKeySecret: 'sk',
        securityToken: 'token',
        expiration: '2026-06-19T09:00:00Z',
      },
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
    fireEvent.click(screen.getByRole('button', { name: /^发布$/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('请先登录后再发布商品');
    expect(api.createUploadIntent).not.toHaveBeenCalled();
  });

  it('publishes an item after uploading through an OSS intent', async () => {
    setup({ session: sellerSession, items: [] });

    await screen.findByText('@seller');
    fireEvent.change(screen.getByLabelText('标题'), { target: { value: '机械键盘' } });
    fireEvent.change(screen.getByLabelText('分类'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('价格'), { target: { value: '199' } });
    fireEvent.change(screen.getByLabelText('描述'), { target: { value: '青轴，配件齐全' } });
    fireEvent.change(screen.getByLabelText(/选择图片/), {
      target: { files: [new File(['image'], 'keyboard.png', { type: 'image/png' })] },
    });
    fireEvent.click(screen.getByRole('button', { name: /发布$/ }));

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
      vi.advanceTimersByTime(3000);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(screen.getByText('今天可取。')).toBeVisible();
  });
});
