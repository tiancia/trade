package com.trade.x.interfaces.scheduler;

import com.trade.x.application.service.XPostService;
import org.springframework.stereotype.Component;

@Component
public class XScheduler {
    private final XPostService posts;
    public XScheduler(XPostService posts) { this.posts = posts; }
    public void generate() { if (!posts.runGeneration()) throw new IllegalStateException("X generate loop failed; inspect persisted workflow state"); }
    public void review() { if (!posts.runReviews()) throw new IllegalStateException("X review loop failed; inspect persisted workflow state"); }
    public void publish() { if (!posts.runPublishing()) throw new IllegalStateException("X publish loop failed; inspect persisted workflow state"); }
}
