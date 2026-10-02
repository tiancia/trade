package com.trade.weibo.infrastructure.trend;

import com.trade.weibo.application.port.HotEventSource;
import com.trade.weibo.domain.model.HotEvent;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** One configured RSS/Atom news feed. Missing dates/summaries are skipped, never invented. */
@Component
public class RssHotEventSource implements HotEventSource {
    private static final int MAX_BYTES = 262144;
    private final WeiboWorkflowProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Clock clock;

    @Autowired
    public RssHotEventSource(WeiboWorkflowProperties properties) { this(properties, Clock.systemUTC()); }
    public RssHotEventSource(WeiboWorkflowProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<HotEvent> collect() {
        String url = properties.getFeedUrl();
        if (url == null || url.isBlank()) return List.of();
        URI uri = URI.create(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Weibo news feed must use HTTPS");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml").GET().build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("News feed request failed");
                return parse(readBounded(input), Instant.now(clock));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("News feed request interrupted");
        } catch (Exception e) {
            throw new IllegalStateException("News feed unavailable; no fallback events generated");
        }
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        // The InputStream handler completes at headers; separately bound a stalled response body.
        FutureTask<byte[]> read = new FutureTask<>(() -> input.readNBytes(MAX_BYTES + 1));
        Thread.startVirtualThread(read);
        try { return read.get(15, TimeUnit.SECONDS); }
        finally { read.cancel(true); }
    }

    List<HotEvent> parse(byte[] bytes, Instant now) throws Exception {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("News feed is too large");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        NodeList items = document.getElementsByTagName("item");
        boolean atom = items.getLength() == 0;
        if (atom) items = document.getElementsByTagNameNS("*", "entry");
        List<HotEvent> events = new ArrayList<>();
        for (int i = 0; i < Math.min(items.getLength(), 20); i++) {
            Element item = (Element) items.item(i);
            try {
                String title = clean(text(item, "title"));
                String summary = clean(text(item, atom ? "summary" : "description"));
                if (atom && summary.isBlank()) summary = clean(text(item, "content"));
                String date = text(item, atom ? "published" : "pubDate");
                if (atom && date.isBlank()) date = text(item, "updated");
                Instant occurredAt = atom ? Instant.parse(date.trim())
                        : ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                String link = atom ? atomLink(item) : text(item, "link").trim();
                events.add(new HotEvent(title, link, summary, occurredAt, now));
            } catch (RuntimeException ignored) {
                // Incomplete event metadata cannot support a fact-based comment.
            }
        }
        return List.copyOf(events);
    }

    private static String atomLink(Element item) {
        NodeList links = item.getElementsByTagNameNS("*", "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            if (!link.hasAttribute("rel") || "alternate".equals(link.getAttribute("rel"))) {
                return link.getAttribute("href");
            }
        }
        return "";
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static String clean(String text) {
        return text.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
