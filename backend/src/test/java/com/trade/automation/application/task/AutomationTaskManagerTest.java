package com.trade.automation.application.task;

import com.trade.automation.domain.model.AutomationLoopDefinition;
import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.automation.domain.model.AutomationTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void completingAnOldExecutionAfterRestartCannotCreateAnotherSchedulingChain() throws Exception {
        TaskScheduler controlledScheduler = mock(TaskScheduler.class);
        List<Runnable> scheduled = new CopyOnWriteArrayList<>();
        when(controlledScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        AutomationTaskManager controlledManager = new AutomationTaskManager(controlledScheduler);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        controlledManager.register(new AutomationTaskDefinition("sample", "Sample", false, null, null,
                List.of(new AutomationLoopDefinition("loop", Duration.ZERO, Duration.ofSeconds(10), () -> {
                    if (runs.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test execution was not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                }))));
        controlledManager.start("sample");
        Runnable oldCallback = scheduled.getFirst();
        Thread oldExecution = new Thread(oldCallback);
        oldExecution.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            controlledManager.stop("sample");
            controlledManager.start("sample");
            assertEquals(2, scheduled.size());
            release.countDown();
            oldExecution.join(2_000);
            assertFalse(oldExecution.isAlive());
            assertEquals(2, scheduled.size(), "the old execution must not reschedule after restart");

            oldCallback.run();
            assertEquals(1, runs.get(), "a stale queued callback must not execute the new generation");
            scheduled.get(1).run();
            assertEquals(2, runs.get());
            assertEquals(3, scheduled.size(), "only the new generation schedules its next run");
        } finally {
            release.countDown();
            oldExecution.join(2_000);
            controlledManager.destroy();
        }
    }

    @Test
    void stoppingDuringStartupWaitsForStartupLifecycleToFinish() throws Exception {
        TaskScheduler controlledScheduler = mock(TaskScheduler.class);
        when(controlledScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(ignored -> mock(ScheduledFuture.class));
        AutomationTaskManager controlledManager = new AutomationTaskManager(controlledScheduler);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> lifecycle = new CopyOnWriteArrayList<>();
        controlledManager.register(new AutomationTaskDefinition("sample", "Sample", false, () -> {
            lifecycle.add("start-enter");
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test startup was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            lifecycle.add("start-exit");
        }, () -> lifecycle.add("stop"), List.of()));
        Thread starter = new Thread(() -> controlledManager.start("sample"));
        Thread stopper = new Thread(() -> controlledManager.stop("sample"));
        starter.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            stopper.start();
            waitUntil(() -> stopper.getState() == Thread.State.BLOCKED || lifecycle.contains("stop"));
            release.countDown();
            starter.join(2_000);
            stopper.join(2_000);
            assertFalse(starter.isAlive());
            assertFalse(stopper.isAlive());
            assertEquals(List.of("start-enter", "start-exit", "stop"), lifecycle);
            assertFalse(controlledManager.status("sample").running());
        } finally {
            release.countDown();
            starter.join(2_000);
            if (stopper.getState() != Thread.State.NEW) {
                stopper.join(2_000);
            }
            controlledManager.destroy();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
