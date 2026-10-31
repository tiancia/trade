package com.trade.automation.infrastructure.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ModuleSwitchMapperTest {
    @Test void choicesSurviveNewSessionsAndUpdatesDoNotCreateDuplicateModules() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:switches_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__module_switches.sql")).execute(data);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(data);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(ModuleSwitchMapper.class);
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(ModuleSwitchMapper.class);
            assertTrue(mapper.findAll().isEmpty());
            mapper.save("story", true); mapper.save("story", false); mapper.save("trading-ai", true);
        }
        try (var session = sessions.openSession(true)) {
            var choices = session.getMapper(ModuleSwitchMapper.class).findAll();
            assertEquals(2, choices.size());
            assertEquals("story", choices.getFirst().id()); assertFalse(choices.getFirst().enabled());
            assertTrue(choices.getLast().enabled());
        }
    }
}
