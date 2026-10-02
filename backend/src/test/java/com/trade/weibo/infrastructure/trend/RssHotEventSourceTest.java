package com.trade.weibo.infrastructure.trend;

import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RssHotEventSourceTest {
    private final RssHotEventSource source = new RssHotEventSource(new WeiboWorkflowProperties());
    private final Instant now = Instant.parse("2026-10-02T01:00:00Z");

    @Test
    void parsesSourcedRssAndSkipsMissingFactsWithoutFallback() throws Exception {
        var events = source.parse(bytes("""
                <rss><channel>
                <item><title>新闻事件</title><link>https://example.test/1</link>
                  <description><![CDATA[<p>事实摘要</p>]]></description>
                  <pubDate>Fri, 02 Oct 2026 00:00:00 GMT</pubDate></item>
                <item><title>缺少时间</title><link>https://example.test/2</link><description>摘要</description></item>
                <item><title>缺少摘要</title><link>https://example.test/3</link>
                  <pubDate>Fri, 02 Oct 2026 00:00:00 GMT</pubDate></item>
                </channel></rss>
                """), now);
        assertEquals(1, events.size());
        assertEquals("事实摘要", events.getFirst().summary());
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), events.getFirst().occurredAt());
        assertTrue(source.collect().isEmpty());
    }

    @Test
    void supportsAtomAlternativeLinksAndRejectsExternalEntitiesAndOversizeFeeds() throws Exception {
        var events = source.parse(bytes("""
                <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>事件</title>
                <link rel="self" href="https://example.test/api"/>
                <link rel="alternate" href="https://example.test/news"/>
                <summary>事实摘要</summary><updated>2026-10-02T00:00:00Z</updated></entry></feed>
                """), now);
        assertEquals("https://example.test/news", events.getFirst().sourceUrl());
        assertThrows(Exception.class, () -> source.parse(bytes("""
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///not-readable">]><rss>&xxe;</rss>
                """), now));
        assertThrows(IllegalArgumentException.class, () -> source.parse(new byte[262145], now));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
