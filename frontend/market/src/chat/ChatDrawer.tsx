import { useEffect, useRef } from 'react';
import { MessageCircle, Send, X } from 'lucide-react';
import { formatTime } from '../time';
import type { useMarketplaceChat } from './useMarketplaceChat';

type ChatDrawerProps = {
  chat: ReturnType<typeof useMarketplaceChat>;
  currentUserId: number | undefined;
};

export function ChatDrawer({ chat, currentUserId }: ChatDrawerProps) {
  const {
    chatOpen, setChatOpen, conversations, activeConversation, openConversation,
    messages, messageDraft, changeDraft, sendDraft, busy,
  } = chat;
  const messagesEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!chatOpen) return;
    if (typeof messagesEndRef.current?.scrollIntoView === 'function') {
      messagesEndRef.current.scrollIntoView({ block: 'end' });
    }
  }, [chatOpen, messages]);

  return (
    <>
      <button
        className={`marketplace-chat-backdrop ${chatOpen ? 'open' : ''}`}
        type="button"
        aria-label="关闭消息"
        onClick={() => setChatOpen(false)}
      />
      <aside className={`marketplace-chat ${chatOpen ? 'open' : ''}`} aria-label="聊天抽屉" aria-hidden={!chatOpen}>
        <header>
          <div>
            <span>MESSAGES</span>
            <strong>我的消息</strong>
          </div>
          <button type="button" onClick={() => setChatOpen(false)} aria-label="关闭聊天"><X size={19} /></button>
        </header>
        <div className="marketplace-chat-body">
          <nav aria-label="会话列表">
            {conversations.map((conversation) => {
              const partner = conversation.buyer.id === currentUserId ? conversation.seller : conversation.buyer;
              return (
                <button
                  type="button"
                  key={conversation.id}
                  className={activeConversation?.id === conversation.id ? 'active' : ''}
                  onClick={() => void openConversation(conversation)}
                >
                  <img src={conversation.item.imageUrl} alt="" />
                  <span>
                    <strong>{conversation.item.title}</strong>
                    <small>{partner.displayName} · {conversation.lastMessage?.body ?? '开始聊聊吧'}</small>
                  </span>
                </button>
              );
            })}
            {!conversations.length && (
              <div className="marketplace-chat-empty">
                <MessageCircle size={24} />
                <strong>还没有会话</strong>
                <span>在商品详情中联系卖家后，会话会出现在这里。</span>
              </div>
            )}
          </nav>
          <section className="marketplace-message-pane">
            {activeConversation ? (
              <>
                <div className="marketplace-message-title">
                  <img src={activeConversation.item.imageUrl} alt="" />
                  <span>
                    <strong>{activeConversation.item.title}</strong>
                    <small>
                      与 {activeConversation.buyer.id === currentUserId
                        ? activeConversation.seller.displayName
                        : activeConversation.buyer.displayName} 沟通中
                    </small>
                  </span>
                </div>
                <div className="marketplace-messages" aria-live="polite">
                  {messages.map((message) => (
                    <div className={`marketplace-message ${message.sender.id === currentUserId ? 'mine' : ''}`} key={message.id}>
                      <span>{message.sender.displayName} · {formatTime(message.createdAt)}</span>
                      <p>{message.body}</p>
                    </div>
                  ))}
                  {!messages.length && <p className="marketplace-conversation-start">还没有消息，友好地打个招呼吧。</p>}
                  <div ref={messagesEndRef} />
                </div>
                <form className="marketplace-message-form" onSubmit={(event) => { event.preventDefault(); void sendDraft(); }}>
                  <input
                    aria-label="消息内容"
                    value={messageDraft}
                    maxLength={1000}
                    autoComplete="off"
                    onChange={(event) => changeDraft(event.target.value)}
                    placeholder="输入消息…"
                  />
                  <button type="submit" aria-label="发送消息" disabled={busy || !messageDraft.trim()}>
                    <Send size={18} />
                  </button>
                </form>
              </>
            ) : (
              <div className="marketplace-chat-placeholder">
                <span><MessageCircle size={30} /></span>
                <strong>选择一段会话</strong>
                <p>与买家或卖家的消息会显示在这里。</p>
              </div>
            )}
          </section>
        </div>
      </aside>
    </>
  );
}
