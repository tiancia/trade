package com.trade.marketplace.infrastructure.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketplaceSessionPersistenceTest {
    @Test
    void generatedSessionIdsPreserveTokenLookupUniquenessAndRevocation() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:marketplace_sessions_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (var connection = source.getConnection()) {
            String schema = new ClassPathResource("db/schema/marketplace/schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4\\s+COLLATE=utf8mb4_unicode_ci", "");
            new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(source);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/marketplace/MarketplaceMapper.xml"));
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MarketplaceMapper.class);
            var jdbc = new JdbcTemplate(source);
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
    }

    private static MarketplaceSessionRow session(String tokenHash, Long userId, Timestamp now, Timestamp expires) {
        return new MarketplaceSessionRow().setTokenHash(tokenHash).setUserId(userId)
                .setExpiresAt(expires).setLastSeenAt(now);
    }
}
