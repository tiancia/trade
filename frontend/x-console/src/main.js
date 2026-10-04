import './styles.css';
import { fetchRecent, fetchPost, fetchHistory } from './api.js';
import { STATUS_META, formatTime, reviewSummary, publishSummary, filterPosts, getStats, publishedUrl } from './model.js';

const $ = (id) => document.getElementById(id);
const state = {
  token: '', posts: [], selected: null, history: [], filter: 'ALL', query: '',
  limit: 30, loaded: false, busy: false, connected: false, updatedAt: null,
  listError: '', detailError: '', historyError: '', detailBusy: false, detailTab: 'details',
};
let listController;
let detailController;
const tones = { info: 'blue', warning: 'amber', success: 'green', danger: 'red' };
const escape = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[char]);
const icon = (name, className = '') => `<svg aria-hidden="true" class="${className}"><use href="#i-${name}"/></svg>`;
const badge = (summary) => `<span class="badge ${tones[summary.tone] || 'neutral'}"><span class="badge-dot"></span>${escape(summary.label)}</span>`;
const meta = (status) => STATUS_META[status] || { label: status || '未知状态', tone: 'neutral' };
const field = (label, value, mono = false) => `<dt>${escape(label)}</dt><dd${mono ? ' class="mono"' : ''}>${escape(value || '—')}</dd>`;

function visiblePosts() {
  let posts = state.posts;
  if (state.filter === 'ATTENTION') posts = posts.filter((post) => ['GENERATION_FAILED', 'FAILED', 'UNKNOWN'].includes(post.status));
  if (state.filter === 'PUBLISH_RESULTS') posts = posts.filter((post) => ['PUBLISHED', 'FAILED', 'UNKNOWN'].includes(post.status));
  return filterPosts(posts, {
    query: state.query,
    status: ['ATTENTION', 'PUBLISH_RESULTS'].includes(state.filter) ? 'ALL' : state.filter,
  });
}

function renderHeader() {
  const connection = $('connection-status');
  const label = state.busy ? '正在读取' : state.listError ? '连接异常' : state.connected ? '已连接' : '未连接';
  connection.className = `connection-pill ${state.busy ? 'loading' : state.listError ? 'error' : state.connected ? 'connected' : ''}`;
  connection.innerHTML = `<span class="dot"></span>${label}`;
  $('connect-button').querySelector('span').textContent = state.token ? '连接设置' : '连接后端';
  $('refresh-button').disabled = !state.token || state.busy;
  $('refresh-icon').classList.toggle('spinning', state.busy);
  $('disconnect-button').hidden = !state.token;
  const stats = getStats(state.posts);
  for (const [id, key] of [['stat-total', 'total'], ['stat-pending', 'pendingReview'], ['stat-published', 'published'], ['stat-attention', 'attention']]) {
    $(id).textContent = state.loaded ? stats[key] : '—';
  }
  $('nav-pending').textContent = state.loaded ? stats.pendingReview : '—';
  $('nav-attention').textContent = state.loaded ? stats.attention : '—';
  document.querySelectorAll('[data-filter]').forEach((element) => {
    const active = element.dataset.filter === state.filter;
    element.classList.toggle('active', active);
    if (element.classList.contains('nav-item')) {
      if (active) element.setAttribute('aria-current', 'page');
      else element.removeAttribute('aria-current');
    } else element.setAttribute('aria-pressed', String(active));
  });
  $('status-select').value = state.filter;
  const sectionNames = { ALL: '内容总览', PENDING_REVIEW: '待审核', PUBLISH_RESULTS: '发布结果', ATTENTION: '需要关注' };
  document.querySelector('.breadcrumb strong').textContent = sectionNames[state.filter] || meta(state.filter).label;
}

