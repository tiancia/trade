package com.trade.trading.infrastructure.config;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns the bounded backtest worker pool and its shutdown lifecycle. */
@Configuration(proxyBeanMethods = false)
public class BacktestExecutorConfiguration {
    private final ExecutorService executor = newExecutor();

    @Bean(name = "backtestExecutor", destroyMethod = "")
    ExecutorService backtestExecutor() { return executor; }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("trading-backtest-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
