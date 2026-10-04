export const STATUS_META = Object.freeze({
  GENERATING: { label: '生成中', tone: 'info', stage: 'generation' },
  GENERATION_FAILED: { label: '生成失败', tone: 'danger', stage: 'generation' },
  PENDING_REVIEW: { label: '待审核', tone: 'warning', stage: 'review' },
  APPROVED: { label: '审核通过', tone: 'success', stage: 'review' },
  REJECTED: { label: '审核拒绝', tone: 'danger', stage: 'review' },
  EXPIRED: { label: '已过期', tone: 'neutral', stage: 'review' },
  PUBLISHING: { label: '发布中', tone: 'info', stage: 'publish' },
  PUBLISHED: { label: '已发布', tone: 'success', stage: 'publish' },
  FAILED: { label: '发布失败', tone: 'danger', stage: 'publish' },
  UNKNOWN: { label: '发布结果待核对', tone: 'warning', stage: 'publish' },
});

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});

export function formatTime(value) {
  if (value === null || value === undefined || value === '') return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date);
}

export function reviewSummary(post = {}) {
  if (post.reviewer && post.reviewedAt) {
    return post.status === 'REJECTED'
      ? { label: '已拒绝', tone: 'danger' }
      : { label: '已通过', tone: 'success' };
  }
  if (post.status === 'GENERATING' || post.status === 'GENERATION_FAILED') {
    return { label: '尚未送审', tone: 'neutral' };
  }
  if (post.status === 'EXPIRED') return { label: '未审核 · 已过期', tone: 'neutral' };
  if (post.status === 'PENDING_REVIEW') return { label: '等待审核', tone: 'warning' };
  return { label: '暂无审核记录', tone: 'neutral' };
}

export function publishSummary(post = {}) {
  switch (post.status) {
    case 'PUBLISHED': return { label: '发布成功', tone: 'success' };
    case 'PUBLISHING': return { label: '正在发布', tone: 'info' };
    case 'FAILED': return { label: '发布失败', tone: 'danger' };
    case 'UNKNOWN': return { label: '结果待核对', tone: 'warning' };
    case 'APPROVED': return { label: '等待发布', tone: 'neutral' };
    case 'REJECTED': return { label: '未发布 · 审核拒绝', tone: 'neutral' };
    case 'EXPIRED': return { label: '未发布 · 已过期', tone: 'neutral' };
    default: return { label: '尚未发布', tone: 'neutral' };
  }
}

function createdTime(post) {
  const value = post.createdAt ? Date.parse(post.createdAt) : NaN;
  return Number.isFinite(value) ? value : -Infinity;
}

export function filterPosts(posts, { query = '', status = 'ALL' } = {}) {
  const search = String(query ?? '').trim().toLocaleLowerCase('zh-CN');
  return posts.filter(post => {
    if (status !== 'ALL' && post.status !== status) return false;
    return !search || ['body', 'id', 'reviewer', 'reviewReason', 'postId']
      .some(key => String(post[key] ?? '').toLocaleLowerCase('zh-CN').includes(search));
  }).sort((a, b) => {
    const first = createdTime(a);
    const second = createdTime(b);
    if (first !== second) return first > second ? -1 : 1;
    const firstId = String(a.id ?? '');
    const secondId = String(b.id ?? '');
    return firstId < secondId ? -1 : firstId > secondId ? 1 : 0;
  });
}

export function getStats(posts) {
  return {
    total: posts.length,
    pendingReview: posts.filter(post => post.status === 'PENDING_REVIEW').length,
    published: posts.filter(post => post.status === 'PUBLISHED').length,
    attention: posts.filter(post => ['GENERATION_FAILED', 'FAILED', 'UNKNOWN'].includes(post.status)).length,
  };
}

export function publishedUrl(post = {}) {
  if (post.status !== 'PUBLISHED' || typeof post.postId !== 'string' || !/^[1-9][0-9]{0,18}$/.test(post.postId)) {
    return null;
  }
  return `https://x.com/i/status/${post.postId}`;
}
