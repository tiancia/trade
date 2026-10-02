package com.trade.telegram.infrastructure.review;

import com.trade.telegram.application.port.HumanReviewGateway;
import com.trade.telegram.domain.model.ReviewRequest;
import org.springframework.stereotype.Component;

/** Fail-closed placeholder: does not send messages or fabricate review decisions. */
@Component
public class UnavailableHumanReviewGateway implements HumanReviewGateway {
    @Override
    public boolean submit(ReviewRequest request) {
        // TODO: Telegram Bot API, authenticated reviewer/chat allowlists, durable delivery
        // deduplication and callback routing by module/reference/version. No business imports.
        return false;
    }
}
