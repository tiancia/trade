package com.trade.automation.web;

import com.trade.automation.application.AutomationTaskManager;
import com.trade.automation.model.AutomationTaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/automation/tasks")
public class AutomationTaskController {
    private final AutomationTaskManager taskManager;

    public AutomationTaskController(AutomationTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @GetMapping
    public List<AutomationTaskStatus> statuses() {
        return taskManager.statuses();
    }

    @GetMapping("/{taskId}")
    public AutomationTaskStatus status(@PathVariable String taskId) {
        return taskManager.status(taskId);
    }

    @PostMapping("/{taskId}/start")
    public AutomationTaskStatus start(@PathVariable String taskId) {
        return taskManager.start(taskId);
    }

    @PostMapping("/{taskId}/stop")
    public AutomationTaskStatus stop(@PathVariable String taskId) {
        return taskManager.stop(taskId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }
}
