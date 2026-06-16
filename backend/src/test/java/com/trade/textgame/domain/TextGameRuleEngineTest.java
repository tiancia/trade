package com.trade.textgame.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextGameRuleEngineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TextGameRuleEngine engine = new TextGameRuleEngine();

    @Test
    void combinesConditionsAndUsesUpdatedStateForTransition() throws Exception {
        GameState state = new GameState();
        state.setAttributes(Map.of("cash", 5, "skill", 10));
        state.setFlags(Map.of("ready", true));
        JsonNode condition = objectMapper.readTree("""
                {"all":[
                  {"source":"attribute","key":"cash","op":"gte","value":5},
                  {"not":{"source":"attribute","key":"skill","op":"gt","value":20}},
                  {"source":"flag","key":"ready","op":"eq","value":true}
                ]}
                """);
        assertTrue(engine.matches(condition, state));

        JsonNode choice = objectMapper.readTree("""
                {
                  "id":"raise",
                  "effects":[
                    {"target":"attribute","key":"cash","op":"add","value":10},
                    {"target":"attribute","key":"cash","op":"set","value":30},
                    {"target":"attribute","key":"cash","op":"add","value":2}
                  ],
                  "transitions":[
                    {"when":{"source":"attribute","key":"cash","op":"gte","value":30},"to":"win"},
                    {"to":"lose"}
                  ]
                }
                """);
        TextGameRuleEngine.EffectResult result = engine.applyEffects(choice.path("effects"), state);
        assertEquals(32, state.getAttributes().get("cash"));
        assertEquals(27, result.attributeDelta().get("cash"));
        assertEquals("win", engine.resolveTransition(choice, state));
    }

    @Test
    void filtersHiddenChoicesAndTracksHistory() throws Exception {
        GameState state = new GameState();
        state.setFlags(Map.of("secret", false));
        state.setHistory(List.of("earlier_choice"));
        JsonNode scene = objectMapper.readTree("""
                {"choices":[
                  {"id":"visible"},
                  {"id":"hidden","visibleWhen":{"source":"flag","key":"secret","op":"eq","value":true}}
                ]}
                """);
        assertEquals(List.of("visible"), engine.visibleChoices(scene, state).stream()
                .map(choice -> choice.path("id").asText()).toList());
        assertTrue(engine.matches(objectMapper.readTree(
                "{\"source\":\"history\",\"key\":\"earlier_choice\",\"op\":\"contains\"}"
        ), state));
        assertFalse(engine.matches(objectMapper.readTree(
                "{\"source\":\"history\",\"key\":\"missing\",\"op\":\"contains\"}"
        ), state));
    }
}
