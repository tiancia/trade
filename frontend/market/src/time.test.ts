import { describe, expect, it, vi } from 'vitest';
import { formatDate, formatTime, timestampMillis } from './time';

describe('API timestamps', () => {
  it('normalizes seconds, fractional seconds, milliseconds, ISO and epoch zero', () => {
    expect(timestampMillis(1792540800)).toBe(1792540800000);
    expect(timestampMillis(1792540800.125)).toBe(1792540800125);
    expect(timestampMillis(1792540800000)).toBe(1792540800000);
    expect(timestampMillis('2026-10-21T00:00:00Z')).toBe(1792540800000);
    expect(timestampMillis(0)).toBe(0);
    for (const invalid of [null, undefined, '', 'bad-date', NaN, Infinity, 1e20, {}]) {
      expect(timestampMillis(invalid)).toBeNull();
      expect(formatDate(invalid)).toBe('—');
      expect(formatTime(invalid)).toBe('—');
    }
  });

  it('keeps date and time formatting identical across all supported API representations', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-10-22T00:00:00Z'));
      const iso = '2026-10-21T00:00:00Z';
      for (const timestamp of [1792540800, 1792540800000]) {
        expect(formatDate(timestamp)).toBe(formatDate(iso));
        expect(formatTime(timestamp)).toBe(formatTime(iso));
      }
      vi.setSystemTime(new Date(iso));
      expect(formatDate(1792540800)).toBe(formatTime(iso));
    } finally {
      vi.useRealTimers();
    }
  });
});
