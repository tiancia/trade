package com.trade.marketplace.infrastructure.persistence;

import com.trade.marketplace.application.service.MarketplaceChatService;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.port.MarketplaceItemStore;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringJUnitConfig(MarketplacePersistenceTest.Config.class)
class MarketplacePersistenceTest {
    @MockitoSpyBean private MarketplaceMapper mapper;
    @Autowired private SqlSessionFactory factory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MarketplaceChatService chat;
    @Autowired private MarketplaceItemStore items;

    @Test
    void generatedSessionIdsPreserveTokenLookupUniquenessAndRevocation() {
        var user = new MarketplaceUserRow().setUsername("session-test").setPasswordHash("offline-test-hash")
                .setDisplayName("Session test");
        mapper.insertUser(user);
        Timestamp now = Timestamp.from(Instant.parse("2026-10-04T00:00:00Z"));
        Timestamp expires = Timestamp.from(Instant.parse("2026-10-05T00:00:00Z"));
        var first = session("a".repeat(64), user.getId(), now, expires);
        var second = session("b".repeat(64), user.getId(), now, expires);
        mapper.insertSession(first);
        mapper.insertSession(second);

        Long firstId = jdbc.queryForObject("SELECT id FROM marketplace_sessions WHERE token_hash=?", Long.class, first.getTokenHash());
        Long secondId = jdbc.queryForObject("SELECT id FROM marketplace_sessions WHERE token_hash=?", Long.class, second.getTokenHash());
        assertNotNull(firstId);
        assertNotNull(secondId);
        assertTrue(firstId > 0 && secondId > firstId);
        assertEquals(user.getId(), mapper.findSessionByTokenHash(first.getTokenHash()).getUserId());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> mapper.insertSession(first));
        assertEquals(1, mapper.revokeSession(first.getTokenHash(), now));
        assertEquals(0, mapper.revokeSession(first.getTokenHash(), now));
        assertEquals(now, mapper.findSessionByTokenHash(first.getTokenHash()).getRevokedAt());
        assertNull(mapper.findSessionByTokenHash(second.getTokenHash()).getRevokedAt());
        assertEquals(firstId, jdbc.queryForObject("SELECT id FROM marketplace_sessions WHERE token_hash=?", Long.class, first.getTokenHash()));
    }

    @Test
    void simultaneousFirstContactsReturnTheSameCommittedConversation() throws Exception {
        var accounts = new MyBatisMarketplaceAccountStore(mapper);
        MarketplacePrincipal seller = accounts.createUser("chat-seller", "test-hash", "Seller");
        MarketplacePrincipal buyer = accounts.createUser("chat-buyer", "test-hash", "Buyer");
        long categoryId = items.listCategories().getFirst().id();
        var listing = items.createListing(new MarketplaceItemStore.NewListing(
                seller.id(), categoryId, "Camera", "", "https://example.test/camera.jpg", null));

        // Both requests must have observed an empty lookup before either may insert.
        var bothInserts = new CyclicBarrier(2);
        var realMapper = new SqlSessionTemplate(factory).getMapper(MarketplaceMapper.class);
        doAnswer(invocation -> {
            bothInserts.await(5, TimeUnit.SECONDS);
            realMapper.insertConversation(invocation.getArgument(0));
            return null;
        }).when(mapper).insertConversation(any());

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> chat.createConversation(buyer, listing.id()));
            var second = workers.submit(() -> chat.createConversation(buyer, listing.id()));
            var conversation = first.get(10, TimeUnit.SECONDS);
            assertEquals(conversation.id(), second.get(10, TimeUnit.SECONDS).id());
            assertEquals(buyer.id(), conversation.buyer().id());
            assertEquals(seller.id(), conversation.seller().id());
            assertEquals(1, chat.listConversations(buyer).conversations().size());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MarketplaceChatService.class, MyBatisMarketplaceConversationStore.class, MyBatisMarketplaceItemStore.class})
    static class Config {
        @Bean
        HikariDataSource dataSource() throws Exception {
            var source = new HikariDataSource();
            source.setJdbcUrl("jdbc:h2:mem:marketplace_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
            source.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
            String schema = new ClassPathResource("db/schema/marketplace/schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4\\s+COLLATE=utf8mb4_unicode_ci", "");
            new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(source);
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/marketplace/MarketplaceMapper.xml"));
            return factory.getObject();
        }

        @Bean MarketplaceMapper marketplaceMapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(MarketplaceMapper.class);
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
    }

    private static MarketplaceSessionRow session(String tokenHash, Long userId, Timestamp now, Timestamp expires) {
        return new MarketplaceSessionRow().setTokenHash(tokenHash).setUserId(userId)
                .setExpiresAt(expires).setLastSeenAt(now);
    }
}
