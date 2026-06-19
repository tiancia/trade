package com.trade.marketplace.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MarketplaceApi {
    private MarketplaceApi() {
    }

    public record RegisterRequest(String username, String password, String displayName) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record AuthResponse(String token, User user, Instant expiresAt) {
    }

    public record User(Long id, String username, String displayName) {
    }

    public record Categories(List<Category> categories) {
    }

    public record Category(Long id, String name, String slug) {
    }

    public record Items(List<Item> items) {
    }

    public record Item(
            Long id,
            String title,
            String description,
            Category category,
            String imageUrl,
            BigDecimal price,
            String status,
            User seller,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CreateItemRequest(
            String title,
            String description,
            Long categoryId,
            String imageUrl,
            BigDecimal price
    ) {
    }

    public record UploadIntentRequest(String fileName, String contentType) {
    }

    public record UploadIntent(
            String objectKey,
            String publicUrl,
            String bucket,
            String region,
            OssCredentials credentials
    ) {
    }

    public record OssCredentials(
            String accessKeyId,
            String accessKeySecret,
            String securityToken,
            Instant expiration
    ) {
    }

    public record Conversations(List<Conversation> conversations) {
    }

    public record Conversation(
            Long id,
            Item item,
            User buyer,
            User seller,
            Message lastMessage,
            Instant updatedAt
    ) {
    }

    public record Messages(List<Message> messages) {
    }

    public record Message(
            Long id,
            Long conversationId,
            User sender,
            String body,
            Instant createdAt
    ) {
    }

    public record SendMessageRequest(String body) {
    }
}
