package com.trade.automation.port;

import com.trade.automation.domain.model.ModuleSwitch;
import java.util.List;

public interface ModuleSwitchStore {
    List<ModuleSwitch> findAll();
    void save(String id, boolean enabled);
}
