package com.trade.trading.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BacktestExecutorConfigurationTest {
    @Test
    void poolRemainsBoundedAndContextCloseStopsWorkers() throws Exception {
        ThreadPoolExecutor executor;
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var context = new AnnotationConfigApplicationContext(BacktestExecutorConfiguration.class)) {
            executor = context.getBean("backtestExecutor", ThreadPoolExecutor.class);
            try {
                Runnable work = () -> {
                    started.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                };
                executor.execute(work);
                executor.execute(work);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 32; i++) { executor.execute(() -> {}); }
                assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            } finally { release.countDown(); }
        }
        assertTrue(executor.isTerminated());
    }
}
