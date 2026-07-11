/**
 * Background task orchestration for long-running backend loops.
 *
 * <p>This package does not contain trading or generation business rules. It
 * registers, starts, stops, and reports status for loops owned by other
 * domains. Start with
 * {@link com.trade.automation.web.AutomationTaskController} for the operational
 * API, {@link com.trade.automation.application.AutomationTaskRegistrar} for the
 * domain-to-task map, and
 * {@link com.trade.automation.application.AutomationTaskManager} for lifecycle
 * behavior.</p>
 */
package com.trade.automation;
