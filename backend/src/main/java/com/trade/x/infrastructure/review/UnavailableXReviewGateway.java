package com.trade.x.infrastructure.review;

import com.trade.x.application.port.XHumanReviewGateway;
import com.trade.x.domain.model.XReviewRequest;

public class UnavailableXReviewGateway implements XHumanReviewGateway {
    @Override public boolean submit(XReviewRequest request) { return false; }
}