function renderList() {
  const focusedRow = document.activeElement?.closest('[data-post-id]')?.dataset.postId;
  const posts = visiblePosts();
  $('record-count').textContent = state.loaded ? state.posts.length : '0';
  const notice = $('list-notice');
  notice.hidden = !state.listError;
  notice.textContent = state.listError + (state.loaded ? ' 仍显示上次读取的记录，请刷新后再确认。' : '');
  $('table-region').setAttribute('aria-busy', String(state.busy));
  if (!state.loaded) {
    $('table-region').innerHTML = `<div class="empty-state"><span class="empty-icon">${icon(state.busy ? 'refresh' : state.listError ? 'alert' : 'connect', state.busy ? 'spinning' : '')}</span><h3>${state.busy ? '正在读取最近内容' : state.listError ? '暂时无法读取内容' : '让每条内容的进展清晰可见'}</h3><p>${state.busy ? '正在获取 X 模块的最新记录，请稍候。' : state.listError ? '检查连接设置后，重新读取内容记录。' : '连接 X 模块，查看生成内容、审核反馈和发布结果。'}</p>${state.busy ? '' : `<button class="button primary" data-action="connect">${icon('connect')}${state.listError ? '检查连接' : '连接后端'}</button>`}</div>`;
  } else if (!posts.length) {
    $('table-region').innerHTML = `<div class="empty-state"><span class="empty-icon">${icon('file')}</span><h3>${state.posts.length ? '没有匹配的内容' : '还没有内容记录'}</h3><p>${state.posts.length ? '试试其他关键词，或调整内容状态筛选。' : '后端当前没有生成记录，产生内容后可在这里查看。'}</p>${state.posts.length ? '<button class="button secondary" data-action="reset-filter">清除筛选</button>' : ''}</div>`;
  } else {
    $('table-region').innerHTML = `<table class="content-table"><caption class="visually-hidden">最近生成内容及审核、发布结果</caption><thead><tr><th scope="col">内容</th><th scope="col">审核结果</th><th scope="col">发布结果</th><th scope="col">生成时间</th><th scope="col"><span class="visually-hidden">查看详情</span></th></tr></thead><tbody>${posts.map((post) => {
      const selected = state.selected?.id === post.id;
      const review = reviewSummary(post);
      const publish = publishSummary(post);
      return `<tr data-post-id="${escape(post.id)}" class="${selected ? 'selected' : ''}"><td class="content-cell"><p class="content-snippet">${escape(post.body || (post.status === 'GENERATING' ? '内容正在生成…' : '暂无生成正文'))}</p><div class="row-meta"><span class="version-tag">V${escape(post.contentVersion)}</span><span>${escape(meta(post.status).label)}</span><span class="mono" title="${escape(post.id)}">${escape(String(post.id).slice(0, 8))}</span></div></td><td>${badge(review)}<div class="status-reason">${escape(post.reviewer ? `审核人 ${post.reviewer}` : post.status === 'PENDING_REVIEW' ? '等待审核反馈' : '—')}</div></td><td>${badge(publish)}<div class="status-reason">${post.status === 'UNKNOWN' ? '需核对平台结果' : post.postId ? `ID ${escape(post.postId)}` : post.status === 'FAILED' ? '查看失败原因' : '—'}</div></td><td class="date-cell"><time datetime="${escape(post.createdAt)}">${escape(formatTime(post.createdAt))}</time></td><td><button class="button icon-button row-button" aria-label="查看内容 ${escape(String(post.id).slice(0, 8))}" aria-pressed="${selected}">${icon('chevron')}</button></td></tr>`;
    }).join('')}</tbody></table>`;
  }
  $('list-summary').textContent = state.loaded ? `显示 ${posts.length} / ${state.posts.length} 条 · 最近 ${state.limit} 条范围内` : '连接后查看最近生成的内容';
  $('updated-time').textContent = state.updatedAt ? `更新于 ${formatTime(state.updatedAt)} · 北京时间` : '时间均为北京时间';
  if (focusedRow) {
    [...$('table-region').querySelectorAll('[data-post-id]')].find((row) => row.dataset.postId === focusedRow)?.querySelector('button')?.focus({ preventScroll: true });
  }
}

