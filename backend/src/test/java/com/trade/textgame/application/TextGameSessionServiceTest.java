package com.trade.textgame.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.config.TextGameProperties;
import com.trade.textgame.domain.TextGameRuleEngine;
import com.trade.textgame.model.TextGameApi;
import com.trade.textgame.persistence.TextGameStoryRow;
import com.trade.textgame.persistence.TextGameVersionRow;
import com.trade.textgame.support.InMemoryTextGameMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextGameSessionServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryTextGameMapper mapper = new InMemoryTextGameMapper();
    private TextGameSessionService service;
    private String storyJson;
    private TextGameStoryRow story;

    @BeforeEach
    void setUp() throws Exception {
        storyJson = new ClassPathResource("textgame/stories/100-days-comeback.v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        story = new TextGameStoryRow().setStoryKey("100-days-comeback").setTitle("100天翻身")
                .setSummary("summary").setEnabled(true).setSortOrder(1);
        mapper.insertStory(story);
        mapper.insertVersion(new TextGameVersionRow().setStoryId(story.getId()).setVersionNumber(1)
                .setStatus("PUBLISHED").setRevision(0).setStoryJson(storyJson).setChecksum("v1")
                .setPublishedAt(Timestamp.from(Instant.now())));
        service = new TextGameSessionService(mapper, objectMapper, new TextGameRuleEngine(), new TextGameProperties());
    }

    @Test
    void createsRestoresAndAdvancesWithOptimisticLocking() {
        TextGameApi.Session created = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        assertEquals("scene", created.phase());
        assertEquals(0, created.revision());
        assertEquals(3, created.scene().choices().size());

        TextGameApi.Session result = service.submitChoice(created.sessionId(),
                new TextGameApi.SubmitChoiceRequest("start_skill", created.revision()));
        assertEquals("result", result.phase());
        assertEquals(1, result.revision());
        assertNotNull(result.result());
        assertEquals(1, mapper.listSessionEvents(created.sessionId()).size());
        assertThrows(TextGameConflictException.class, () -> service.submitChoice(created.sessionId(),
                new TextGameApi.SubmitChoiceRequest("start_delivery", 0L)));

        TextGameApi.Session next = service.continueGame(created.sessionId(),
                new TextGameApi.ContinueRequest(result.revision()));
        assertEquals("scene", next.phase());
        assertEquals(2, next.revision());
        assertEquals("day15_skill", next.scene().nodeId());

        TextGameSessionService restarted = new TextGameSessionService(
                mapper, objectMapper, new TextGameRuleEngine(), new TextGameProperties());
        assertEquals(next, restarted.getSession(created.sessionId()));
    }

    @Test
    void existingSessionKeepsOriginalStoryVersionAfterNewPublication() {
        TextGameApi.Session created = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        mapper.archivePublished(story.getId());
        mapper.insertVersion(new TextGameVersionRow().setStoryId(story.getId()).setVersionNumber(2)
                .setStatus("PUBLISHED").setRevision(0).setStoryJson(storyJson).setChecksum("v2")
                .setPublishedAt(Timestamp.from(Instant.now())));

        assertEquals(1, service.getSession(created.sessionId()).story().version());
        assertEquals(2, service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback")).story().version());
    }
}
