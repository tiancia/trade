package com.trade.telegram.infrastructure.review;

import com.trade.telegram.domain.model.ReviewRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class UnavailableHumanReviewGatewayTest {
    @Test
    void sharedPlaceholderNeverSendsOrApprovesForAnyModule() {
        var gateway = new UnavailableHumanReviewGateway();
        assertFalse(gateway.submit(new ReviewRequest("weibo", "draft", 1, "body", "context", Instant.EPOCH)));
        assertFalse(gateway.submit(new ReviewRequest("other-module", "reference", 2, "body", "context", Instant.EPOCH)));
    }
}
