package com.trade.automation.model;

import java.util.List;

public record AutomationTaskStatus(
        String id,
        String name,
        boolean running,
        boolean autoStart,
        List<AutomationLoopStatus> loops
) {
}
