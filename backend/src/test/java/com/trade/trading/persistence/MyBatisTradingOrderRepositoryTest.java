package com.trade.trading.persistence;

import com.trade.trading.order.OrderFill;
import com.trade.trading.order.OrderLifecycleService;
import com.trade.trading.order.OrderReservation;
import com.trade.trading.order.OrderStatus;
import com.trade.trading.order.OrderSubmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "trade.trading.enabled=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:order_state;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "trade.text-game.seed-enabled=false"
})
@Sql(statements = {
        "DROP TABLE IF EXISTS okx_order_status_history",
        "DROP TABLE IF EXISTS okx_orders",
        "CREATE TABLE okx_orders (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "idempotency_key VARCHAR(64) NOT NULL UNIQUE," +
                "client_order_id VARCHAR(32) NOT NULL UNIQUE," +
                "exchange_order_id VARCHAR(128), decision_id VARCHAR(64), strategy_id VARCHAR(128)," +
                "inst_id VARCHAR(64) NOT NULL, action VARCHAR(32) NOT NULL, side VARCHAR(16) NOT NULL," +
                "td_mode VARCHAR(32), order_type VARCHAR(32), target_currency VARCHAR(32)," +
                "requested_size DECIMAL(38,18) NOT NULL, status VARCHAR(32) NOT NULL, version BIGINT NOT NULL," +
                "filled_base_amount DECIMAL(38,18), average_fill_price DECIMAL(38,18), fee DECIMAL(38,18)," +
                "fee_ccy VARCHAR(32), failure_code VARCHAR(64), failure_message VARCHAR(1000)," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL," +
                "submitted_at TIMESTAMP, completed_at TIMESTAMP)",
        "CREATE TABLE okx_order_status_history (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL," +
                "from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL, version BIGINT NOT NULL," +
                "reason VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL," +
                "UNIQUE(order_id, version))"
})
class MyBatisTradingOrderRepositoryTest {
    @Autowired
    private OrderLifecycleService lifecycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uniqueKeyAndOptimisticStateTransitionsArePersistedWithHistory() {
        OrderSubmission submission = new OrderSubmission(
                "database-idempotency-key",
                "stbu-database-order",
                "decision-1",
                "strategy-1",
                "BTC-USDT",
                "BUY",
                "buy",
                "cash",
                "market",
                "quote_ccy",
                BigDecimal.TEN
        );

        OrderReservation first = lifecycleService.reserve(submission);
        OrderReservation replay = lifecycleService.reserve(submission);
        lifecycleService.markAccepted(submission.idempotencyKey(), "exchange-1");
        lifecycleService.markFilled(
                submission.idempotencyKey(),
                "exchange-1",
                new OrderFill(new BigDecimal("0.001"), new BigDecimal("50000"),
                        new BigDecimal("-0.000001"), "BTC")
        );

        assertTrue(first.acquired());
        assertFalse(replay.acquired());
        assertEquals(OrderStatus.FILLED,
                lifecycleService.find(submission.idempotencyKey()).orElseThrow().getStatus());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_orders", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okx_order_status_history", Integer.class));
    }
}
