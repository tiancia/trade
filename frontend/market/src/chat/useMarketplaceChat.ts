import { useCallback, useEffect, useRef, useState } from 'react';
import { createConversation, fetchConversations, fetchMessages, sendMessage } from '../api';
import type { MarketplaceConversation, MarketplaceMessage } from '../api';
import { messageOf } from '../errors';

export function useMarketplaceChat(
  token: string | undefined,
  isCurrentSession: (token: string) => boolean,
  onError: (message: string | null) => void,
) {
  const [conversations, setConversations] = useState<MarketplaceConversation[]>([]);
  const [activeConversation, setActiveConversation] = useState<MarketplaceConversation | null>(null);
  const [messages, setMessages] = useState<MarketplaceMessage[]>([]);
  const [messageDrafts, setMessageDrafts] = useState<Record<number, string>>({});
  const [chatLoading, setChatLoading] = useState(false);
  const [chatOpen, setChatOpen] = useState(false);
  const [contacting, setContacting] = useState(false);
  const [sending, setSending] = useState(false);
  const chatRequestRef = useRef(0);
  const conversationIdRef = useRef<number | null>(null);
  const messageCursorRef = useRef<number | undefined>(undefined);
  const activeId = activeConversation?.id;
  const messageDraft = activeConversation ? messageDrafts[activeConversation.id] ?? '' : '';

  useEffect(() => {
    chatRequestRef.current += 1;
    conversationIdRef.current = null;
    messageCursorRef.current = undefined;
    setActiveConversation(null);
    setMessages([]);
    setMessageDrafts({});
    setChatLoading(false);
    setConversations([]);
    setContacting(false);
    setSending(false);
    if (!token) setChatOpen(false);
  }, [token]);

  const refreshConversations = useCallback(async (): Promise<MarketplaceConversation[]> => {
    if (!token) return [];
    try {
      const next = await fetchConversations(token);
      if (!isCurrentSession(token)) return [];
      setConversations(next);
      return next;
    } catch (cause) {
      if (isCurrentSession(token)) onError(messageOf(cause));
      return [];
    }
  }, [token, isCurrentSession, onError]);

  useEffect(() => {
    void refreshConversations();
  }, [refreshConversations]);

  const isCurrentChat = useCallback((conversationId: number, generation: number, capturedToken: string) => {
    return chatRequestRef.current === generation
      && conversationIdRef.current === conversationId
      && isCurrentSession(capturedToken);
  }, [isCurrentSession]);

  const appendMessages = useCallback((conversationId: number, next: MarketplaceMessage[]) => {
    if (conversationIdRef.current !== conversationId) return;
    setMessages((current) => {
      if (conversationIdRef.current !== conversationId) return current;
      const seen = new Set(current.map((message) => message.id));
      return [...current, ...next.filter((message) => message.conversationId === conversationId && !seen.has(message.id))]
        .sort((left, right) => left.id - right.id);
    });
  }, []);

  useEffect(() => {
    if (!chatOpen || activeId == null || !token || chatLoading) return;
    let stopped = false;
    let polling = false;
    const conversationId = activeId;
    const capturedToken = token;
    const generation = chatRequestRef.current;
    async function poll() {
      if (polling) return;
      polling = true;
      try {
        const next = await fetchMessages(capturedToken, conversationId, messageCursorRef.current);
        if (!stopped && isCurrentChat(conversationId, generation, capturedToken)) {
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
  }, [activeId, chatOpen, token, chatLoading, isCurrentChat, appendMessages]);

  async function openConversation(conversation: MarketplaceConversation) {
    if (!token || !isCurrentSession(token)) return;
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
      if (isCurrentChat(conversation.id, generation, token)) onError(messageOf(cause));
    } finally {
      if (isCurrentChat(conversation.id, generation, token)) setChatLoading(false);
    }
  }

  async function contactSeller(itemId: number, onOpen: () => void) {
    if (!token || !isCurrentSession(token)) return;
    setContacting(true);
    onError(null);
    const generation = chatRequestRef.current;
    try {
      const conversation = await createConversation(token, itemId);
      if (chatRequestRef.current !== generation || !isCurrentSession(token)) return;
      onOpen();
      setChatOpen(true);
      await openConversation(conversation);
      await refreshConversations();
    } catch (cause) {
      if (chatRequestRef.current === generation && isCurrentSession(token)) onError(messageOf(cause));
    } finally {
      if (isCurrentSession(token)) setContacting(false);
    }
  }

  async function openInbox() {
    if (!token || !isCurrentSession(token)) return;
    setChatOpen(true);
    const nextConversations = await refreshConversations();
    if (isCurrentSession(token) && conversationIdRef.current === null && nextConversations.length) {
      await openConversation(nextConversations[0]);
    }
  }

  function changeDraft(value: string) {
    if (activeId != null) setMessageDrafts((current) => ({ ...current, [activeId]: value }));
  }

  async function sendDraft() {
    const body = messageDraft.trim();
    if (!token || !activeConversation || !body || chatLoading || sending) return;
    const conversationId = activeConversation.id;
    const generation = chatRequestRef.current;
    const sentDraft = messageDraft;
    setSending(true);
    onError(null);
    try {
      const sent = await sendMessage(token, conversationId, body);
      if (!isCurrentSession(token)) return;
      if (isCurrentChat(conversationId, generation, token)) appendMessages(conversationId, [sent]);
      setMessageDrafts((current) => current[conversationId] === sentDraft ? { ...current, [conversationId]: '' } : current);
      await refreshConversations();
    } catch (cause) {
      if (isCurrentChat(conversationId, generation, token)) onError(messageOf(cause));
    } finally {
      if (isCurrentSession(token)) setSending(false);
    }
  }

  return {
    chatOpen, setChatOpen, openInbox, contactSeller, contacting,
    conversations, activeConversation, openConversation, messages,
    messageDraft, changeDraft, sendDraft, busy: sending || chatLoading,
  };
}
