package com.trade.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationTaskManagerTest {
    private ThreadPoolTaskScheduler scheduler;
    private AutomationTaskManager manager;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("automation-test-");
        scheduler.initialize();
        manager = new AutomationTaskManager(scheduler);
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
        scheduler.shutdown();
    }

    @Test
    void startSchedulesLoopAndStopCancelsNextRun() throws InterruptedException {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();

        manager.register(new AutomationTaskDefinition(
                "sample",
                "Sample task",
                false,
                starts::incrementAndGet,
                stops::incrementAndGet,
                List.of(new AutomationLoopDefinition(
                        "loop",
                        Duration.ZERO,
                        Duration.ofSeconds(10),
                        runs::incrementAndGet
                ))
        ));

        AutomationTaskStatus started = manager.start("sample");

        assertTrue(started.running());
        assertEquals(1, starts.get());
        waitUntil(() -> runs.get() == 1);

        AutomationTaskStatus stopped = manager.stop("sample");

        assertFalse(stopped.running());
        assertEquals(1, stops.get());
        Thread.sleep(100);
        assertEquals(1, runs.get());
    }

    @Test
    void unknownTaskCannotBeControlled() {
        assertThrows(IllegalArgumentException.class, () -> manager.start("missing"));
        assertThrows(IllegalArgumentException.class, () -> manager.stop("missing"));
        assertThrows(IllegalArgumentException.class, () -> manager.status("missing"));
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
