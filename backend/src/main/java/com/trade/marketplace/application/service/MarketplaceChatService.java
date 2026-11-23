package com.trade.marketplace.application.service;

import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.domain.rule.MarketplaceConversationRules;
import com.trade.marketplace.port.MarketplaceConversationStore;
import com.trade.marketplace.port.MarketplaceItemStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Coordinates buyer-seller conversations and enforces participant access.
 */
@Service
public class MarketplaceChatService {
    private final MarketplaceConversationStore conversations;
    private final MarketplaceItemStore items;
    private final Clock clock;

    @Autowired
    public MarketplaceChatService(MarketplaceConversationStore conversations, MarketplaceItemStore items) {
        this(conversations, items, Clock.systemUTC());
    }

    public MarketplaceChatService(MarketplaceConversationStore conversations, MarketplaceItemStore items, Clock clock) {
        this.conversations = conversations;
        this.items = items;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // A competing insert can commit after our first lookup. The retry must see that conversation.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MarketplaceApi.Conversation createConversation(MarketplacePrincipal buyer, long itemId) {
        MarketplaceApi.Item item = items.requireItem(itemId);
        MarketplaceConversationRules.requireCanStart(item.status(), item.seller().id(), buyer.id());
        return requireParticipant(conversations.findOrCreate(itemId, buyer.id(), item.seller().id()), buyer);
    }

    public MarketplaceApi.Conversations listConversations(MarketplacePrincipal user) {
        return new MarketplaceApi.Conversations(conversations.listConversations(user.id()));
    }

    public MarketplaceApi.Messages listMessages(MarketplacePrincipal user, long conversationId, Long afterId, Integer limit) {
        requireParticipant(conversations.requireConversation(conversationId), user);
        int normalizedLimit = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        Long normalizedAfterId = afterId == null || afterId <= 0 ? null : afterId;
        return new MarketplaceApi.Messages(conversations.listMessages(conversationId, normalizedAfterId, normalizedLimit));
    }

    @Transactional
    public MarketplaceApi.Message sendMessage(
            MarketplacePrincipal sender,
            long conversationId,
            MarketplaceApi.SendMessageRequest request
    ) {
        requireParticipant(conversations.requireConversation(conversationId), sender);
        String body = MarketplaceConversationRules.cleanBody(request == null ? null : request.body());
        return conversations.appendMessage(conversationId, sender, body, Instant.now(clock));
    }

    private static MarketplaceApi.Conversation requireParticipant(
            MarketplaceApi.Conversation conversation, MarketplacePrincipal user) {
        MarketplaceConversationRules.requireParticipant(conversation.buyer().id(), conversation.seller().id(), user.id());
        return conversation;
    }
}
