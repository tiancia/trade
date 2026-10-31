package com.trade.automation.application.task;

import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.automation.domain.model.ModuleSwitch;
import com.trade.automation.port.ModuleSwitchStore;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleControlServiceTest {
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final ModuleSwitchStore store = mock(ModuleSwitchStore.class);
    private final AutomationTaskManager tasks = new AutomationTaskManager(mock(TaskScheduler.class));
    private final ModuleControl control = new ModuleControl("story", "Story", "Generate", enabled::get,
            enabled::set, () -> {}, List::of);

    private ModuleControlService service(boolean autoStart, Runnable start) {
        tasks.register(new AutomationTaskDefinition("story", "Story", autoStart, start, null, List.of()));
        return new ModuleControlService(tasks, store, List.of(control));
    }

    @Test void enablingAndDisablingUpdatesTheBusinessGateAndTaskAndPersistsBothChoices() {
        var service = service(false, null);
        var active = service.setEnabled("story", true);
        assertTrue(enabled.get()); assertTrue(active.task().running()); assertTrue(active.task().autoStart());
        assertEquals(Boolean.TRUE, active.savedEnabled());
        var stopped = service.setEnabled("story", false);
        assertFalse(enabled.get()); assertFalse(stopped.task().running()); assertFalse(stopped.task().autoStart());
        verify(store).save("story", true); verify(store).save("story", false);
    }

    @Test void savedOffPreventsConfiguredAutoStartOnRestart() {
        enabled.set(true);
        var service = service(true, null);
        when(store.findAll()).thenReturn(List.of(new ModuleSwitch("story", false)));
        service.restore(); tasks.startAutoTasks();
        assertFalse(enabled.get()); assertFalse(tasks.status("story").running());
    }

    @Test void savedOnRestoresDisabledModuleBeforeAutoStart() {
        var service = service(false, () -> assertTrue(enabled.get()));
        when(store.findAll()).thenReturn(List.of(new ModuleSwitch("story", true)));
        service.restore();
        assertFalse(tasks.status("story").running());
        tasks.startAutoTasks(); assertTrue(tasks.status("story").running());
    }

    @Test void failedDatabaseWriteLeavesRuntimeUntouchedAndUnknownIdsCannotBeSaved() {
        var service = service(false, null);
        doThrow(new IllegalStateException("database unavailable")).when(store).save("story", true);
        assertThrows(IllegalStateException.class, () -> service.setEnabled("story", true));
        assertFalse(enabled.get()); assertFalse(tasks.status("story").running());
        assertNull(service.statuses().getFirst().savedEnabled());
        assertThrows(IllegalArgumentException.class, () -> service.setEnabled("live-execution", true));
        verify(store, never()).save("live-execution", true);
    }

    @Test void failedStartIsVisibleAndDoesNotPretendTheModuleIsRunning() {
        var service = service(false, () -> { throw new IllegalStateException("provider secret must not leak"); });
        var result = service.setEnabled("story", true);
        assertEquals(Boolean.TRUE, result.savedEnabled());
        assertFalse(result.enabled()); assertFalse(result.task().running());
        assertNotNull(result.error()); assertFalse(result.error().contains("provider secret"));
    }

    @Test void invalidSavedActivationStaysStoppedInsteadOfStartingWithInvalidConfiguration() {
        tasks.register(new AutomationTaskDefinition("story", "Story", true, null, null, List.of()));
        var blocked = new ModuleControl("story", "Story", "Generate", enabled::get, enabled::set,
                () -> { throw new IllegalStateException("missing configuration"); }, List::of);
        var service = new ModuleControlService(tasks, store, List.of(blocked));
        when(store.findAll()).thenReturn(List.of(new ModuleSwitch("story", true)));
        service.restore(); tasks.startAutoTasks();
        assertFalse(enabled.get()); assertFalse(tasks.status("story").running());
        assertNotNull(service.statuses().getFirst().error());
    }
}
