package com.trade.story.trend;

import com.trade.story.config.AiStoryProperties;
import com.trade.story.model.StoryTrendContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class StoryTrendCollector {
    private static final Logger log = LoggerFactory.getLogger(StoryTrendCollector.class);
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern STYLE_PATTERN = Pattern.compile("(?is)<style[^>]*>.*?</style>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACE_PATTERN = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINE_PATTERN = Pattern.compile("\\n{3,}");

    private final AiStoryProperties properties;
    private final HttpClient httpClient;

    public StoryTrendCollector(AiStoryProperties properties) {
        this.properties = properties;
        this.httpClient = buildHttpClient(properties);
    }

    public StoryTrendContext collect() {
        List<String> sources = new ArrayList<>();
        StringBuilder trendText = new StringBuilder();

        if (properties.isTrendFetchEnabled()) {
            for (String url : properties.getTrendSourceUrls()) {
                if (!hasText(url)) {
                    continue;
                }
                try {
                    String text = fetchReadableText(url.trim());
                    if (hasText(text)) {
                        sources.add(url.trim());
                        appendSourceText(trendText, url.trim(), text);
                    }
                } catch (Exception e) {
                    log.warn("Fetch story trend source failed: url={}, error={}", url, e.getMessage());
                }
                if (trendText.length() >= properties.getTrendSourceMaxChars()) {
                    break;
                }
            }
        }

        if (trendText.isEmpty()) {
            sources.add("fallback-hot-topics");
            trendText.append("无法读取实时榜单时，使用本地热门题材候选池：\n");
            for (String topic : properties.getFallbackHotTopics()) {
                trendText.append("- ").append(topic).append('\n');
            }
        }

        return new StoryTrendContext()
                .setCollectedAt(Instant.now())
                .setSources(sources)
                .setTrendText(limit(trendText.toString(), properties.getTrendSourceMaxChars()));
    }

    private String fetchReadableText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1_000, properties.getTrendFetchTimeoutMs())))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("User-Agent", "Mozilla/5.0 AI-Story-Trend-Collector")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP status=" + response.statusCode());
        }
        return limit(extractReadableText(response.body()), properties.getTrendSourceMaxChars());
    }

    private static void appendSourceText(StringBuilder trendText, String url, String text) {
        if (!trendText.isEmpty()) {
            trendText.append("\n\n");
        }
        trendText.append("来源：").append(url).append('\n').append(text);
    }

    private static String extractReadableText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = SCRIPT_PATTERN.matcher(html).replaceAll("\n");
        text = STYLE_PATTERN.matcher(text).replaceAll("\n");
        text = TAG_PATTERN.matcher(text).replaceAll("\n");
        text = decodeBasicHtmlEntities(text);
        text = SPACE_PATTERN.matcher(text).replaceAll(" ");
        text = BLANK_LINE_PATTERN.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    private static String decodeBasicHtmlEntities(String text) {
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int safeMax = Math.max(500, maxChars);
        if (value.length() <= safeMax) {
            return value;
        }
        return value.substring(0, safeMax) + "\n...[truncated]";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static HttpClient buildHttpClient(AiStoryProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1_000, properties.getTrendFetchTimeoutMs())));
        AiStoryProperties.ProxyProperties proxy = properties.getTrendProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
}
