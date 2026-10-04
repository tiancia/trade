package com.trade.weibo.interfaces.scheduler;

import com.trade.weibo.application.service.WeiboPostService;
import org.springframework.stereotype.Component;

/** Thin task adapter; registration/loop lifecycle is owned by automation. */
@Component
public class WeiboScheduler {
    private final WeiboPostService posts;
    public WeiboScheduler(WeiboPostService posts) { this.posts = posts; }
    public void generate() { posts.runGeneration(); }
    public void review() { posts.runReviews(); }
    public void publish() { posts.runPublishing(); }
}
