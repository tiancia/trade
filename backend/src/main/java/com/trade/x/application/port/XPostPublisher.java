package com.trade.x.application.port;

import com.trade.x.domain.model.XPost;

public interface XPostPublisher {
    boolean credentialsAvailable(String targetUserId);
    /** Safe, fixed diagnostic text only; must never include credentials or provider response bodies. */
    default String unavailableReason(String targetUserId) {
        return "Publisher gates or credentials are unavailable";
    }
    String publish(XPost claimed);
}
