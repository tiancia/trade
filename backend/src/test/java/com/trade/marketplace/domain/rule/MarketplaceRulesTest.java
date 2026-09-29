package com.trade.marketplace.domain.rule;

import com.trade.marketplace.domain.exception.MarketplaceConflictException;
import com.trade.marketplace.domain.exception.MarketplaceForbiddenException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MarketplaceRulesTest {
    @Test
    void delistedItemsRemainVisibleOnlyToSeller() {
        assertTrue(MarketplaceItemRules.isVisible("LISTED", 1L, null));
        assertTrue(MarketplaceItemRules.isVisible("DELISTED", 1L, 1L));
        assertFalse(MarketplaceItemRules.isVisible("DELISTED", 1L, 2L));
        assertThrows(MarketplaceForbiddenException.class, () -> MarketplaceItemRules.requireSeller(1L, 2L));
    }

    @Test
    void listingPriceAndUploadedImageRulesDoNotNeedPersistence() {
        assertEquals(new BigDecimal("1.24"), MarketplaceItemRules.normalizePrice(new BigDecimal("1.235")));
        assertNull(MarketplaceItemRules.normalizePrice(null));
        assertThrows(IllegalArgumentException.class,
                () -> MarketplaceItemRules.normalizePrice(new BigDecimal("-0.01")));
        String prefix = "https://images.example/items/users/1/";
        assertDoesNotThrow(() -> MarketplaceItemRules.validateImageUrl(prefix + "a.jpg", prefix));
        assertThrows(IllegalArgumentException.class,
                () -> MarketplaceItemRules.validateImageUrl("https://images.example/items/users/2/a.jpg", prefix));
    }

    @Test
    void conversationRequiresListedItemAndParticipantAccess() {
        assertThrows(MarketplaceConflictException.class,
                () -> MarketplaceConversationRules.requireCanStart("DELISTED", 1L, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> MarketplaceConversationRules.requireCanStart("LISTED", 1L, 1L));
        assertDoesNotThrow(() -> MarketplaceConversationRules.requireParticipant(1L, 2L, 2L));
        assertThrows(MarketplaceForbiddenException.class,
                () -> MarketplaceConversationRules.requireParticipant(1L, 2L, 3L));
        assertEquals("hello", MarketplaceConversationRules.cleanBody(" hello "));
        assertThrows(IllegalArgumentException.class, () -> MarketplaceConversationRules.cleanBody(" "));
    }
}
