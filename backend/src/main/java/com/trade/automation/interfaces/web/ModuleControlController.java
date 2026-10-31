package com.trade.automation.interfaces.web;

import com.trade.automation.application.task.ModuleControlService;
import com.trade.automation.domain.model.ModuleControlStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/automation/modules")
public class ModuleControlController {
    private final ModuleControlService service;
    public ModuleControlController(ModuleControlService service) { this.service = service; }

    @GetMapping
    public List<ModuleControlStatus> statuses() { return service.statuses(); }

    @PutMapping("/{id}")
    public ModuleControlStatus update(@PathVariable String id, @RequestBody UpdateModule request) {
        return service.setEnabled(id, request.enabled());
    }

    public record UpdateModule(Boolean enabled) {
        public UpdateModule {
            if (enabled == null) throw new IllegalArgumentException("enabled 必须为 true 或 false");
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> invalid(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
