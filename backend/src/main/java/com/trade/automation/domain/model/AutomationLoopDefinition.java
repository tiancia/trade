package com.trade.automation.domain.model;

import java.time.Duration;

public record AutomationLoopDefinition(
        String id,
        Duration initialDelay,
        Duration fixedDelay,
        Runnable action
) {
}
