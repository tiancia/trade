package com.trade.automation.domain.model;

import java.util.List;

public record AutomationTaskDefinition(
        String id,
        String name,
        boolean autoStart,
        Runnable onStart,
        Runnable onStop,
        List<AutomationLoopDefinition> loops
) {
}