function resultSection(post) {
  const review = reviewSummary(post);
  const publish = publishSummary(post);
  const url = publishedUrl(post);
  const reviewCopy = post.reviewReason || (post.status === 'PENDING_REVIEW' ? '等待 Telegram 返回审核意见。' : post.reviewer ? '审核已完成，未填写审核原因。' : '尚无审核意见。');
  const publishCopy = {
    UNKNOWN: '请求可能已到达 X，发布结果尚未确认，请核对平台记录。',
    FAILED: '本次发布未成功，具体原因见下方反馈。',
    PUBLISHED: 'X 已返回帖子 ID，可打开平台查看已发布内容。',
    PUBLISHING: '发布请求正在处理，稍后刷新查看结果。',
    APPROVED: '当前版本已通过审核，等待后端发布流程。',
  }[post.status] || '当前内容尚未发布。';
  return `<section class="detail-section"><div class="section-heading"><h3>${icon('review')}审核结果</h3></div><div class="result-card ${tones[review.tone] || 'neutral'}"><div class="result-title">${badge(review)}</div><p class="result-copy">${escape(reviewCopy)}</p></div><dl class="info-grid">${field('审核人', post.reviewer)}${field('审核时间', formatTime(post.reviewedAt))}${field('审核有效期', formatTime(post.expiresAt))}</dl></section>
    <section class="detail-section"><div class="section-heading"><h3>${icon('send')}发布结果</h3></div><div class="result-card ${tones[publish.tone] || 'neutral'}"><div class="result-title">${badge(publish)}</div><p class="result-copy">${escape(publishCopy)}</p>${url ? `<a class="text-link" href="${url}" target="_blank" rel="noopener noreferrer">在 X 查看帖子 ${icon('arrow')}</a>` : ''}</div>${post.lastError && ['FAILED', 'UNKNOWN'].includes(post.status) ? `<p class="history-error">${escape(post.lastError)}</p>` : ''}<dl class="info-grid">${field('帖子 ID', post.postId, true)}${field('发布开始', formatTime(post.publishStartedAt))}${field('请求 ID', post.attemptId, true)}</dl></section>`;
}

function renderHistory() {
  if (state.detailBusy) return `<div class="history-loading">${icon('refresh', 'spinning')}正在读取历史记录…</div>`;
  if (state.historyError) return `<div class="history-error">${escape(state.historyError)}<button class="button secondary small" data-action="reload-detail">重新读取</button></div>`;
  if (!state.history.length) return '<div class="detail-empty"><p>暂无状态历史记录。</p></div>';
  const history = [...state.history].sort((a, b) => b.revision - a.revision);
  return `<p class="history-caption">最近 ${history.length} 次状态快照 · 新记录在前</p><ol class="history-list">${history.map((item) => `<li class="history-item"><span class="history-marker ${tones[meta(item.status).tone] || 'neutral'}"></span><div class="history-content"><div class="history-heading">${badge(meta(item.status))}<span class="mono">V${escape(item.contentVersion)} · R${escape(item.revision)}</span></div><time class="history-time">${escape(formatTime(item.updatedAt))}</time>${item.reviewer ? `<p class="history-note">审核人：${escape(item.reviewer)}</p>` : ''}${item.reviewReason ? `<p class="history-note">审核原因：${escape(item.reviewReason)}</p>` : ''}${item.postId ? `<p class="history-note mono">帖子 ID：${escape(item.postId)}</p>` : ''}${item.lastError ? `<p class="history-error">${escape(item.lastError)}</p>` : ''}${item.body ? `<details><summary>查看此版本内容</summary><p class="history-body">${escape(item.body)}</p></details>` : ''}</div></li>`).join('')}</ol>`;
}

