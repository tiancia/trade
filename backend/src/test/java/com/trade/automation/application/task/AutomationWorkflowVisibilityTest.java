package com.trade.automation.application.task;

import com.trade.automation.domain.model.AutomationLoopDefinition;
import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.story.application.service.AiStoryService;
import com.trade.story.domain.model.StoryGenerationResult;
import com.trade.story.interfaces.scheduler.AiStoryScheduler;
import com.trade.weibo.application.service.WeiboPostService;
import com.trade.weibo.interfaces.scheduler.WeiboScheduler;
import com.trade.x.application.service.XPostService;
import com.trade.x.interfaces.scheduler.XScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutomationWorkflowVisibilityTest {
    @Test
    void workflowFailuresAndNormalSkipsReachStatusAndMetrics() {
        AiStoryService story = mock(AiStoryService.class);
        when(story.generateStory()).thenThrow(new IllegalStateException("AI key=secret-quota-response"));
        verifyRun("story", new AiStoryScheduler(story)::runScheduledGeneration, false);
        doReturn(Optional.of(new StoryGenerationResult().setDegraded(true))).when(story).generateStory();
        verifyRun("story", new AiStoryScheduler(story)::runScheduledGeneration, false);

        XPostService x = mock(XPostService.class);
        verifyRun("x", new XScheduler(x)::generate, false);
        verifyRun("x", new XScheduler(x)::publish, false);
        when(x.runGeneration()).thenReturn(true);
        verifyRun("x", new XScheduler(x)::generate, true);

        WeiboPostService weibo = mock(WeiboPostService.class);
        when(weibo.runReviews()).thenThrow(new IllegalStateException("Telegram token=secret-401-response"));
        verifyRun("weibo", new WeiboScheduler(weibo)::review, false);
        verifyRun("weibo", new WeiboScheduler(weibo)::publish, false);
        when(weibo.runPublishing()).thenReturn(true);
        verifyRun("weibo", new WeiboScheduler(weibo)::publish, true);
    }

    private void verifyRun(String task, Runnable action, boolean successful) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> callbacks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutomationTaskManager manager = new AutomationTaskManager(scheduler, registry);
        try {
            manager.register(new AutomationTaskDefinition(task, task, false, null, null,
                    List.of(new AutomationLoopDefinition("workflow", Duration.ZERO, Duration.ofSeconds(1), action))));
            manager.start(task);
            callbacks.getFirst().run();
            var state = manager.status(task).loops().getFirst();
            assertEquals(successful, state.lastRunSuccessful());
            if (successful) assertNull(state.lastError());
            else {
                assertNotNull(state.lastError());
                assertFalse(state.lastError().contains("secret"));
            }
            assertEquals(1, registry.get("trade.automation.runs").tags("task", task, "loop", "workflow",
                    "outcome", successful ? "success" : "failure").counter().count());
            assertEquals(successful ? 1 : 0,
                    registry.get("trade.automation.last.success").tags("task", task, "loop", "workflow").gauge().value());
            assertEquals(1, registry.get("trade.automation.duration").tags("task", task, "loop", "workflow").timer().count());
            assertEquals(2, callbacks.size(), "a failed run must still schedule its next attempt");
        } finally {
            manager.destroy();
            registry.close();
        }
    }
}
