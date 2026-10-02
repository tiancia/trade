package com.trade.telegram.domain.model;

import java.time.Instant;

/** reference + version is the stable delivery/idempotency identity, owned by the caller. */
public record ReviewRequest(String module, String reference, int version, String content,
                            String context, Instant expiresAt) {
    public ReviewRequest {
        if (module == null || module.isBlank() || reference == null || reference.isBlank()
                || version < 1 || content == null || content.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Invalid review request");
        }
    }
}
