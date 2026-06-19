package com.trade.marketplace.application;

import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.persistence.MarketplaceConversationRow;
import com.trade.marketplace.persistence.MarketplaceItemRow;
import com.trade.marketplace.persistence.MarketplaceMapper;
import com.trade.marketplace.persistence.MarketplaceMessageRow;
import com.trade.marketplace.persistence.MarketplaceUserRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class MarketplaceChatService {
    private final MarketplaceMapper mapper;
    private final MarketplaceItemService itemService;
    private final Clock clock;

    @Autowired
    public MarketplaceChatService(MarketplaceMapper mapper, MarketplaceItemService itemService) {
        this(mapper, itemService, Clock.systemUTC());
    }

    public MarketplaceChatService(MarketplaceMapper mapper, MarketplaceItemService itemService, Clock clock) {
        this.mapper = mapper;
        this.itemService = itemService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Transactional
    public MarketplaceApi.Conversation createConversation(MarketplaceUserRow buyer, long itemId) {
        MarketplaceItemRow item = itemService.requireItem(itemId);
        if (!"LISTED".equals(item.getStatus())) {
            throw new MarketplaceConflictException("item is no longer listed");
        }
        if (item.getSellerId().equals(buyer.getId())) {
            throw new IllegalArgumentException("seller cannot start a conversation with themselves");
        }
        MarketplaceConversationRow existing = mapper.findConversationForItemBuyer(itemId, buyer.getId());
        if (existing != null) {
            return MarketplaceViews.conversation(existing);
        }
        MarketplaceConversationRow created = new MarketplaceConversationRow()
                .setItemId(itemId)
                .setBuyerId(buyer.getId())
                .setSellerId(item.getSellerId());
        try {
            mapper.insertConversation(created);
        } catch (DuplicateKeyException e) {
            return MarketplaceViews.conversation(mapper.findConversationForItemBuyer(itemId, buyer.getId()));
        }
        return MarketplaceViews.conversation(requireConversation(created.getId(), buyer));
    }

    public MarketplaceApi.Conversations listConversations(MarketplaceUserRow user) {
        List<MarketplaceApi.Conversation> conversations = mapper.listConversations(user.getId()).stream()
                .map(MarketplaceViews::conversation)
                .toList();
        return new MarketplaceApi.Conversations(conversations);
    }

    public MarketplaceApi.Messages listMessages(MarketplaceUserRow user, long conversationId, Long afterId, Integer limit) {
        requireConversation(conversationId, user);
        int normalizedLimit = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        Long normalizedAfterId = afterId == null || afterId <= 0 ? null : afterId;
        List<MarketplaceApi.Message> messages = mapper.listMessages(conversationId, normalizedAfterId, normalizedLimit)
                .stream()
                .map(MarketplaceViews::message)
                .toList();
        return new MarketplaceApi.Messages(messages);
    }

    @Transactional
    public MarketplaceApi.Message sendMessage(
            MarketplaceUserRow sender,
            long conversationId,
            MarketplaceApi.SendMessageRequest request
    ) {
        requireConversation(conversationId, sender);
        String body = cleanBody(request == null ? null : request.body());
        Timestamp now = Timestamp.from(Instant.now(clock));
        MarketplaceMessageRow row = new MarketplaceMessageRow()
                .setConversationId(conversationId)
                .setSenderId(sender.getId())
                .setSenderUsername(sender.getUsername())
                .setSenderDisplayName(sender.getDisplayName())
                .setBody(body)
                .setCreatedAt(now);
        mapper.insertMessage(row);
        mapper.touchConversation(conversationId, now);
        return MarketplaceViews.message(row);
    }

    private MarketplaceConversationRow requireConversation(long id, MarketplaceUserRow user) {
        MarketplaceConversationRow row = mapper.findConversationById(id);
        if (row == null) {
            throw new MarketplaceNotFoundException("conversation does not exist");
        }
        if (!row.getBuyerId().equals(user.getId()) && !row.getSellerId().equals(user.getId())) {
            throw new MarketplaceForbiddenException("only the buyer and seller can access this conversation");
        }
        return row;
    }

    private static String cleanBody(String value) {
        String body = value == null ? "" : value.trim();
        if (body.isEmpty() || body.length() > 1000) {
            throw new IllegalArgumentException("message body must be 1-1000 characters");
        }
        return body;
    }
}
