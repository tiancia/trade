package com.trade.marketplace.infrastructure.persistence;

import com.trade.marketplace.domain.exception.MarketplaceNotFoundException;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.port.MarketplaceItemStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

@Repository
public class MyBatisMarketplaceItemStore implements MarketplaceItemStore {
    private final MarketplaceMapper mapper;

    public MyBatisMarketplaceItemStore(MarketplaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MarketplaceApi.Category> listCategories() {
        return mapper.listCategories().stream().map(MarketplaceViews::category).toList();
    }

    @Override
    public boolean categoryExists(long categoryId) {
        return mapper.findCategoryById(categoryId) != null;
    }

    @Override
    public List<MarketplaceApi.Item> listItems(Long categoryId, String query, Long sellerId, int limit) {
        String pattern = query == null || query.isBlank() ? null : "%" + query.toLowerCase(Locale.ROOT) + "%";
        return mapper.listItems(categoryId, pattern, sellerId, sellerId != null, limit)
                .stream().map(MarketplaceViews::item).toList();
    }

    @Override
    public MarketplaceApi.Item requireItem(long itemId) {
        MarketplaceItemRow row = mapper.findItemById(itemId);
        if (row == null) {
            throw new MarketplaceNotFoundException("item does not exist");
        }
        return MarketplaceViews.item(row);
    }

    @Override
    public MarketplaceApi.Item createListing(NewListing listing) {
        MarketplaceItemRow row = new MarketplaceItemRow()
                .setSellerId(listing.sellerId())
                .setCategoryId(listing.categoryId())
                .setTitle(listing.title())
                .setDescription(listing.description())
                .setImageUrl(listing.imageUrl())
                .setPrice(listing.price())
                .setStatus("LISTED");
        mapper.insertItem(row);
        return requireItem(row.getId());
    }

    @Override
    public void delistItem(long itemId, long sellerId) {
        mapper.delistItem(itemId, sellerId);
    }
}
