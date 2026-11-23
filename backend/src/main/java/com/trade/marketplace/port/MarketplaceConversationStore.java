package com.trade.marketplace.port;

import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;

import java.time.Instant;
import java.util.List;

public interface MarketplaceConversationStore {
    MarketplaceApi.Conversation findOrCreate(long itemId, long buyerId, long sellerId);

    MarketplaceApi.Conversation requireConversation(long conversationId);

    List<MarketplaceApi.Conversation> listConversations(long userId);

    List<MarketplaceApi.Message> listMessages(long conversationId, Long afterId, int limit);

    MarketplaceApi.Message appendMessage(long conversationId, MarketplacePrincipal sender, String body, Instant sentAt);
}
