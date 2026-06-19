package com.trade.marketplace.application;

import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.persistence.MarketplaceCategoryRow;
import com.trade.marketplace.persistence.MarketplaceItemRow;
import com.trade.marketplace.persistence.MarketplaceMapper;
import com.trade.marketplace.persistence.MarketplaceUserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MarketplaceItemService {
    private final MarketplaceMapper mapper;

    public MarketplaceItemService(MarketplaceMapper mapper) {
        this.mapper = mapper;
    }

    public MarketplaceApi.Categories categories() {
        List<MarketplaceApi.Category> categories = mapper.listCategories().stream()
                .map(MarketplaceViews::category)
                .toList();
        return new MarketplaceApi.Categories(categories);
    }

    public MarketplaceApi.Items listItems(Long categoryId, String q, boolean mine, MarketplaceUserRow currentUser) {
        if (mine && currentUser == null) {
            throw new MarketplaceUnauthorizedException("login is required to view your items");
        }
        if (categoryId != null && mapper.findCategoryById(categoryId) == null) {
            throw new MarketplaceNotFoundException("category does not exist");
        }
        String query = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        Long sellerId = mine ? currentUser.getId() : null;
        List<MarketplaceApi.Item> items = mapper.listItems(categoryId, query, sellerId, mine).stream()
                .map(MarketplaceViews::item)
                .toList();
        return new MarketplaceApi.Items(items);
    }

    public MarketplaceApi.Item getItem(long id, MarketplaceUserRow currentUser) {
        MarketplaceItemRow item = requireItem(id);
        if (!"LISTED".equals(item.getStatus()) && !isSeller(item, currentUser)) {
            throw new MarketplaceNotFoundException("item does not exist");
        }
        return MarketplaceViews.item(item);
    }

    @Transactional
    public MarketplaceApi.Item createItem(MarketplaceUserRow seller, MarketplaceApi.CreateItemRequest request) {
        if (seller == null) {
            throw new MarketplaceUnauthorizedException("login is required to create items");
        }
        String title = requiredText(request == null ? null : request.title(), "title is illegal", 1, 120);
        String description = requiredText(request == null ? null : request.description(), "description is too long", 0, 2000);
        String imageUrl = requiredText(request == null ? null : request.imageUrl(), "imageUrl is illegal", 8, 1000);
        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
            throw new IllegalArgumentException("imageUrl must be an absolute URL");
        }
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
                .setSellerId(seller.getId())
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
    public MarketplaceApi.Item delistItem(MarketplaceUserRow seller, long itemId) {
        if (seller == null) {
            throw new MarketplaceUnauthorizedException("login is required to delist items");
        }
        MarketplaceItemRow item = requireItem(itemId);
        if (!isSeller(item, seller)) {
            throw new MarketplaceForbiddenException("only the seller can delist this item");
        }
        if ("LISTED".equals(item.getStatus())) {
            mapper.delistItem(itemId, seller.getId());
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

    private static boolean isSeller(MarketplaceItemRow item, MarketplaceUserRow user) {
        return user != null && item.getSellerId() != null && item.getSellerId().equals(user.getId());
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

    private static String requiredText(String value, String message, int min, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }
}
