package com.trade.telegram.application.port;

import com.trade.telegram.domain.model.ReviewRequest;

/** Shared outbound contract; a successful submission never means approval. */
public interface HumanReviewGateway {
    /** Implementations must deduplicate module/reference/version. */
    boolean submit(ReviewRequest request);
}
