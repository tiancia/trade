package com.trade.weibo.interfaces.scheduler;

import com.trade.weibo.application.service.WeiboPostService;
import org.springframework.stereotype.Component;

/** Thin task adapter; registration/loop lifecycle is owned by automation. */
@Component
public class WeiboScheduler {
    private final WeiboPostService posts;
    public WeiboScheduler(WeiboPostService posts) { this.posts = posts; }
    public void generate() { if (!posts.runGeneration()) throw new IllegalStateException("Weibo generate loop failed; inspect persisted workflow state"); }
    public void review() { if (!posts.runReviews()) throw new IllegalStateException("Weibo review loop failed; inspect persisted workflow state"); }
    public void publish() { if (!posts.runPublishing()) throw new IllegalStateException("Weibo publish loop failed; inspect persisted workflow state"); }
}
