package com.trade.marketplace.application;

import com.trade.marketplace.config.MarketplaceProperties;
import com.trade.marketplace.exception.MarketplaceForbiddenException;
import com.trade.marketplace.exception.MarketplaceNotFoundException;
import com.trade.marketplace.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.model.MarketplacePrincipal;
import com.trade.marketplace.persistence.MarketplaceCategoryRow;
import com.trade.marketplace.persistence.MarketplaceItemRow;
import com.trade.marketplace.persistence.MarketplaceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
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
        if (!"LISTED".equals(item.getStatus()) && !isSeller(item, currentUser)) {
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
        BigDecimal price = normalizePrice(request == null ? null : request.price());
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
        if (!isSeller(item, seller)) {
            throw new MarketplaceForbiddenException("only the seller can delist this item");
        }
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

    private static boolean isSeller(MarketplaceItemRow item, MarketplacePrincipal user) {
        return user != null && item.getSellerId() != null && item.getSellerId().equals(user.id());
    }

    private static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price cannot be negative");
        }
        if (price.scale() > 2) {
            return price.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return price;
    }

    private void validateImageUrl(MarketplacePrincipal seller, String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imageUrl must be a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("imageUrl must use HTTPS");
        }
        if (properties == null || properties.getOss().getPublicBaseUrl() == null
                || properties.getOss().getPublicBaseUrl().isBlank()) {
            return;
        }
        String expectedPrefix = properties.getOss().normalizedPublicBaseUrl()
                + "/" + properties.getOss().normalizedKeyPrefix()
                + "/users/" + seller.id() + "/";
        if (!imageUrl.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("imageUrl must reference an uploaded marketplace image");
        }
    }

    private static String requiredText(String value, String message, int min, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }
}
