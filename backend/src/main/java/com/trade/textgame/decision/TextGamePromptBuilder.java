package com.trade.textgame.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.model.TextGameChoice;
import com.trade.textgame.model.TextGameModeDefinition;
import com.trade.textgame.model.TextGameScene;
import com.trade.textgame.model.TextGameStageDefinition;
import com.trade.textgame.model.TextGameThemeDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TextGamePromptBuilder {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String buildOpeningPrompt(
            TextGameThemeDefinition theme,
            TextGameModeDefinition mode,
            TextGameStageDefinition stage,
            Map<String, Integer> stats
    ) {
        return """
                你是一个中文选择驱动型网页文字游戏引擎。请为新局生成开场场景。
                只返回一个 JSON object，不要输出 markdown、注释或额外解释。

                必须返回的 JSON schema:
                {
                  "scene": {
                    "title": "第0天：具体场景标题",
                    "text": "开场剧情正文，120-260字，必须给出清晰困境和玩家可行动空间",
                    "choices": [
                      {"id": "A", "label": "选择文本", "hint": "倾向或代价"},
                      {"id": "B", "label": "选择文本", "hint": "倾向或代价"}
                    ]
                  }
                }

                规则:
                - choices 必须是 2 或 3 个，id 使用 A/B/C。
                - 不要替玩家做出选择，只呈现选择前的状态。
                - 文风具体、现实、有压力，不要使用空泛鸡汤或旁白总结。
                - 避免露骨色情、极端血腥、违法教程、仇恨和未成年人不当内容。

                游戏定义 JSON:
                %s

                当前状态 JSON:
                %s
                """.formatted(toJson(definitionView(theme, mode, stage)), toJson(Map.of("stats", stats)));
    }

    public String buildTurnPrompt(
            TextGameThemeDefinition theme,
            TextGameModeDefinition mode,
            UUID sessionId,
            int currentTurn,
            int nextTurn,
            int nextDay,
            TextGameStageDefinition nextStage,
            Map<String, Integer> stats,
            TextGameScene currentScene,
            TextGameChoice selectedChoice,
            String lastResult,
            List<Map<String, Object>> history,
            boolean finalTurn
    ) {
        String schema = finalTurn ? """
                {
                  "result": "本次选择的直接后果，60-160字",
                  "statsDelta": {"money": 0, "health": 0, "skill": 0, "network": 0, "reputation": 0, "risk": 0},
                  "ending": {
                    "title": "结局标题",
                    "grade": "S/A/B/C/D 之一",
                    "summary": "100天结局总结，180-320字",
                    "echoes": ["一个早期选择带来的回声", "关键关系或代价的结算"]
                  }
                }
                """ : """
                {
                  "result": "本次选择的直接后果，60-160字",
                  "statsDelta": {"money": 0, "health": 0, "skill": 0, "network": 0, "reputation": 0, "risk": 0},
                  "scene": {
                    "title": "第N天：具体场景标题",
                    "text": "下一幕剧情正文，120-260字",
                    "choices": [
                      {"id": "A", "label": "选择文本", "hint": "倾向或代价"},
                      {"id": "B", "label": "选择文本", "hint": "倾向或代价"}
                    ]
                  }
                }
                """;
        return """
                你是一个中文选择驱动型网页文字游戏引擎。请根据玩家选择推进一回合。
                只返回一个 JSON object，不要输出 markdown、注释或额外解释。

                必须返回的 JSON schema:
                %s

                规则:
                - 当前是第 %d/%d 次决策后的生成，时间推进到第 %d 天，阶段是「%s」。
                - statsDelta 只写整数增减，后端会负责最终属性边界；money 可正可负，其余属性建议在 -20 到 20 内波动。
                - result 必须只描述玩家刚才选择造成的结果，不要提前泄露下一步选项答案。
                - %s
                - 文风具体、现实、有压力，少用抽象总结，多用账目、身体状态、具体人际反馈和行动后果。
                - 避免露骨色情、极端血腥、违法教程、仇恨和未成年人不当内容。

                游戏定义 JSON:
                %s

                会话状态 JSON:
                %s
                """.formatted(
                schema,
                nextTurn,
                mode.maxTurns(),
                nextDay,
                nextStage.name(),
                finalTurn ? "这是最终回合，必须返回 ending，不要返回下一组选项。" : "必须返回下一幕 scene，choices 必须是 2 或 3 个，id 使用 A/B/C。",
                toJson(definitionView(theme, mode, nextStage)),
                toJson(sessionView(
                        sessionId,
                        currentTurn,
                        nextTurn,
                        stats,
                        currentScene,
                        selectedChoice,
                        lastResult,
                        history
                ))
        );
    }

    private static Map<String, Object> definitionView(
            TextGameThemeDefinition theme,
            TextGameModeDefinition mode,
            TextGameStageDefinition stage
    ) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("themeId", theme.id());
        view.put("themeName", theme.name());
        view.put("themeDescription", theme.description());
        view.put("premise", theme.premise());
        view.put("protagonistSetup", theme.protagonistSetup());
        view.put("tone", theme.tone());
        view.put("initialStats", theme.initialStats());
        view.put("statLabels", theme.statLabels());
        view.put("statRules", theme.statRules());
        view.put("openingHooks", theme.openingHooks());
        view.put("interludeActions", theme.interludeActions());
        view.put("settlingActions", theme.settlingActions());
        view.put("modeId", mode.id());
        view.put("modeName", mode.name());
        view.put("maxTurns", mode.maxTurns());
        view.put("totalDays", mode.totalDays());
        view.put("stages", mode.stages());
        view.put("currentStage", stage);
        return view;
    }

    private static Map<String, Object> sessionView(
            UUID sessionId,
            int currentTurn,
            int nextTurn,
            Map<String, Integer> stats,
            TextGameScene currentScene,
            TextGameChoice selectedChoice,
            String lastResult,
            List<Map<String, Object>> history
    ) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", sessionId);
        view.put("currentTurn", currentTurn);
        view.put("nextTurn", nextTurn);
        view.put("currentStats", stats);
        view.put("currentScene", currentScene);
        view.put("selectedChoice", selectedChoice);
        view.put("lastResult", lastResult);
        view.put("history", history);
        return view;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Serialize text game prompt context failed", e);
        }
    }
}
