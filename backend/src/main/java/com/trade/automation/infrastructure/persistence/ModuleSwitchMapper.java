package com.trade.automation.infrastructure.persistence;

import com.trade.automation.domain.model.ModuleSwitch;
import com.trade.automation.port.ModuleSwitchStore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ModuleSwitchMapper extends ModuleSwitchStore {
    @Override
    @Select("SELECT module_id AS id, enabled FROM automation_module_switch ORDER BY module_id")
    List<ModuleSwitch> findAll();

    @Override
    @Insert("""
            INSERT INTO automation_module_switch(module_id, enabled, updated_at)
            VALUES(#{id}, #{enabled}, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP
            """)
    void save(@Param("id") String id, @Param("enabled") boolean enabled);
}
