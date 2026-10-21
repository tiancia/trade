export type ApiTimestamp = string | number;

export function timestampMillis(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  // Spring emits epoch seconds; saved snapshots may contain milliseconds or ISO text.
  const milliseconds = typeof value === 'number'
    ? (Math.abs(value) < 100_000_000_000 ? value * 1000 : value)
    : typeof value === 'string' ? Date.parse(value) : NaN;
  return Number.isFinite(milliseconds) && Number.isFinite(new Date(milliseconds).getTime()) ? milliseconds : null;
}

export function formatDate(value: unknown) {
  const milliseconds = timestampMillis(value);
  if (milliseconds === null) return '—';
  const date = new Date(milliseconds);
  const options: Intl.DateTimeFormatOptions = date.toDateString() === new Date().toDateString()
    ? { hour: '2-digit', minute: '2-digit' }
    : { month: 'numeric', day: 'numeric' };
  return new Intl.DateTimeFormat('zh-CN', options).format(date);
}

export function formatTime(value: unknown) {
  const milliseconds = timestampMillis(value);
  return milliseconds === null ? '—' : new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(milliseconds);
}
