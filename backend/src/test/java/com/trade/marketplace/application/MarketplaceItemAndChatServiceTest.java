package com.trade.marketplace.application;

import com.trade.marketplace.exception.MarketplaceForbiddenException;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.model.MarketplacePrincipal;
import com.trade.marketplace.persistence.MarketplaceCategoryRow;
import com.trade.marketplace.persistence.MarketplaceUserRow;
import com.trade.marketplace.support.InMemoryMarketplaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceItemAndChatServiceTest {
    private final InMemoryMarketplaceMapper mapper = new InMemoryMarketplaceMapper();
    private MarketplaceItemService itemService;
    private MarketplaceChatService chatService;
    private MarketplacePrincipal seller;
    private MarketplacePrincipal buyer;
    private MarketplacePrincipal outsider;
    private MarketplaceCategoryRow digital;
    private MarketplaceCategoryRow books;

    @BeforeEach
    void setUp() {
        digital = mapper.addCategory("手机数码", "phones-digital", 10);
        books = mapper.addCategory("图书文具", "books-stationery", 20);
        seller = user("seller", "Seller");
        buyer = user("buyer", "Buyer");
        outsider = user("outsider", "Outsider");
        itemService = new MarketplaceItemService(mapper);
        chatService = new MarketplaceChatService(mapper, itemService);
    }

    @Test
    void createsFiltersAndDelistsOnlyBySeller() {
        MarketplaceApi.Item phone = itemService.createItem(seller, new MarketplaceApi.CreateItemRequest(
                "iPhone 15", "轻微使用痕迹", digital.getId(), "https://cdn.example.com/phone.jpg", new BigDecimal("3888")
        ));
        itemService.createItem(seller, new MarketplaceApi.CreateItemRequest(
                "算法书", "九成新", books.getId(), "https://cdn.example.com/book.jpg", null
        ));

        assertEquals(2, itemService.listItems(null, null, false, null).items().size());
        assertEquals(1, itemService.listItems(digital.getId(), null, false, null).items().size());
        assertEquals(1, itemService.listItems(null, "iphone", false, null).items().size());
        assertEquals(2, itemService.listItems(null, null, true, seller).items().size());
        assertThrows(MarketplaceForbiddenException.class, () -> itemService.delistItem(buyer, phone.id()));

        MarketplaceApi.Item delisted = itemService.delistItem(seller, phone.id());
        assertEquals("DELISTED", delisted.status());
        assertEquals(1, itemService.listItems(null, null, false, null).items().size());
        assertEquals(2, itemService.listItems(null, null, true, seller).items().size());
    }

    @Test
    void conversationAccessIsLimitedToBuyerAndSellerAndMessagesCanBeFetchedIncrementally() {
        MarketplaceApi.Item item = itemService.createItem(seller, new MarketplaceApi.CreateItemRequest(
                "尼康相机", "带原装电池", digital.getId(), "https://cdn.example.com/camera.jpg", new BigDecimal("1200")
        ));

        assertThrows(IllegalArgumentException.class, () -> chatService.createConversation(seller, item.id()));
        MarketplaceApi.Conversation conversation = chatService.createConversation(buyer, item.id());
        assertEquals(conversation.id(), chatService.createConversation(buyer, item.id()).id());
        assertThrows(MarketplaceForbiddenException.class,
                () -> chatService.listMessages(outsider, conversation.id(), null, null));

        MarketplaceApi.Message first = chatService.sendMessage(
                buyer,
                conversation.id(),
                new MarketplaceApi.SendMessageRequest("还在吗？")
        );
        MarketplaceApi.Message second = chatService.sendMessage(
                seller,
                conversation.id(),
                new MarketplaceApi.SendMessageRequest("在，今天可取。")
        );

        assertEquals(2, chatService.listMessages(buyer, conversation.id(), null, 10).messages().size());
        MarketplaceApi.Messages incremental = chatService.listMessages(buyer, conversation.id(), first.id(), 10);
        assertEquals(1, incremental.messages().size());
        assertEquals(second.id(), incremental.messages().getFirst().id());
        assertEquals(second.body(), chatService.listConversations(seller).conversations().getFirst().lastMessage().body());
    }

    private MarketplacePrincipal user(String username, String displayName) {
        MarketplaceUserRow row = new MarketplaceUserRow()
                .setUsername(username)
                .setDisplayName(displayName)
                .setPasswordHash("hash");
        mapper.insertUser(row);
        return new MarketplacePrincipal(row.getId(), row.getUsername(), row.getDisplayName());
    }
}
