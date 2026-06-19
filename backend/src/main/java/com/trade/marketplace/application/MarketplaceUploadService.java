package com.trade.marketplace.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.marketplace.config.MarketplaceProperties;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.oss.MarketplaceOssStsClient;
import com.trade.marketplace.persistence.MarketplaceUserRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MarketplaceUploadService {
    private static final String PUBLIC_READ_ACL = "public-read";
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    private final MarketplaceProperties properties;
    private final MarketplaceOssStsClient stsClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MarketplaceUploadService(
            MarketplaceProperties properties,
            MarketplaceOssStsClient stsClient,
            ObjectMapper objectMapper
    ) {
        this(properties, stsClient, objectMapper, Clock.systemUTC());
    }

    public MarketplaceUploadService(
            MarketplaceProperties properties,
            MarketplaceOssStsClient stsClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.stsClient = stsClient;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public MarketplaceApi.UploadIntent createIntent(
            MarketplaceUserRow user,
            MarketplaceApi.UploadIntentRequest request
    ) {
        if (user == null) {
            throw new MarketplaceUnauthorizedException("login is required to upload images");
        }
        String contentType = cleanContentType(request == null ? null : request.contentType());
        String objectKey = objectKey(user, request == null ? null : request.fileName(), contentType);
        MarketplaceProperties.OssProperties oss = properties.getOss();
        try {
            String policy = uploadPolicy(oss.requiredBucket(), objectKey);
            MarketplaceApi.OssCredentials credentials = stsClient.assumeRole(
                    oss.requiredRoleArn(),
                    "marketplace-user-" + user.getId() + "-" + UUID.randomUUID().toString().substring(0, 8),
                    policy,
                    oss.normalizedDurationSeconds()
            );
            return new MarketplaceApi.UploadIntent(
                    objectKey,
                    oss.normalizedPublicBaseUrl() + "/" + objectKey,
                    oss.requiredBucket(),
                    oss.requiredRegion(),
                    PUBLIC_READ_ACL,
                    credentials
            );
        } catch (IllegalStateException e) {
            throw new MarketplaceUnavailableException(e.getMessage(), e);
        }
    }

    private String uploadPolicy(String bucket, String objectKey) {
        try {
            Map<String, Object> statement = Map.of(
                    "Effect", "Allow",
                    "Action", List.of("oss:PutObject", "oss:PutObjectAcl", "oss:AbortMultipartUpload", "oss:ListParts"),
                    "Resource", List.of("acs:oss:*:*:" + bucket + "/" + objectKey)
            );
            return objectMapper.writeValueAsString(Map.of(
                    "Version", "1",
                    "Statement", List.of(statement)
            ));
        } catch (Exception e) {
            throw new IllegalStateException("failed to build OSS upload policy", e);
        }
    }

    private String objectKey(MarketplaceUserRow user, String fileName, String contentType) {
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String ext = extension(fileName, contentType);
        return properties.getOss().normalizedKeyPrefix()
                + "/users/" + user.getId()
                + "/" + date.getYear()
                + "/" + String.format(Locale.ROOT, "%02d", date.getMonthValue())
                + "/" + UUID.randomUUID() + ext;
    }

    private static String cleanContentType(String value) {
        String contentType = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("only jpeg, png, webp, and gif images can be uploaded");
        }
        return contentType;
    }

    private static String extension(String fileName, String contentType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".webp", ".gif")) {
            if (lower.endsWith(ext)) {
                return ".jpeg".equals(ext) ? ".jpg" : ext;
            }
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
