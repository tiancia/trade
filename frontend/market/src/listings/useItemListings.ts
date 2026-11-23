import { useEffect, useState } from 'react';
import { delistItem, fetchItems } from '../api';
import type { MarketplaceItem } from '../api';
import { messageOf } from '../errors';

export function useItemListings(token: string | undefined, ready: boolean, onError: (message: string | null) => void) {
  const [items, setItems] = useState<MarketplaceItem[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | null>(null);
  const [queryInput, setQueryInput] = useState('');
  const query = useDebouncedValue(queryInput, 320);
  const [mine, setMine] = useState(false);
  const [selectedItemId, setSelectedItemId] = useState<number | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [itemsLoading, setItemsLoading] = useState(true);
  const [delisting, setDelisting] = useState(false);
  const selectedItem = items.find((item) => item.id === selectedItemId) ?? items[0] ?? null;

  useEffect(() => {
    if (!ready) return;
    let active = true;
    async function refresh() {
      setItemsLoading(true);
      try {
        const nextItems = await fetchItems({ categoryId: selectedCategoryId, q: query, mine }, token);
        if (!active) return;
        setItems(nextItems);
        setSelectedItemId((current) => nextItems.some((item) => item.id === current) ? current : nextItems[0]?.id ?? null);
      } catch (cause) {
        if (!active) return;
        onError(messageOf(cause));
        if (mine && !token) setMine(false);
      } finally {
        if (active) setItemsLoading(false);
      }
    }
    void refresh();
    return () => { active = false; };
  }, [ready, mine, query, selectedCategoryId, token, onError]);

  async function delist(item: MarketplaceItem) {
    if (!token) return;
    setDelisting(true);
    onError(null);
    try {
      const updated = await delistItem(token, item.id);
      setItems((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
    } catch (cause) {
      onError(messageOf(cause));
    } finally {
      setDelisting(false);
    }
  }

  function selectItem(item: MarketplaceItem) {
    setSelectedItemId(item.id);
    setDetailOpen(true);
  }

  function showPublished(created: MarketplaceItem) {
    setMine(true);
    setItems((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    setSelectedItemId(created.id);
    setDetailOpen(true);
  }

  return {
    items, itemsLoading, selectedItem, selectItem, detailOpen, setDetailOpen,
    mine, setMine, queryInput, setQueryInput, selectedCategoryId, setSelectedCategoryId,
    delisting, delist, showPublished,
  };
}

function useDebouncedValue<T>(value: T, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [delay, value]);
  return debounced;
}
