package com.trade.story.persistence;

import com.trade.story.config.AiStoryProperties;
import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StoryTopicPlan;
import com.trade.story.model.StoryTrendContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class StoryFileRepository {
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final AiStoryProperties properties;

    public StoryFileRepository(AiStoryProperties properties) {
        this.properties = properties;
    }

    public Path save(
            StoryTopicPlan plan,
            List<StorySectionDraft> drafts,
            StoryTrendContext trendContext,
            int actualCharCount
    ) {
        try {
            Path outputDir = Path.of(properties.getOutputDir());
            Files.createDirectories(outputDir);
            Path outputPath = uniqueOutputPath(outputDir, plan.getTitle());
            Files.writeString(
                    outputPath,
                    buildFileContent(plan, drafts, trendContext, actualCharCount),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            return outputPath;
        } catch (IOException e) {
            throw new RuntimeException("Save AI story file failed", e);
        }
    }

    public List<String> recentStoryNames() {
        Path outputDir = Path.of(properties.getOutputDir());
        if (!Files.isDirectory(outputDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(outputDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparingLong(this::lastModifiedMillis).reversed())
                    .limit(Math.max(0, properties.getRecentStoryLimit()))
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Read recent AI story files failed", e);
        }
    }

    private Path uniqueOutputPath(Path outputDir, String title) {
        String timestamp = FILE_TIME_FORMATTER.format(Instant.now());
        String safeTitle = sanitizeFileName(title);
        Path path = outputDir.resolve(timestamp + "-" + safeTitle + ".txt");
        int suffix = 2;
        while (Files.exists(path)) {
            path = outputDir.resolve(timestamp + "-" + safeTitle + "-" + suffix + ".txt");
            suffix++;
        }
        return path;
    }

    private String buildFileContent(
            StoryTopicPlan plan,
            List<StorySectionDraft> drafts,
            StoryTrendContext trendContext,
            int actualCharCount
    ) {
        StringBuilder text = new StringBuilder();
        text.append("标题：").append(plan.getTitle()).append("\n");
        text.append("题材：").append(nullToEmpty(plan.getGenre())).append("\n");
        text.append("热门话题：").append(plan.getHotTopic()).append("\n");
        text.append("目标平台：").append(properties.getTargetPlatform()).append("\n");
        text.append("生成时间：").append(Instant.now()).append("\n");
        text.append("实际非空白字数：").append(actualCharCount).append("\n");
        text.append("题材参考来源：").append(trendContext.getSources()).append("\n\n");

        if (plan.getSellingPoints() != null && !plan.getSellingPoints().isEmpty()) {
            text.append("卖点：\n");
            for (String sellingPoint : plan.getSellingPoints()) {
                text.append("- ").append(sellingPoint).append("\n");
            }
            text.append("\n");
        }

        text.append("正文：\n\n");
        for (StorySectionDraft draft : drafts) {
            text.append("第").append(draft.getSection()).append("节");
            if (hasText(draft.getSectionTitle())) {
                text.append(" ").append(draft.getSectionTitle());
            }
            text.append("\n\n");
            text.append(draft.getContent().trim()).append("\n\n");
        }
        return text.toString();
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sanitizeFileName(String title) {
        String value = hasText(title) ? title.trim() : "未命名小说";
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        value = value.replaceAll("\\s+", "");
        if (value.length() > 40) {
            value = value.substring(0, 40);
        }
        return value.isBlank() ? "未命名小说" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