function renderDetail() {
  const post = state.selected;
  const panel = $('detail-panel');
  const active = panel.contains(document.activeElement) ? document.activeElement : null;
  const focusKey = active?.dataset.detailTab ? ['detail-tab', active.dataset.detailTab] : active?.dataset.action ? ['action', active.dataset.action] : null;
  if (!post) {
    panel.innerHTML = `<div class="detail-header"><div><h2>内容详情</h2><p>从生成到发布的完整记录</p></div>${icon('file')}</div><div class="detail-empty"><span class="empty-icon">${icon('file')}</span><h3>关注每一条内容</h3><p>选择左侧记录，查看完整正文、审核意见与发布进展。</p><div class="detail-empty-flow"><span>${icon('file')}生成</span><span>→</span><span>${icon('review')}审核</span><span>→</span><span>${icon('send')}发布</span></div></div>`;
    return;
  }
  panel.innerHTML = `<div class="detail-header"><div><h2>内容详情 ${badge(meta(post.status))}</h2><p class="mono">${escape(String(post.id).slice(0, 8))} · V${escape(post.contentVersion)} · R${escape(post.revision)}</p></div><div class="detail-header-actions"><button class="button icon-button" data-action="reload-detail" aria-label="刷新内容详情" ${state.detailBusy ? 'disabled' : ''}>${icon('refresh', state.detailBusy ? 'spinning' : '')}</button><button class="button icon-button" data-action="close-detail" aria-label="关闭内容详情">${icon('close')}</button></div></div><div class="detail-tabs" aria-label="详情视图"><button data-detail-tab="details" class="${state.detailTab === 'details' ? 'active' : ''}" aria-pressed="${state.detailTab === 'details'}">内容与结果</button><button data-detail-tab="history" class="${state.detailTab === 'history' ? 'active' : ''}" aria-pressed="${state.detailTab === 'history'}">${icon('history')}状态历史</button></div><div class="detail-body">${state.detailError ? `<div class="notice error" role="alert">${escape(state.detailError)} 正在展示列表快照。</div>` : ''}${state.detailTab === 'history' ? renderHistory() : `<section class="detail-section"><div class="post-preview"><div class="post-account"><span class="account-avatar">𝕏</span><div><strong>X 内容快照</strong><span class="mono">账号 ${escape(post.targetUserId || '—')}</span></div></div><p class="post-body">${escape(post.body || '暂无生成正文')}</p><div class="post-meta"><time>${escape(formatTime(post.createdAt))}</time><span>${Array.from(post.body || '').length} 字符</span></div></div>${post.status === 'GENERATION_FAILED' && post.lastError ? `<div class="history-error" role="alert"><strong>生成失败</strong><p>${escape(post.lastError)}</p></div>` : ''}${post.reviewNote ? `<div class="review-note"><strong>生成说明</strong><p>${escape(post.reviewNote)}</p></div>` : ''}</section>${resultSection(post)}<section class="detail-section"><div class="section-heading"><h3>记录信息</h3></div><dl class="info-grid">${field('内容 ID', post.id, true)}${field('更新时间', formatTime(post.updatedAt))}${field('内容方向', post.contentPolicy?.direction)}${field('语言', post.contentPolicy?.language)}${field('风格', post.contentPolicy?.tone)}</dl></section>`}</div>`;
  if (focusKey) panel.querySelector(`[data-${focusKey[0]}="${focusKey[1]}"]`)?.focus({ preventScroll: true });
}

function render() { renderHeader(); renderList(); renderDetail(); }

function selectFilter(filter) {
  state.filter = filter;
  renderHeader();
  renderList();
}

function clearDetail() {
  detailController?.abort();
  detailController = null;
  state.selected = null;
  state.history = [];
  state.detailBusy = false;
  state.detailError = '';
  state.historyError = '';
  renderDetail();
}

