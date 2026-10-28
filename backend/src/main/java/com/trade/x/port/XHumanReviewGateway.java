package com.trade.x.port;

import com.trade.x.domain.model.XReviewRequest;
import com.trade.x.domain.model.XReviewDecision;
import java.util.function.Function;

/** Submission is not approval. Decisions must authenticate their reviewer and bound message. */
public interface XHumanReviewGateway {
    /** Explicit configuration disablement is a normal skip, not a delivery failure. */
    default boolean disabled() { return false; }

    boolean submit(XReviewRequest request);
    /** True means committed/idempotent; false permanently stale; exceptions remain unacknowledged. */
    default void poll(Function<XReviewDecision, Boolean> consume) { }
}
