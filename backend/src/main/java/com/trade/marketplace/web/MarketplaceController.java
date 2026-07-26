package com.trade.marketplace.web;

import com.trade.marketplace.application.MarketplaceAuthService;
import com.trade.marketplace.application.MarketplaceItemService;
import com.trade.marketplace.application.MarketplaceUploadService;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.model.MarketplacePrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for categories, listings, and image-upload intents.
 */
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {
    private final MarketplaceAuthService authService;
    private final MarketplaceItemService itemService;
    private final MarketplaceUploadService uploadService;

    public MarketplaceController(
            MarketplaceAuthService authService,
            MarketplaceItemService itemService,
            MarketplaceUploadService uploadService
    ) {
        this.authService = authService;
        this.itemService = itemService;
        this.uploadService = uploadService;
    }

    @GetMapping("/categories")
    public MarketplaceApi.Categories categories() {
        return itemService.categories();
    }

    @GetMapping("/items")
    public MarketplaceApi.Items items(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        MarketplacePrincipal currentUser = mine
                ? authService.requireUser(authorization)
                : authService.optionalUser(authorization);
        return itemService.listItems(categoryId, q, mine, currentUser, limit);
    }

    @GetMapping("/items/{id}")
    public MarketplaceApi.Item item(
            @PathVariable long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return itemService.getItem(id, authService.optionalUser(authorization));
    }

    @PostMapping("/items")
    public MarketplaceApi.Item createItem(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody MarketplaceApi.CreateItemRequest request
    ) {
        return itemService.createItem(authService.requireUser(authorization), request);
    }

    @PostMapping("/items/{id}/delist")
    public MarketplaceApi.Item delist(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long id
    ) {
        return itemService.delistItem(authService.requireUser(authorization), id);
    }

    @PostMapping("/uploads/intents")
    public MarketplaceApi.UploadIntent uploadIntent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody MarketplaceApi.UploadIntentRequest request
    ) {
        return uploadService.createIntent(authService.requireUser(authorization), request);
    }
}
