export const MARKETPLACE_SESSION_KEY = 'marketplace-session-v1';

export class MarketplaceApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export interface MarketplaceUser {
  id: number;
  username: string;
  displayName: string;
}

export interface MarketplaceSession {
  token: string;
  user: MarketplaceUser;
  expiresAt: string;
}

export interface MarketplaceCategory {
  id: number;
  name: string;
  slug: string;
}

export interface MarketplaceItem {
  id: number;
  title: string;
  description: string;
  category: MarketplaceCategory;
  imageUrl: string;
  price: number | null;
  status: 'LISTED' | 'DELISTED';
  seller: MarketplaceUser;
  createdAt: string;
  updatedAt: string;
}

export interface UploadIntent {
  objectKey: string;
  publicUrl: string;
  bucket: string;
  region: string;
  objectAcl: string;
  credentials: {
    accessKeyId: string;
    accessKeySecret: string;
    securityToken: string;
    expiration: string;
  };
}

export interface MarketplaceConversation {
  id: number;
  item: MarketplaceItem;
  buyer: MarketplaceUser;
  seller: MarketplaceUser;
  lastMessage: MarketplaceMessage | null;
  updatedAt: string;
}

export interface MarketplaceMessage {
  id: number;
  conversationId: number;
  sender: MarketplaceUser;
  body: string;
  createdAt: string;
}

export interface CreateItemPayload {
  title: string;
  description: string;
  categoryId: number;
  imageUrl: string;
  price: number | null;
}

export function loadStoredSession(): MarketplaceSession | null {
  try {
    const raw = localStorage.getItem(MARKETPLACE_SESSION_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as MarketplaceSession;
    if (!session.token || !session.user) return null;
    return session;
  } catch {
    localStorage.removeItem(MARKETPLACE_SESSION_KEY);
    return null;
  }
}

export function saveStoredSession(session: MarketplaceSession) {
  localStorage.setItem(MARKETPLACE_SESSION_KEY, JSON.stringify(session));
}

export function clearStoredSession() {
  localStorage.removeItem(MARKETPLACE_SESSION_KEY);
}

export async function register(username: string, password: string, displayName: string) {
  return marketplaceRequest<MarketplaceSession>('/auth/register', {
    method: 'POST',
    body: { username, password, displayName },
  });
}

export async function login(username: string, password: string) {
  return marketplaceRequest<MarketplaceSession>('/auth/login', {
    method: 'POST',
    body: { username, password },
  });
}

export async function logout(token: string) {
  await marketplaceRequest<void>('/auth/logout', { method: 'POST', token });
}

export async function fetchMe(token: string) {
  return marketplaceRequest<MarketplaceUser>('/auth/me', { token });
}

export async function fetchCategories() {
  const payload = await marketplaceRequest<{ categories: MarketplaceCategory[] }>('/categories');
  return payload.categories;
}

export async function fetchItems(filters: { categoryId?: number | null; q?: string; mine?: boolean }, token?: string) {
  const params = new URLSearchParams();
  if (filters.categoryId) params.set('categoryId', String(filters.categoryId));
  if (filters.q?.trim()) params.set('q', filters.q.trim());
  if (filters.mine) params.set('mine', 'true');
  const suffix = params.size ? `?${params}` : '';
  const payload = await marketplaceRequest<{ items: MarketplaceItem[] }>(`/items${suffix}`, { token });
  return payload.items;
}

export async function fetchItem(id: number, token?: string) {
  return marketplaceRequest<MarketplaceItem>(`/items/${id}`, { token });
}

export async function createItem(token: string, payload: CreateItemPayload) {
  return marketplaceRequest<MarketplaceItem>('/items', { method: 'POST', body: payload, token });
}

export async function delistItem(token: string, id: number) {
  return marketplaceRequest<MarketplaceItem>(`/items/${id}/delist`, { method: 'POST', token });
}

export async function createUploadIntent(token: string, file: File) {
  return marketplaceRequest<UploadIntent>('/uploads/intents', {
    method: 'POST',
    body: { fileName: file.name, contentType: file.type },
    token,
  });
}

export async function uploadFileToOss(file: File, intent: UploadIntent) {
  const imported = (await import('ali-oss')) as { default?: new (options: Record<string, unknown>) => { put: (key: string, value: File, options?: Record<string, unknown>) => Promise<unknown> } };
  const OSS = imported.default ?? (imported as unknown as new (options: Record<string, unknown>) => { put: (key: string, value: File, options?: Record<string, unknown>) => Promise<unknown> });
  const client = new OSS({
    region: intent.region,
    bucket: intent.bucket,
    accessKeyId: intent.credentials.accessKeyId,
    accessKeySecret: intent.credentials.accessKeySecret,
    stsToken: intent.credentials.securityToken,
    secure: true,
  });
  await client.put(intent.objectKey, file, {
    headers: {
      'Content-Type': file.type,
      'x-oss-object-acl': intent.objectAcl,
    },
  });
  return intent.publicUrl;
}

export async function createConversation(token: string, itemId: number) {
  return marketplaceRequest<MarketplaceConversation>(`/items/${itemId}/conversations`, { method: 'POST', token });
}

export async function fetchConversations(token: string) {
  const payload = await marketplaceRequest<{ conversations: MarketplaceConversation[] }>('/conversations', { token });
  return payload.conversations;
}

export async function fetchMessages(token: string, conversationId: number, afterId?: number) {
  const params = new URLSearchParams({ limit: '50' });
  if (afterId) params.set('afterId', String(afterId));
  const payload = await marketplaceRequest<{ messages: MarketplaceMessage[] }>(
    `/conversations/${conversationId}/messages?${params}`,
    { token },
  );
  return payload.messages;
}

export async function sendMessage(token: string, conversationId: number, body: string) {
  return marketplaceRequest<MarketplaceMessage>(`/conversations/${conversationId}/messages`, {
    method: 'POST',
    body: { body },
    token,
  });
}

async function marketplaceRequest<T>(path: string, options: { method?: string; body?: unknown; token?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  const response = await fetch(`/api/marketplace${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as { error?: string };
    throw new MarketplaceApiError(response.status, payload.error || `Request failed: ${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
