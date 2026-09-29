package com.trade.marketplace.application.service;

import com.trade.marketplace.domain.rule.MarketplaceItemRules;
import com.trade.marketplace.domain.exception.MarketplaceNotFoundException;
import com.trade.marketplace.domain.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import com.trade.marketplace.infrastructure.persistence.MarketplaceCategoryRow;
import com.trade.marketplace.infrastructure.persistence.MarketplaceItemRow;
import com.trade.marketplace.infrastructure.persistence.MarketplaceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Owns listing queries, creation validation, and seller-only delisting.
 */
@Service
public class MarketplaceItemService {
    private static final int DEFAULT_ITEM_LIMIT = 60;
    private static final int MAX_ITEM_LIMIT = 100;
    private final MarketplaceMapper mapper;
    private final MarketplaceProperties properties;

    @Autowired
    public MarketplaceItemService(MarketplaceMapper mapper, MarketplaceProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    MarketplaceItemService(MarketplaceMapper mapper) {
        this(mapper, null);
    }

    public MarketplaceApi.Categories categories() {
        List<MarketplaceApi.Category> categories = mapper.listCategories().stream()
                .map(MarketplaceViews::category)
                .toList();
        return new MarketplaceApi.Categories(categories);
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
        if (categoryId != null && mapper.findCategoryById(categoryId) == null) {
            throw new MarketplaceNotFoundException("category does not exist");
        }
        String cleanedQuery = q == null ? "" : q.trim();
        if (cleanedQuery.length() > 80) {
            throw new IllegalArgumentException("search query must be at most 80 characters");
        }
        String query = cleanedQuery.isBlank() ? null : "%" + cleanedQuery.toLowerCase(Locale.ROOT) + "%";
        Long sellerId = mine ? currentUser.id() : null;
        int normalizedLimit = limit == null
                ? DEFAULT_ITEM_LIMIT
                : Math.max(1, Math.min(MAX_ITEM_LIMIT, limit));
        List<MarketplaceApi.Item> items = mapper.listItems(categoryId, query, sellerId, mine, normalizedLimit).stream()
                .map(MarketplaceViews::item)
                .toList();
        return new MarketplaceApi.Items(items);
    }

    public MarketplaceApi.Item getItem(long id, MarketplacePrincipal currentUser) {
        MarketplaceItemRow item = requireItem(id);
        if (!MarketplaceItemRules.isVisible(item.getStatus(), item.getSellerId(), currentUser == null ? null : currentUser.id())) {
            throw new MarketplaceNotFoundException("item does not exist");
        }
        return MarketplaceViews.item(item);
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
        MarketplaceCategoryRow category = mapper.findCategoryById(categoryId);
        if (category == null) {
            throw new MarketplaceNotFoundException("category does not exist");
        }
        BigDecimal price = MarketplaceItemRules.normalizePrice(request == null ? null : request.price());
        MarketplaceItemRow row = new MarketplaceItemRow()
                .setSellerId(seller.id())
                .setCategoryId(categoryId)
                .setTitle(title)
                .setDescription(description)
                .setImageUrl(imageUrl)
                .setPrice(price)
                .setStatus("LISTED");
        mapper.insertItem(row);
        return MarketplaceViews.item(requireItem(row.getId()));
    }

    @Transactional
    public MarketplaceApi.Item delistItem(MarketplacePrincipal seller, long itemId) {
        if (seller == null) {
            throw new MarketplaceUnauthorizedException("login is required to delist items");
        }
        MarketplaceItemRow item = requireItem(itemId);
        MarketplaceItemRules.requireSeller(item.getSellerId(), seller.id());
        if ("LISTED".equals(item.getStatus())) {
            mapper.delistItem(itemId, seller.id());
        }
        return MarketplaceViews.item(requireItem(itemId));
    }

    MarketplaceItemRow requireItem(long id) {
        MarketplaceItemRow item = mapper.findItemById(id);
        if (item == null) {
            throw new MarketplaceNotFoundException("item does not exist");
        }
        return item;
    }

    private void validateImageUrl(MarketplacePrincipal seller, String imageUrl) {
        String expectedPrefix = null;
        if (properties != null && properties.getOss().getPublicBaseUrl() != null
                && !properties.getOss().getPublicBaseUrl().isBlank()) {
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
