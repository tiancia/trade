package com.trade.textgame.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.domain.TextGameStoryValidator;
import com.trade.textgame.model.TextGameAdminApi;
import com.trade.textgame.support.InMemoryTextGameMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextGameAdminServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryTextGameMapper mapper = new InMemoryTextGameMapper();
    private TextGameAdminService service;
    private JsonNode story;

    @BeforeEach
    void setUp() throws Exception {
        service = new TextGameAdminService(mapper, objectMapper, new TextGameStoryValidator());
        story = objectMapper.readTree(new ClassPathResource("textgame/stories/100-days-comeback.v1.json")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void draftRevisionConflictsAndPublishedVersionsAreImmutable() {
        TextGameAdminApi.VersionDocument draft = service.createDraft("100-days-comeback",
                new TextGameAdminApi.CreateDraftRequest(1, story));
        assertEquals("DRAFT", draft.status());

        TextGameAdminApi.VersionDocument replaced = service.replaceDraft("100-days-comeback", 1,
                new TextGameAdminApi.ReplaceDraftRequest(0L, story));
        assertEquals(1, replaced.revision());
        assertThrows(TextGameConflictException.class, () -> service.replaceDraft("100-days-comeback", 1,
                new TextGameAdminApi.ReplaceDraftRequest(0L, story)));

        TextGameAdminApi.VersionDocument published = service.publish("100-days-comeback", 1,
                new TextGameAdminApi.PublishRequest(1L));
        assertEquals("PUBLISHED", published.status());
        assertThrows(TextGameConflictException.class, () -> service.replaceDraft("100-days-comeback", 1,
                new TextGameAdminApi.ReplaceDraftRequest(2L, story)));

        service.createDraft("100-days-comeback", new TextGameAdminApi.CreateDraftRequest(2, story));
        service.publish("100-days-comeback", 2, new TextGameAdminApi.PublishRequest(0L));
        assertEquals("ARCHIVED", service.getVersion("100-days-comeback", 1).status());
        assertEquals("PUBLISHED", service.getVersion("100-days-comeback", 2).status());
    }
}
