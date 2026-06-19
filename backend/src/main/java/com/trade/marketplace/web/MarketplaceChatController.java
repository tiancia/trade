package com.trade.marketplace.web;

import com.trade.marketplace.application.MarketplaceAuthService;
import com.trade.marketplace.application.MarketplaceChatService;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.persistence.MarketplaceUserRow;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceChatController {
    private final MarketplaceAuthService authService;
    private final MarketplaceChatService chatService;

    public MarketplaceChatController(MarketplaceAuthService authService, MarketplaceChatService chatService) {
        this.authService = authService;
        this.chatService = chatService;
    }

    @PostMapping("/items/{itemId}/conversations")
    public MarketplaceApi.Conversation createConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long itemId
    ) {
        MarketplaceUserRow user = authService.requireUser(authorization);
        return chatService.createConversation(user, itemId);
    }

    @GetMapping("/conversations")
    public MarketplaceApi.Conversations conversations(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        MarketplaceUserRow user = authService.requireUser(authorization);
        return chatService.listConversations(user);
    }

    @GetMapping("/conversations/{id}/messages")
    public MarketplaceApi.Messages messages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long id,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Integer limit
    ) {
        MarketplaceUserRow user = authService.requireUser(authorization);
        return chatService.listMessages(user, id, afterId, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    public MarketplaceApi.Message sendMessage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long id,
            @RequestBody MarketplaceApi.SendMessageRequest request
    ) {
        MarketplaceUserRow user = authService.requireUser(authorization);
        return chatService.sendMessage(user, id, request);
    }
}
