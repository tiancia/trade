package com.trade.marketplace.application.service;

import com.trade.marketplace.domain.exception.MarketplaceNotFoundException;
import com.trade.marketplace.domain.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.domain.rule.MarketplaceItemRules;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import com.trade.marketplace.port.MarketplaceItemStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Owns listing queries, creation validation, and seller-only delisting.
 */
@Service
public class MarketplaceItemService {
    private static final int DEFAULT_ITEM_LIMIT = 60;
    private static final int MAX_ITEM_LIMIT = 100;
    private final MarketplaceItemStore items;
    private final MarketplaceProperties properties;

    public MarketplaceItemService(MarketplaceItemStore items, MarketplaceProperties properties) {
        this.items = items;
        this.properties = properties;
    }

    public MarketplaceApi.Categories categories() {
        return new MarketplaceApi.Categories(items.listCategories());
    }

    public MarketplaceApi.Items listItems(Long categoryId, String q, boolean mine, MarketplacePrincipal currentUser) {
        return listItems(categoryId, q, mine, currentUser, DEFAULT_ITEM_LIMIT);
    }

    public MarketplaceApi.Items listItems(
            Long categoryId,
            String q,
            boolean mine,
            MarketplacePrincipal currentUser,
            Integer limit
    ) {
        if (mine && currentUser == null) {
            throw new MarketplaceUnauthorizedException("login is required to view your items");
        }
        if (categoryId != null && !items.categoryExists(categoryId)) {
            throw new MarketplaceNotFoundException("category does not exist");
        }
        String cleanedQuery = q == null ? "" : q.trim();
        if (cleanedQuery.length() > 80) {
            throw new IllegalArgumentException("search query must be at most 80 characters");
        }
        Long sellerId = mine ? currentUser.id() : null;
        int normalizedLimit = limit == null
                ? DEFAULT_ITEM_LIMIT
                : Math.max(1, Math.min(MAX_ITEM_LIMIT, limit));
        return new MarketplaceApi.Items(items.listItems(categoryId, cleanedQuery, sellerId, normalizedLimit));
    }

    public MarketplaceApi.Item getItem(long id, MarketplacePrincipal currentUser) {
        MarketplaceApi.Item item = items.requireItem(id);
        if (!MarketplaceItemRules.isVisible(item.status(), item.seller().id(), currentUser == null ? null : currentUser.id())) {
            throw new MarketplaceNotFoundException("item does not exist");
        }
        return item;
    }

    @Transactional
    public MarketplaceApi.Item createItem(MarketplacePrincipal seller, MarketplaceApi.CreateItemRequest request) {
        if (seller == null) {
            throw new MarketplaceUnauthorizedException("login is required to create items");
        }
        String title = requiredText(request == null ? null : request.title(), "title is illegal", 1, 120);
        String description = requiredText(request == null ? null : request.description(), "description is too long", 0, 2000);
        String imageUrl = requiredText(request == null ? null : request.imageUrl(), "imageUrl is illegal", 8, 1000);
        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
            throw new IllegalArgumentException("imageUrl must be an absolute URL");
        }
        validateImageUrl(seller, imageUrl);
        Long categoryId = request == null ? null : request.categoryId();
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId is required");
        }
        if (!items.categoryExists(categoryId)) {
            throw new MarketplaceNotFoundException("category does not exist");
        }
        BigDecimal price = MarketplaceItemRules.normalizePrice(request == null ? null : request.price());
        return items.createListing(new MarketplaceItemStore.NewListing(
                seller.id(), categoryId, title, description, imageUrl, price));
    }

    @Transactional
    public MarketplaceApi.Item delistItem(MarketplacePrincipal seller, long itemId) {
        if (seller == null) {
            throw new MarketplaceUnauthorizedException("login is required to delist items");
        }
        MarketplaceApi.Item item = items.requireItem(itemId);
        MarketplaceItemRules.requireSeller(item.seller().id(), seller.id());
        if ("LISTED".equals(item.status())) {
            items.delistItem(itemId, seller.id());
        }
        return items.requireItem(itemId);
    }

    private void validateImageUrl(MarketplacePrincipal seller, String imageUrl) {
        String expectedPrefix = null;
        if (properties != null && ((properties.getOss().getPublicBaseUrl() != null
                && !properties.getOss().getPublicBaseUrl().isBlank())
                || (properties.getOss().getBucket() != null && !properties.getOss().getBucket().isBlank()))) {
            // The upload flow also derives its public URL from bucket/region when no custom base is set.
            expectedPrefix = properties.getOss().normalizedPublicBaseUrl()
                    + "/" + properties.getOss().normalizedKeyPrefix()
                    + "/users/" + seller.id() + "/";
        }
        MarketplaceItemRules.validateImageUrl(imageUrl, expectedPrefix);
    }

    private static String requiredText(String value, String message, int min, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }
}
