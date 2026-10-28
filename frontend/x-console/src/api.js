const BASE_URL = '/api/x/posts';
const TIMEOUT_MS = 15_000;

function apiError(message, code) {
  const error = new Error(message);
  error.name = 'ApiError';
  error.code = code;
  return error;
}

function postPath(id) {
  if (typeof id !== 'string' || !id.trim()) {
    throw apiError('缺少内容 ID，请重新选择内容。', 'ID_REQUIRED');
  }
  return `${BASE_URL}/${encodeURIComponent(id)}`;
}

function validRevision(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && typeof value.status === 'string' && Boolean(value.status.trim())
    && Number.isInteger(value.contentVersion) && value.contentVersion > 0
    && Number.isInteger(value.revision) && value.revision >= 0;
}

function validPost(value) {
  return validRevision(value) && typeof value.id === 'string' && Boolean(value.id.trim());
}

async function request(path, { signal, method = 'GET' }, shape) {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort(signal?.reason);
  if (signal?.aborted) abort();
  else signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, TIMEOUT_MS);

  try {
    const response = await fetch(path, {
      method,
      headers: method === 'GET' ? { Accept: 'application/json' } : { Accept: 'application/json', 'X-Trade-CSRF': '1' },
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    });

    if (response.status === 401 || response.status === 403) {
      throw apiError('访问被拒绝，请检查网关登录状态或访问权限。', 'UNAUTHORIZED');
    }
    if (response.status === 404) {
      throw apiError('未找到 X 接口或内容，请检查后端地址或刷新列表。', 'NOT_FOUND');
    }
    if (!response.ok) {
      let detail = null;
      try { detail = await response.json(); }
      catch (error) {
        if (controller.signal.aborted) throw error;
        // Keep the HTTP error for non-JSON failures.
      }
      if (response.status === 400 && detail?.error === 'X post not found') {
        throw apiError('该内容不存在或已被移除，请刷新列表。', 'POST_NOT_FOUND');
      }
      throw apiError(`请求失败（HTTP ${response.status}），请检查后端服务。`, 'HTTP_ERROR');
    }

    const contentType = response.headers.get('content-type') || '';
    if (!/\bapplication\/(?:[\w.-]+\+)?json\b/i.test(contentType)) {
      throw apiError('后端没有返回 JSON 数据，请检查 API 代理和后端地址。', 'INVALID_RESPONSE');
    }
    let data;
    try { data = await response.json(); }
    catch (error) {
      if (controller.signal.aborted) throw error;
      throw apiError('后端返回的 JSON 无法解析，请稍后重试。', 'INVALID_RESPONSE');
    }
    const valid = shape === 'task'
      ? data !== null && typeof data === 'object' && data.id === 'x' && typeof data.running === 'boolean' && typeof data.autoStart === 'boolean'
      : shape === 'post'
      ? validPost(data)
      : Array.isArray(data) && data.every(shape === 'history' ? validRevision : validPost);
    if (!valid) {
      throw apiError('后端返回的数据格式不符合 X 接口，请检查后端版本。', 'INVALID_RESPONSE');
    }
    return data;
  } catch (error) {
    if (error?.name === 'ApiError') throw error;
    if (timedOut) throw apiError('请求超时，请检查后端服务后重试。', 'TIMEOUT');
    if (signal?.aborted || controller.signal.aborted) {
      const cancelled = new Error('请求已取消。');
      cancelled.name = 'AbortError';
      throw cancelled;
    }
    throw apiError('无法连接后端，请确认服务正在运行且 API 代理配置正确。', 'NETWORK_ERROR');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

export async function fetchRecent({ limit = 30, signal } = {}) {
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw apiError('查询条数必须为 1 到 100 的整数。', 'LIMIT_INVALID');
  }
  return request(`${BASE_URL}?limit=${limit}`, { signal }, 'recent');
}

export async function fetchPost({ id, signal } = {}) {
  return request(postPath(id), { signal }, 'post');
}

export async function fetchHistory({ id, signal } = {}) {
  return request(`${postPath(id)}/history`, { signal }, 'history');
}

export async function fetchXTask({ signal } = {}) {
  return request('/api/automation/tasks/x', { signal }, 'task');
}

export async function setXTaskRunning(running, { signal } = {}) {
  if (typeof running !== 'boolean') throw apiError('任务状态无效。', 'STATE_INVALID');
  return request(`/api/automation/tasks/x/${running ? 'start' : 'stop'}`, { signal, method: 'POST' }, 'task');
}
