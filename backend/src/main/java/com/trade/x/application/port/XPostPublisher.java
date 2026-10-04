package com.trade.x.application.port;

import com.trade.x.domain.model.XPost;

public interface XPostPublisher {
    boolean credentialsAvailable(String targetUserId);
    String publish(XPost claimed);
}