async function loadDetail(post, { scroll = false } = {}) {
  detailController?.abort();
  const controller = new AbortController();
  detailController = controller;
  state.selected = post;
  state.history = [];
  state.detailError = '';
  state.historyError = '';
  state.detailBusy = true;
  renderList();
  renderDetail();
  if (scroll && window.matchMedia('(max-width: 1100px)').matches) $('detail-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  const options = { token: state.token, id: post.id, signal: controller.signal };
  const [detail, history] = await Promise.allSettled([fetchPost(options), fetchHistory(options)]);
  if (controller.signal.aborted || detailController !== controller) return;
  state.detailBusy = false;
  if (detail.status === 'fulfilled') state.selected = detail.value;
  else state.detailError = detail.reason.message;
  if (history.status === 'fulfilled') state.history = history.value;
  else state.historyError = history.reason.message;
  renderDetail();
}

async function refresh() {
  if (!state.token) return;
  listController?.abort();
  if (state.detailBusy && state.selected) {
    state.detailError = '详情读取已取消，请刷新详情重新读取。';
    state.historyError = '历史读取已取消，请重新读取。';
  }
  detailController?.abort();
  const controller = new AbortController();
  listController = controller;
  state.busy = true;
  state.detailBusy = false;
  state.listError = '';
  render();
  try {
    const posts = await fetchRecent({ token: state.token, limit: state.limit, signal: controller.signal });
    if (listController !== controller || controller.signal.aborted) return;
    state.posts = posts;
    state.connected = true;
    state.loaded = true;
    state.updatedAt = new Date().toISOString();
    state.busy = false;
    const selected = posts.find((post) => post.id === state.selected?.id);
    if (!selected) clearDetail();
    render();
    if (selected) void loadDetail(selected);
  } catch (error) {
    if (controller.signal.aborted || listController !== controller) return;
    state.busy = false;
    state.connected = false;
    state.listError = error.message;
    render();
  }
}

function disconnect() {
  listController?.abort();
  listController = null;
  clearDetail();
  Object.assign(state, { token: '', posts: [], loaded: false, busy: false, connected: false, updatedAt: null, listError: '', filter: 'ALL', query: '' });
  $('token-input').value = '';
  $('search-input').value = '';
  render();
}

function openConnection() {
  $('form-error').hidden = true;
  $('token-input').value = '';
  $('disconnect-button').hidden = !state.token;
  $('connection-dialog').showModal();
  $('token-input').focus();
}

$('connect-button').addEventListener('click', openConnection);
$('dialog-close').addEventListener('click', () => $('connection-dialog').close());
$('connection-dialog').addEventListener('close', () => { $('token-input').value = ''; });
$('connection-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const token = $('token-input').value.trim();
  if (!token) {
    $('form-error').textContent = '请输入管理员令牌。';
    $('form-error').hidden = false;
    return;
  }
  disconnect();
  state.token = token;
  $('connection-dialog').close();
  void refresh();
});
$('disconnect-button').addEventListener('click', () => { disconnect(); $('connection-dialog').close(); });
$('refresh-button').addEventListener('click', () => void refresh());
$('limit-select').addEventListener('change', (event) => { state.limit = Number(event.target.value); void refresh(); });
$('status-select').addEventListener('change', (event) => selectFilter(event.target.value));
$('search-input').addEventListener('input', (event) => { state.query = event.target.value; renderList(); });
document.addEventListener('click', (event) => {
  const filter = event.target.closest('[data-filter]');
  if (filter) { selectFilter(filter.dataset.filter); return; }
  const action = event.target.closest('[data-action]')?.dataset.action;
  if (action === 'connect') openConnection();
  if (action === 'reset-filter') { state.query = ''; $('search-input').value = ''; selectFilter('ALL'); }
  if (action === 'close-detail') {
    const id = state.selected?.id;
    clearDetail();
    renderList();
    [...$('table-region').querySelectorAll('[data-post-id]')].find((row) => row.dataset.postId === id)?.querySelector('button')?.focus({ preventScroll: true });
  }
  if (action === 'reload-detail' && state.selected) void loadDetail(state.selected);
  const tab = event.target.closest('[data-detail-tab]');
  if (tab) { state.detailTab = tab.dataset.detailTab; renderDetail(); }
  const row = event.target.closest('[data-post-id]');
  if (row) {
    const post = state.posts.find((item) => item.id === row.dataset.postId);
    if (post) { state.detailTab = 'details'; void loadDetail(post, { scroll: true }); }
  }
});
window.addEventListener('pagehide', disconnect);
render();
