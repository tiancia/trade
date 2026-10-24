package com.trade.automation.application.task;

import com.trade.automation.domain.model.AutomationLoopDefinition;
import com.trade.automation.domain.model.AutomationLoopStatus;
import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.automation.domain.model.AutomationTaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runtime registry for background automation loops such as trading schedulers.
 * Each registered task can contain multiple loops, but loops in the same task
 * are serialized to avoid overlapping side effects.
 */
@Component
public class AutomationTaskManager implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(AutomationTaskManager.class);

    private final TaskScheduler scheduler;
    private final MeterRegistry meterRegistry;
    private final Map<String, ManagedTask> tasks = new LinkedHashMap<>();

    public AutomationTaskManager(TaskScheduler scheduler) {
        this(scheduler, new SimpleMeterRegistry());
    }

    @Autowired
    public AutomationTaskManager(@Qualifier("automationTaskScheduler") TaskScheduler scheduler,
                                 MeterRegistry meterRegistry) {
        this.scheduler = scheduler;
        this.meterRegistry = meterRegistry;
    }

    public synchronized void register(AutomationTaskDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        String taskId = normalize(definition.id());
        if (tasks.containsKey(taskId)) {
            throw new IllegalArgumentException("Duplicate automation task: " + taskId);
        }
        tasks.put(taskId, new ManagedTask(definition));
        log.info("Registered automation task: id={}, autoStart={}", taskId, definition.autoStart());
    }

    public List<AutomationTaskStatus> statuses() {
        synchronized (this) {
            return tasks.values().stream()
                    .sorted(Comparator.comparing(task -> task.definition.id()))
                    .map(ManagedTask::status)
                    .toList();
        }
    }

    public AutomationTaskStatus status(String taskId) {
        return task(taskId).status();
    }

    public AutomationTaskStatus start(String taskId) {
        ManagedTask task = task(taskId);
        task.start();
        return task.status();
    }

    public AutomationTaskStatus stop(String taskId) {
        ManagedTask task = task(taskId);
        task.stop();
        return task.status();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAutoTasks() {
        List<ManagedTask> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(tasks.values());
        }
        for (ManagedTask task : snapshot) {
            if (task.definition.autoStart()) {
                task.start();
            }
        }
    }

    @Override
    public void destroy() {
        List<ManagedTask> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(tasks.values());
        }
        for (ManagedTask task : snapshot) {
            task.stop();
        }
    }

    private synchronized ManagedTask task(String taskId) {
        ManagedTask task = tasks.get(normalize(taskId));
        if (task == null) {
            throw new IllegalArgumentException("Unknown automation task: " + taskId);
        }
        return task;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Automation task id must not be blank");
        }
        return value.trim().toLowerCase();
    }

    private final class ManagedTask {
        private final AutomationTaskDefinition definition;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong generation = new AtomicLong();
        // Shared across loops in one task so a slow loop cannot overlap with
        // another loop that touches the same external account or local state.
        private final ReentrantLock executionLock = new ReentrantLock();
        private final Map<String, ManagedLoop> loops = new LinkedHashMap<>();

        private ManagedTask(AutomationTaskDefinition definition) {
            this.definition = definition;
            for (AutomationLoopDefinition loop : definition.loops()) {
                loops.put(loop.id(), new ManagedLoop(loop));
            }
        }

        private synchronized void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            long startedGeneration = generation.incrementAndGet();
            try {
                if (definition.onStart() != null) {
                    definition.onStart().run();
                }
                for (ManagedLoop loop : loops.values()) {
                    loop.schedule(loop.definition.initialDelay(), startedGeneration);
                }
                log.info("Automation task started: id={}", definition.id());
            } catch (RuntimeException e) {
                running.set(false);
                generation.incrementAndGet();
                cancelLoops();
                throw e;
            }
        }

        private synchronized void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            generation.incrementAndGet();
            cancelLoops();
            if (definition.onStop() != null) {
                definition.onStop().run();
            }
            log.info("Automation task stopped: id={}", definition.id());
        }

        private void cancelLoops() {
            for (ManagedLoop loop : loops.values()) {
                loop.cancel();
            }
        }

        private boolean isCurrent(long expectedGeneration) {
            return running.get() && generation.get() == expectedGeneration;
        }

        private AutomationTaskStatus status() {
            return new AutomationTaskStatus(
                    definition.id(),
                    definition.name(),
                    running.get(),
                    definition.autoStart(),
                    loops.values().stream().map(ManagedLoop::status).toList()
            );
        }

        private final class ManagedLoop {
            private final AutomationLoopDefinition definition;
            private ScheduledFuture<?> future;
            private Instant nextRunAt;
            private Instant lastRunStartedAt;
            private Instant lastRunCompletedAt;
            private Boolean lastRunSuccessful;
            private String lastError;
            private final Counter succeeded;
            private final Counter failed;
            private final Counter skipped;
            private final Timer duration;
            private final AtomicLong lastSuccess = new AtomicLong(-1);

            private ManagedLoop(AutomationLoopDefinition definition) {
                this.definition = definition;
                String task = ManagedTask.this.definition.id();
                succeeded = runCounter(task, definition.id(), "success");
                failed = runCounter(task, definition.id(), "failure");
                skipped = runCounter(task, definition.id(), "skipped");
                duration = Timer.builder("trade.automation.duration")
                        .tags("task", task, "loop", definition.id()).register(meterRegistry);
                Gauge.builder("trade.automation.last.success", lastSuccess, AtomicLong::get)
                        .description("Last completed run: 1 success, 0 failure, -1 not run")
                        .tags("task", task, "loop", definition.id()).register(meterRegistry);
            }

            private synchronized void schedule(Duration delay, long expectedGeneration) {
                if (!isCurrent(expectedGeneration)) {
                    return;
                }
                Duration normalizedDelay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
                nextRunAt = Instant.now().plus(normalizedDelay);
                future = scheduler.schedule(() -> runAndReschedule(expectedGeneration), nextRunAt);
            }

            private void runAndReschedule(long expectedGeneration) {
                if (!isCurrent(expectedGeneration)) {
                    return;
                }

                // Skip this tick when the previous one is still active. This
                // keeps scheduler delay bounded without building a backlog.
                boolean locked = executionLock.tryLock();
                if (!locked) {
                    skipped.increment();
                    schedule(definition.fixedDelay(), expectedGeneration);
                    return;
                }

                long started = System.nanoTime();
                try {
                    if (!startRun(expectedGeneration)) {
                        return;
                    }
                    definition.action().run();
                    complete(true, null, expectedGeneration);
                } catch (Exception e) {
                    // Provider exception messages may contain credentials, request bodies or URLs.
                    log.error("Automation task loop failed: task={}, loop={}, failureType={}",
                            ManagedTask.this.definition.id(), definition.id(), e.getClass().getSimpleName());
                    complete(false, "Loop failed (" + e.getClass().getSimpleName()
                            + "); inspect task logs and persisted workflow state", expectedGeneration);
                } finally {
                    duration.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    executionLock.unlock();
                    // An execution from a stopped generation must not revive its old scheduling chain.
                    schedule(definition.fixedDelay(), expectedGeneration);
                }
            }

            private synchronized boolean startRun(long expectedGeneration) {
                if (!isCurrent(expectedGeneration)) {
                    return false;
                }
                nextRunAt = null;
                lastRunStartedAt = Instant.now();
                return true;
            }

            private synchronized void complete(boolean successful, String error, long expectedGeneration) {
                if (!isCurrent(expectedGeneration)) {
                    return;
                }
                lastRunCompletedAt = Instant.now();
                lastRunSuccessful = successful;
                lastError = error;
                lastSuccess.set(successful ? 1 : 0);
                (successful ? succeeded : failed).increment();
            }

            private synchronized void cancel() {
                if (future != null) {
                    future.cancel(false);
                }
                future = null;
                nextRunAt = null;
            }

            private synchronized AutomationLoopStatus status() {
                return new AutomationLoopStatus(
                        definition.id(),
                        definition.initialDelay().toMillis(),
                        definition.fixedDelay().toMillis(),
                        nextRunAt,
                        lastRunStartedAt,
                        lastRunCompletedAt,
                        lastRunSuccessful,
                        lastError
                );
            }
        }
    }

    private Counter runCounter(String task, String loop, String outcome) {
        return Counter.builder("trade.automation.runs")
                .description("Completed automation loop runs and overlap skips")
                .tags("task", task, "loop", loop, "outcome", outcome).register(meterRegistry);
    }
}
