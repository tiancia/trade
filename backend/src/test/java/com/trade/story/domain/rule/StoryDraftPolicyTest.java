package com.trade.story.domain.rule;

import com.trade.story.domain.model.StorySectionDraft;
import com.trade.story.domain.model.StorySectionPlan;
import com.trade.story.domain.model.StoryTopicPlan;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StoryDraftPolicyTest {
    @Test
    void normalizesSectionCountWithoutMutatingOriginalPlan() {
        var original = new StorySectionPlan().setTitle("Opening").setTargetChars(2200);
        var plan = new StoryTopicPlan().setSectionPlans(List.of(original)).setOutline(List.of("one", "two"));
        var sections = new StoryDraftPolicy(2, 5000).normalizedSectionPlans(plan);
        assertEquals(2, sections.size());
        assertEquals(2200, sections.get(0).getTargetChars());
        assertEquals(2500, sections.get(1).getTargetChars());
        assertEquals("two", sections.get(1).getSummary());
        assertNotSame(original, sections.get(0));
    }

    @Test
    void resolvedLoopsDisappearAndContinuityRemainsOrderedAndUnique() {
        var first = new StorySectionDraft().setOpenLoops(List.of(" key ", "door"))
                .setContinuityNotes(List.of("note", "note"));
        var second = new StorySectionDraft().setOpenLoops(List.of("letter"))
                .setResolvedLoops(List.of("key")).setContinuityNotes(List.of("second"));
        assertEquals(List.of("door", "letter"), StoryDraftPolicy.activeOpenLoops(List.of(first, second)));
        assertEquals(List.of("note", "second"), StoryDraftPolicy.continuityNotes(List.of(first, second)));
    }

    @Test
    void lengthBudgetAndContinuationHaveExplicitBounds() {
        var policy = new StoryDraftPolicy(2, 5000);
        assertEquals(1800, policy.sectionTargetChars(4999, 1, 0));
        assertEquals(3500, policy.sectionTargetChars(0, 1, 10000));
        assertTrue(StoryDraftPolicy.shouldContinue(999, 1000, 0, 2));
        assertFalse(StoryDraftPolicy.shouldContinue(1000, 1000, 0, 2));
        assertFalse(StoryDraftPolicy.shouldContinue(999, 1000, 2, 2));
        assertEquals(3, StoryDraftPolicy.countNonWhitespace("中 文\n😀\t"));
    }
}
