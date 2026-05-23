package com.trade.automation.application;

import com.trade.automation.model.AutomationLoopDefinition;
import com.trade.automation.model.AutomationLoopStatus;
import com.trade.automation.model.AutomationTaskDefinition;
import com.trade.automation.model.AutomationTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.locks.ReentrantLock;

@Component
public class AutomationTaskManager implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(AutomationTaskManager.class);

    private final TaskScheduler scheduler;
    private final Map<String, ManagedTask> tasks = new LinkedHashMap<>();

    public AutomationTaskManager(@Qualifier("automationTaskScheduler") TaskScheduler scheduler) {
        this.scheduler = scheduler;
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
        private final ReentrantLock executionLock = new ReentrantLock();
        private final Map<String, ManagedLoop> loops = new LinkedHashMap<>();

        private ManagedTask(AutomationTaskDefinition definition) {
            this.definition = definition;
            for (AutomationLoopDefinition loop : definition.loops()) {
                loops.put(loop.id(), new ManagedLoop(loop));
            }
        }

        private void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            try {
                if (definition.onStart() != null) {
                    definition.onStart().run();
                }
                for (ManagedLoop loop : loops.values()) {
                    loop.schedule(loop.definition.initialDelay());
                }
                log.info("Automation task started: id={}", definition.id());
            } catch (RuntimeException e) {
                running.set(false);
                cancelLoops();
                throw e;
            }
        }

        private void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
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

            private ManagedLoop(AutomationLoopDefinition definition) {
                this.definition = definition;
            }

            private synchronized void schedule(Duration delay) {
                if (!running.get()) {
                    return;
                }
                Duration normalizedDelay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
                nextRunAt = Instant.now().plus(normalizedDelay);
                future = scheduler.schedule(this::runAndReschedule, nextRunAt);
            }

            private void runAndReschedule() {
                if (!running.get()) {
                    return;
                }

                boolean locked = executionLock.tryLock();
                if (!locked) {
                    complete(false, "Another loop in the same task is still running");
                    schedule(definition.fixedDelay());
                    return;
                }

                startRun();
                try {
                    definition.action().run();
                    complete(true, null);
                } catch (Exception e) {
                    log.error("Automation task loop failed: task={}, loop={}", ManagedTask.this.definition.id(), definition.id(), e);
                    complete(false, e.getMessage());
                } finally {
                    executionLock.unlock();
                    schedule(definition.fixedDelay());
                }
            }

            private synchronized void startRun() {
                nextRunAt = null;
                lastRunStartedAt = Instant.now();
            }

            private synchronized void complete(boolean successful, String error) {
                lastRunCompletedAt = Instant.now();
                lastRunSuccessful = successful;
                lastError = error;
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
}
