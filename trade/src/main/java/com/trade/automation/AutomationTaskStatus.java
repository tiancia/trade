package com.trade.automation;

import java.util.List;

public record AutomationTaskStatus(
        String id,
        String name,
        boolean running,
        boolean autoStart,
        List<AutomationLoopStatus> loops
) {
}
