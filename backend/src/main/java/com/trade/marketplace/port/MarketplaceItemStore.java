package com.trade.marketplace.port;

import com.trade.marketplace.domain.model.MarketplaceApi;

import java.math.BigDecimal;
import java.util.List;

public interface MarketplaceItemStore {
    record NewListing(long sellerId, long categoryId, String title, String description,
                      String imageUrl, BigDecimal price) {}

    List<MarketplaceApi.Category> listCategories();

    boolean categoryExists(long categoryId);

    /** A seller filter includes that seller's delisted items. */
    List<MarketplaceApi.Item> listItems(Long categoryId, String query, Long sellerId, int limit);

    MarketplaceApi.Item requireItem(long itemId);

    MarketplaceApi.Item createListing(NewListing listing);

    void delistItem(long itemId, long sellerId);
}
