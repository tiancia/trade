package com.trade.textgame.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextGameStoryValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TextGameStoryValidator validator = new TextGameStoryValidator();
    private final TextGameRuleEngine engine = new TextGameRuleEngine();

    @Test
    void builtInStoryIsValidAndAllPlayablePathsFinishWithinSixChoices() throws Exception {
        JsonNode root = sample();
        StoryValidation.Result validation = validator.validate(root);
        assertTrue(validation.valid(), () -> validation.errors().toString());
        assertEquals(14, root.path("nodes").findValuesAsText("type").stream().filter("scene"::equals).count());

        StoryDocument story = StoryDocument.from(root);
        GameState initial = objectMapper.convertValue(root.path("initialState"), GameState.class);
        ArrayDeque<PathState> pending = new ArrayDeque<>();
        pending.add(new PathState(story.startNodeId(), initial, 0));
        Set<String> endings = new HashSet<>();
        while (!pending.isEmpty()) {
            PathState path = pending.removeFirst();
            JsonNode node = story.node(path.nodeId());
            if ("ending".equals(node.path("type").asText())) {
                endings.add(path.nodeId());
                assertEquals(6, path.depth());
                continue;
            }
            assertTrue(path.depth() < 6, "scene remained after six choices: " + path.nodeId());
            int playable = 0;
            for (JsonNode choice : engine.visibleChoices(node, path.state())) {
                if (!engine.matches(choice.path("enabledWhen"), path.state())) {
                    continue;
                }
                playable++;
                GameState nextState = objectMapper.convertValue(objectMapper.valueToTree(path.state()), GameState.class);
                engine.applyEffects(choice.path("effects"), nextState);
                String next = engine.resolveTransition(choice, nextState);
                nextState.getHistory().add(choice.path("id").asText());
                pending.add(new PathState(next, nextState, path.depth() + 1));
            }
            assertTrue(playable > 0, "dead end at " + path.nodeId());
        }
        assertEquals(Set.of("ending_debt_free", "ending_breakthrough", "ending_trusted", "ending_balanced"), endings);
    }

    @Test
    void rejectsDanglingReferencesCyclesIllegalOperatorsAndLongText() throws Exception {
        ObjectNode root = sample().deepCopy();
        ObjectNode firstChoice = (ObjectNode) root.path("nodes").get(0).path("choices").get(0);
        firstChoice.put("script", "doSomething()");
        ((ObjectNode) firstChoice.path("transitions").get(0)).put("to", "missing");
        ((ObjectNode) firstChoice.path("effects").get(0)).put("op", "multiply");
        ((ObjectNode) firstChoice).set("enabledWhen", objectMapper.readTree(
                "{\"source\":\"attribute\",\"key\":\"cash\",\"op\":\"between\",\"value\":1}"
        ));
        ((ArrayNode) root.path("nodes").get(1).path("text")).set(0, objectMapper.getNodeFactory().textNode("x".repeat(501)));
        ObjectNode finalFallback = (ObjectNode) root.path("nodes").get(13).path("choices").get(3).path("transitions").get(0);
        finalFallback.put("to", "day01_crossroads");

        StoryValidation.Result result = validator.validate(root);
        assertFalse(result.valid());
        Set<String> codes = result.errors().stream().map(StoryValidation.Issue::code).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("DANGLING_REFERENCE"));
        assertTrue(codes.contains("CYCLE_NOT_ALLOWED"));
        assertTrue(codes.contains("INVALID_EFFECT_OPERATOR"));
        assertTrue(codes.contains("INVALID_CONDITION_OPERATOR"));
        assertTrue(codes.contains("TEXT_TOO_LONG"));
        assertTrue(codes.contains("ILLEGAL_FIELD"));
    }

    private JsonNode sample() throws Exception {
        String json = new ClassPathResource("textgame/stories/100-days-comeback.v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private record PathState(String nodeId, GameState state, int depth) {
    }
}
