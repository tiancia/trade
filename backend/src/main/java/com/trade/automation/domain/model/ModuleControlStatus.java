package com.trade.automation.domain.model;

import java.util.List;

public record ModuleControlStatus(String id, String name, String description, boolean enabled,
                                  Boolean savedEnabled, AutomationTaskStatus task,
                                  List<String> notices, String error) {}
