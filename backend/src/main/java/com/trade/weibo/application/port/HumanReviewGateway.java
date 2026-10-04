package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.ReviewRequest;
import com.trade.weibo.domain.model.ReviewDecision;
import java.util.function.Function;

/** Weibo draft-review contract; a successful submission never means approval. */
public interface HumanReviewGateway {
    /** Implementations must deduplicate module/reference/version. */
    boolean submit(ReviewRequest request);

    /** True means committed/idempotent; false means permanently stale. Exceptions must remain unacknowledged. */
    default void poll(Function<ReviewDecision, Boolean> consume) { }
}
