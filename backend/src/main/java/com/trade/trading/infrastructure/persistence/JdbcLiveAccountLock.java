package com.trade.trading.infrastructure.persistence;

import com.trade.trading.port.LiveAccountLock;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Supplier;

@Component
public class JdbcLiveAccountLock implements LiveAccountLock {
    private final DataSource dataSource;

    public JdbcLiveAccountLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized <T> T withLock(Supplier<T> action) {
        // A dedicated connection owns only the guard row. The order journal and emergency
        // HALT must commit independently, including when the exchange call fails or blocks.
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var query = connection.prepareStatement(
                        "SELECT id FROM okx_account_action_guard WHERE account_scope = 'live' FOR UPDATE");
                     var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Missing LIVE account action guard");
                }
                return action.get();
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("LIVE account action guard unavailable", failure);
        }
    }
}
