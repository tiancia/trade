package com.trade.x.interfaces.scheduler;

import com.trade.x.application.service.XPostService;
import org.springframework.stereotype.Component;

@Component
public class XScheduler {
    private final XPostService posts;
    public XScheduler(XPostService posts) { this.posts = posts; }
    public void generate() { posts.runGeneration(); }
    public void review() { posts.runReviews(); }
    public void publish() { posts.runPublishing(); }
}
