package com.trade.marketplace.application.service;

import com.trade.marketplace.domain.rule.MarketplaceImageRules;
import com.trade.marketplace.application.port.MarketplaceOssStsClient;
import com.trade.marketplace.domain.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.domain.exception.MarketplaceUnavailableException;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates constrained Aliyun OSS upload intents for authenticated users.
 *
 * <p>Image bytes go directly from the frontend to OSS; this service only
 * validates metadata and issues short-lived credentials.</p>
 */
@Service
public class MarketplaceUploadService {
    private static final String PUBLIC_READ_ACL = "public-read";
    private final MarketplaceProperties properties;
    private final MarketplaceOssStsClient stsClient;
    private final Clock clock;

    @Autowired
    public MarketplaceUploadService(
            MarketplaceProperties properties,
            MarketplaceOssStsClient stsClient
    ) {
        this(properties, stsClient, Clock.systemUTC());
    }

    public MarketplaceUploadService(
            MarketplaceProperties properties,
            MarketplaceOssStsClient stsClient,
            Clock clock
    ) {
        this.properties = properties;
        this.stsClient = stsClient;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public MarketplaceApi.UploadIntent createIntent(
            MarketplacePrincipal user,
            MarketplaceApi.UploadIntentRequest request
    ) {
        if (user == null) {
            throw new MarketplaceUnauthorizedException("login is required to upload images");
        }
        String contentType = MarketplaceImageRules.cleanContentType(request == null ? null : request.contentType());
        MarketplaceImageRules.validateSize(request == null ? null : request.sizeBytes());
        String objectKey = objectKey(user, request == null ? null : request.fileName(), contentType);
        MarketplaceProperties.OssProperties oss = properties.getOss();
        try {
            MarketplaceApi.OssCredentials credentials = stsClient.assumeUploadRole(
                    oss.requiredRoleArn(),
                    "marketplace-user-" + user.id() + "-" + UUID.randomUUID().toString().substring(0, 8),
                    oss.requiredBucket(),
                    objectKey,
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

    private String objectKey(MarketplacePrincipal user, String fileName, String contentType) {
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String ext = MarketplaceImageRules.extension(fileName, contentType);
        return properties.getOss().normalizedKeyPrefix()
                + "/users/" + user.id()
                + "/" + date.getYear()
                + "/" + String.format(Locale.ROOT, "%02d", date.getMonthValue())
                + "/" + UUID.randomUUID() + ext;
    }

}
