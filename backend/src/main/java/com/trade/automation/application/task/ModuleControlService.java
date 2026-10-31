package com.trade.automation.application.task;

import com.trade.automation.domain.model.ModuleControlStatus;
import com.trade.automation.port.ModuleSwitchStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModuleControlService {
    private static final Logger log = LoggerFactory.getLogger(ModuleControlService.class);
    private final AutomationTaskManager tasks;
    private final ModuleSwitchStore store;
    private final List<ModuleControl> controls;
    private final Map<String, Boolean> saved = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();

    public ModuleControlService(AutomationTaskManager tasks, ModuleSwitchStore store, List<ModuleControl> controls) {
        this.tasks = tasks;
        this.store = store;
        this.controls = List.copyOf(controls);
    }

    /** Restore choices before the manager starts any background task. DB failure blocks startup. */
    @Order(-100)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void restore() {
        store.findAll().forEach(choice -> saved.put(choice.id(), choice.enabled()));
        // Disable first, so moving the shared Telegram consumer from X to Weibo is deterministic.
        for (ModuleControl control : controls) {
            if (Boolean.FALSE.equals(saved.get(control.id()))) apply(control, false, false);
        }
        for (ModuleControl control : controls) {
            if (Boolean.TRUE.equals(saved.get(control.id()))) apply(control, true, false);
        }
    }

    public synchronized List<ModuleControlStatus> statuses() {
        return controls.stream().map(this::status).toList();
    }

    public synchronized ModuleControlStatus setEnabled(String id, boolean enabled) {
        ModuleControl control = controls.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown module: " + id));
        if (enabled) control.validateEnable().run();
        // Commit the choice before changing runtime state. A failed write leaves runtime untouched.
        store.save(id, enabled);
        saved.put(id, enabled);
        apply(control, enabled, true);
        return status(control);
    }

    private void apply(ModuleControl control, boolean enabled, boolean updateTask) {
        String id = control.id();
        try {
            if (enabled) control.validateEnable().run();
            control.changeEnabled().accept(enabled);
            tasks.overrideAutoStart(id, enabled);
            if (updateTask) {
                if (enabled) tasks.start(id);
                else tasks.stop(id);
            }
            errors.remove(id);
        } catch (RuntimeException failure) {
            // Do not silently report success, automatically retry side effects, or expose provider secrets.
            control.changeEnabled().accept(false);
            tasks.overrideAutoStart(id, false);
            errors.put(id, "设置已保存，但运行状态切换失败。请检查模块配置和后端日志后重试。");
            log.warn("Module transition failed: module={}, enabled={}, failureType={}", id, enabled, failure.getClass().getSimpleName());
            try { tasks.stop(id); }
            catch (RuntimeException cleanupFailure) {
                log.warn("Module cleanup failed: module={}, failureType={}", id, cleanupFailure.getClass().getSimpleName());
            }
        }
    }

    private ModuleControlStatus status(ModuleControl control) {
        return new ModuleControlStatus(control.id(), control.name(), control.description(),
                control.enabled().getAsBoolean(), saved.get(control.id()), tasks.status(control.id()),
                control.notices().get(), errors.get(control.id()));
    }
}
