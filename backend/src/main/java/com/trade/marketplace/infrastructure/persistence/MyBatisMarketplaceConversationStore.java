package com.trade.marketplace.infrastructure.persistence;

import com.trade.marketplace.domain.exception.MarketplaceNotFoundException;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.port.MarketplaceConversationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class MyBatisMarketplaceConversationStore implements MarketplaceConversationStore {
    private final MarketplaceMapper mapper;

    public MyBatisMarketplaceConversationStore(MarketplaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MarketplaceApi.Conversation findOrCreate(long itemId, long buyerId, long sellerId) {
        MarketplaceConversationRow existing = mapper.findConversationForItemBuyer(itemId, buyerId);
        if (existing != null) {
            return MarketplaceViews.conversation(existing);
        }
        MarketplaceConversationRow created = new MarketplaceConversationRow()
                .setItemId(itemId).setBuyerId(buyerId).setSellerId(sellerId);
        try {
            mapper.insertConversation(created);
        } catch (DuplicateKeyException duplicate) {
            MarketplaceConversationRow winner = mapper.findConversationForItemBuyer(itemId, buyerId);
            if (winner == null) {
                throw duplicate;
            }
            return MarketplaceViews.conversation(winner);
        }
        return requireConversation(created.getId());
    }

    @Override
    public MarketplaceApi.Conversation requireConversation(long conversationId) {
        MarketplaceConversationRow row = mapper.findConversationById(conversationId);
        if (row == null) {
            throw new MarketplaceNotFoundException("conversation does not exist");
        }
        return MarketplaceViews.conversation(row);
    }

    @Override
    public List<MarketplaceApi.Conversation> listConversations(long userId) {
        return mapper.listConversations(userId).stream().map(MarketplaceViews::conversation).toList();
    }

    @Override
    public List<MarketplaceApi.Message> listMessages(long conversationId, Long afterId, int limit) {
        return mapper.listMessages(conversationId, afterId, limit).stream().map(MarketplaceViews::message).toList();
    }

    @Override
    public MarketplaceApi.Message appendMessage(long conversationId, MarketplacePrincipal sender, String body, Instant sentAt) {
        Timestamp timestamp = Timestamp.from(sentAt);
        MarketplaceMessageRow row = new MarketplaceMessageRow()
                .setConversationId(conversationId).setSenderId(sender.id()).setBody(body).setCreatedAt(timestamp);
        mapper.insertMessage(row);
        mapper.touchConversation(conversationId, timestamp);
        return new MarketplaceApi.Message(row.getId(), conversationId, sender.toUser(), body, sentAt);
    }
}
