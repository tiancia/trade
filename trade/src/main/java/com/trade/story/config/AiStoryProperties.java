package com.trade.story.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.story")
public class AiStoryProperties {
    private boolean enabled = false;
    private long generationFixedDelayMs = 14_400_000L;
    private long initialDelayMs = 120_000L;
    private String outputDir = "A:\\trade\\story";
    private int targetCharCount = 15_000;
    private int minAcceptableCharCount = 13_000;
    private int sectionCount = 6;
    private int maxContinuationSections = 2;
    private int recentStoryLimit = 8;
    private String targetPlatform = "番茄小说等中文网络小说平台";
    private boolean trendFetchEnabled = true;
    private int trendFetchTimeoutMs = 10_000;
    private int trendSourceMaxChars = 6_000;
    private List<String> trendSourceUrls = new ArrayList<>(List.of(
            "https://fanqienovel.com/rank"
    ));
    private List<String> fallbackHotTopics = new ArrayList<>(List.of(
            "都市脑洞：普通人绑定反套路系统，在现实压力中快速逆袭",
            "末世囤货：灾变前重生，围绕家庭、物资和基地建设展开",
            "规则怪谈：现实城市出现诡异规则，主角靠推理和冷静破局",
            "玄幻修仙：低起点主角获得隐藏传承，主线强调升级和宗门冲突",
            "年代重生：回到关键节点弥补遗憾，兼顾创业、亲情和爽点",
            "悬疑推理：小城连环谜案与个人命运交织，节奏紧凑反转明确",
            "女频成长：女主事业线、家庭关系和情感选择并行推进",
            "无限流副本：高概念副本、团队博弈和阶段性通关奖励"
    ));
    private ProxyProperties trendProxy = new ProxyProperties();

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
