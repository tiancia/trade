package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.WeiboPost;

/** Sends only an already claimed, reviewed aggregate. Returns the provider's post ID. */
public interface WeiboPostPublisher {
    boolean credentialsAvailable(String uid);
    String publish(WeiboPost claimed);
}
