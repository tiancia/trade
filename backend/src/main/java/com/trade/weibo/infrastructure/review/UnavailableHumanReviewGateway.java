package com.trade.weibo.infrastructure.review;

import com.trade.weibo.application.port.HumanReviewGateway;
import com.trade.weibo.domain.model.ReviewRequest;

/** Fail-closed placeholder: does not send messages or fabricate review decisions. */
public class UnavailableHumanReviewGateway implements HumanReviewGateway {
    @Override
    public boolean submit(ReviewRequest request) {
        return false;
    }
}
