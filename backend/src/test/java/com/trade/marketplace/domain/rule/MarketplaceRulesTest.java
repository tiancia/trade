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
    void listingPricesRoundWithinTheColumnBoundsEvenWithExtremeExponents() {
        for (String price : new String[] {"1e-2147483647", "0e2147483647", "0.004999"}) {
            assertEquals(new BigDecimal("0.00"), MarketplaceItemRules.normalizePrice(new BigDecimal(price)));
        }
        assertEquals(new BigDecimal("0.01"), MarketplaceItemRules.normalizePrice(new BigDecimal("0.005")));
        assertEquals(new BigDecimal("9999999999.99"),
                MarketplaceItemRules.normalizePrice(new BigDecimal("9999999999.9949")));
        for (String price : new String[] {"9999999999.995", "1e2147483647"}) {
            assertThrows(IllegalArgumentException.class, () -> MarketplaceItemRules.normalizePrice(new BigDecimal(price)));
        }
    }

    @Test
    void uploadedImageCannotEscapeTheSellersObjectPrefix() {
        String prefix = "https://images.example/items/users/1/";
        for (String suffix : new String[] {"../2/a.jpg", "%2e%2e/2/a.jpg", ".%2e/2/a.jpg",
                "%2e./2/a.jpg", "a/../../2/a.jpg", "a.jpg?x-oss-process=image/resize,w_1", "a.jpg#ignored"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MarketplaceItemRules.validateImageUrl(prefix + suffix, prefix), suffix);
        }
    }

    @Test
    void passwordLimitUsesUtf8BytesToMatchBcrypt() {
        assertEquals("a".repeat(72), MarketplaceAccountRules.requiredPassword("a".repeat(72)));
        assertEquals("密".repeat(24), MarketplaceAccountRules.requiredPassword("密".repeat(24)));
        assertThrows(IllegalArgumentException.class,
                () -> MarketplaceAccountRules.requiredPassword("a".repeat(73)));
        assertThrows(IllegalArgumentException.class,
                () -> MarketplaceAccountRules.requiredPassword("密".repeat(25)));
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
