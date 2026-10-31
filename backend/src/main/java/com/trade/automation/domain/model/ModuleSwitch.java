package com.trade.automation.domain.model;

/** A durable operator choice; absence means use startup configuration. */
public record ModuleSwitch(String id, boolean enabled) {}
