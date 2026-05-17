package com.trade.textgame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.model.TextGameModeDefinition;
import com.trade.textgame.model.TextGameThemeDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TextGameDefinitionRegistry {
    private final Map<String, TextGameThemeDefinition> themes;
    private final Map<String, TextGameModeDefinition> modes;

    public TextGameDefinitionRegistry() {
        this(new ObjectMapper());
    }

    private TextGameDefinitionRegistry(ObjectMapper objectMapper) {
        this(
                List.of(readResource(
                        objectMapper,
                        "textgame/themes/life_100_days.json",
                        TextGameThemeDefinition.class
                )),
                List.of(readResource(
                        objectMapper,
                        "textgame/modes/short_20_turns.json",
                        TextGameModeDefinition.class
                ))
        );
    }

    public TextGameDefinitionRegistry(
            List<TextGameThemeDefinition> themes,
            List<TextGameModeDefinition> modes
    ) {
        this.themes = indexThemes(themes);
        this.modes = indexModes(modes);
    }

    public List<TextGameThemeDefinition> themes() {
        return List.copyOf(themes.values());
    }

    public List<TextGameModeDefinition> modes() {
        return List.copyOf(modes.values());
    }

    public TextGameThemeDefinition theme(String themeId) {
        TextGameThemeDefinition theme = themes.get(themeId);
        if (theme == null) {
            throw new IllegalArgumentException("Unknown text game theme: " + themeId);
        }
        return theme;
    }

    public TextGameModeDefinition mode(String modeId) {
        TextGameModeDefinition mode = modes.get(modeId);
        if (mode == null) {
            throw new IllegalArgumentException("Unknown text game mode: " + modeId);
        }
        return mode;
    }

    public String defaultThemeId() {
        return themes.keySet().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No text game themes configured"));
    }

    public String defaultModeId() {
        return modes.keySet().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No text game modes configured"));
    }

    private static Map<String, TextGameThemeDefinition> indexThemes(List<TextGameThemeDefinition> values) {
        LinkedHashMap<String, TextGameThemeDefinition> indexed = new LinkedHashMap<>();
        for (TextGameThemeDefinition value : values) {
            if (value == null || !hasText(value.id())) {
                throw new IllegalArgumentException("Text game theme id is required");
            }
            if (indexed.put(value.id(), value) != null) {
                throw new IllegalArgumentException("Duplicate text game theme id: " + value.id());
            }
            if (value.initialStats() == null || value.initialStats().isEmpty()) {
                throw new IllegalArgumentException("Text game theme initialStats is required: " + value.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<String, TextGameModeDefinition> indexModes(List<TextGameModeDefinition> values) {
        LinkedHashMap<String, TextGameModeDefinition> indexed = new LinkedHashMap<>();
        for (TextGameModeDefinition value : values) {
            if (value == null || !hasText(value.id())) {
                throw new IllegalArgumentException("Text game mode id is required");
            }
            if (value.maxTurns() <= 0) {
                throw new IllegalArgumentException("Text game mode maxTurns must be positive: " + value.id());
            }
            if (value.totalDays() <= 0) {
                throw new IllegalArgumentException("Text game mode totalDays must be positive: " + value.id());
            }
            if (value.stages() == null || value.stages().isEmpty()) {
                throw new IllegalArgumentException("Text game mode stages is required: " + value.id());
            }
            if (indexed.put(value.id(), value) != null) {
                throw new IllegalArgumentException("Duplicate text game mode id: " + value.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static <T> T readResource(ObjectMapper objectMapper, String path, Class<T> type) {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        } catch (IOException e) {
            throw new IllegalStateException("Load text game definition failed: " + path, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
