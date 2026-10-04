package com.trade.textgame.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.application.port.TextGameSessionStore;
import com.trade.textgame.application.service.TextGameSessionService;
import com.trade.textgame.domain.exception.TextGameConflictException;
import com.trade.textgame.domain.model.TextGameApi;
import com.trade.textgame.domain.rule.TextGameRuleEngine;
import com.trade.textgame.infrastructure.config.TextGameProperties;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(MyBatisTextGameSessionStoreTest.Config.class)
class MyBatisTextGameSessionStoreTest {
    @Autowired TextGameSessionService service;
    @Autowired TextGameSessionStore store;
    @Autowired TextGameMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void prepareDatabase() throws Exception {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE text_game_stories (id BIGINT AUTO_INCREMENT PRIMARY KEY, story_key VARCHAR(100), title VARCHAR(200), summary VARCHAR(500), enabled BOOLEAN, sort_order INT, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE text_game_story_versions (id BIGINT AUTO_INCREMENT PRIMARY KEY, story_id BIGINT, version_number INT, status VARCHAR(30), revision BIGINT, story_json CLOB, checksum VARCHAR(100), published_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE text_game_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(100) NOT NULL UNIQUE, story_version_id BIGINT, current_node_id VARCHAR(100), pending_node_id VARCHAR(100), phase VARCHAR(30), attributes_json CLOB, relations_json CLOB, flags_json CLOB, history_json CLOB, result_json CLOB, revision BIGINT, expires_at TIMESTAMP, completed_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE text_game_session_events (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(100), sequence_no INT, node_id VARCHAR(100), choice_id VARCHAR(100), effects_json CLOB, state_after_json CLOB, created_at TIMESTAMP, UNIQUE(session_id, sequence_no))");
        var story = new TextGameStoryRow().setStoryKey("100-days-comeback").setTitle("100天翻身")
                .setSummary("summary").setEnabled(true).setSortOrder(1);
        mapper.insertStory(story);
        mapper.insertVersion(new TextGameVersionRow().setStoryId(story.getId()).setVersionNumber(1)
                .setStatus("PUBLISHED").setRevision(0).setChecksum("v1")
                .setStoryJson(new ClassPathResource("textgame/stories/100-days-comeback.v1.json").getContentAsString(StandardCharsets.UTF_8))
                .setPublishedAt(Timestamp.from(Instant.now())));
    }

    @Test
    void databaseGeneratesIdsWhileSessionsRemainAddressableByUniqueUuid() {
        var first = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        var second = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        Long firstId = jdbc.queryForObject("SELECT id FROM text_game_sessions WHERE session_id=?", Long.class, first.sessionId());
        Long secondId = jdbc.queryForObject("SELECT id FROM text_game_sessions WHERE session_id=?", Long.class, second.sessionId());
        assertNotNull(firstId);
        assertNotNull(secondId);
        assertTrue(firstId > 0 && secondId > firstId);
        assertEquals(first, service.getSession(first.sessionId()));
        assertEquals(second, service.getSession(second.sessionId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> mapper.insertSession(mapper.findSession(first.sessionId())));
        service.submitChoice(first.sessionId(), new TextGameApi.SubmitChoiceRequest("start_skill", 0L));
        assertEquals(first.sessionId(), mapper.listSessionEvents(first.sessionId()).getFirst().getSessionId());
    }

    @Test
    void resultRoundTripsAndStalePersistenceCannotOverwriteNewChoice() throws Exception {
        var created = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        var stale = store.find(created.sessionId()).orElseThrow();
        var result = service.submitChoice(created.sessionId(), new TextGameApi.SubmitChoiceRequest("start_skill", 0L));
        assertEquals(result, service.getSession(created.sessionId()));
        assertThrows(TextGameConflictException.class, () -> store.update(stale, stale.expiresAt()));
        assertEquals(result, service.getSession(created.sessionId()));
        var event = mapper.listSessionEvents(created.sessionId()).getFirst();
        assertEquals(json.readTree(mapper.findSession(created.sessionId()).getResultJson()).path("effects"), json.readTree(event.getEffectsJson()));
        assertEquals("start_skill", json.readTree(event.getStateAfterJson()).path("history").get(0).asText());
    }

    @Test
    void eventInsertFailureRollsBackSessionRevisionAndState() {
        var created = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        mapper.insertSessionEvent(new TextGameSessionEventRow().setSessionId(created.sessionId()).setSequenceNo(1)
                .setNodeId("reserved").setChoiceId("reserved").setEffectsJson("{}").setStateAfterJson("{}"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> service.submitChoice(
                created.sessionId(), new TextGameApi.SubmitChoiceRequest("start_skill", 0L)));
        assertEquals(created, service.getSession(created.sessionId()));
        assertEquals(1, mapper.listSessionEvents(created.sessionId()).size());
    }

    @Test
    void readsExistingSaveFormatAndReportsCorruptionWithoutOverwriting() {
        var created = service.createSession(new TextGameApi.CreateSessionRequest("100-days-comeback"));
        String legacyResult = "{\"choiceId\":\"old-choice\",\"text\":[\"旧结果\"],\"effects\":{\"attributeDelta\":{\"skill\":1},\"relationDelta\":{},\"flagChanges\":{\"seen\":true}}}";
        jdbc.update("UPDATE text_game_sessions SET phase='result', pending_node_id='day15_skill', result_json=?, history_json=? WHERE session_id=?",
                legacyResult, "[\"old-choice\"]", created.sessionId());
        var restored = service.getSession(created.sessionId());
        assertEquals("old-choice", restored.result().choiceId());
        assertEquals(1, restored.result().effects().attributes().get("skill"));
        assertEquals(true, restored.result().effects().flags().get("seen"));
        jdbc.update("UPDATE text_game_sessions SET attributes_json='broken' WHERE session_id=?", created.sessionId());
        assertEquals("文字游戏存档数据损坏", assertThrows(IllegalStateException.class,
                () -> service.getSession(created.sessionId())).getMessage());
        assertEquals("broken", mapper.findSession(created.sessionId()).getAttributesJson());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:session_store;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var bean = new SqlSessionFactoryBean();
            bean.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            bean.setConfiguration(configuration);
            bean.setMapperLocations(new ClassPathResource("mapper/textgame/TextGameMapper.xml"));
            return bean.getObject();
        }
        @Bean TextGameMapper mapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(TextGameMapper.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean TextGameSessionStore store(TextGameMapper mapper, ObjectMapper json) { return new MyBatisTextGameSessionStore(mapper, json); }
        @Bean TextGameSessionService service(TextGameSessionStore store, ObjectMapper json) {
            return new TextGameSessionService(store, json, new TextGameRuleEngine(), new TextGameProperties());
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
}
