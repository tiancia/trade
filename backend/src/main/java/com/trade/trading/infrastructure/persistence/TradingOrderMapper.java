package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TradingOrderMapper {
    int insertIfAbsent(TradingOrderRow order);

    TradingOrderRow findByIdempotencyKey(String idempotencyKey);

    List<TradingOrderRow> findReconciliationCandidates(
            @Param("instId") String instId,
            @Param("limit") int limit
    );

    int compareAndSet(
            @Param("current") TradingOrderRow current,
            @Param("next") TradingOrderRow next
    );

    void insertStatusHistory(
            @Param("orderId") Long orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("version") long version,
            @Param("reason") String reason
    );
}
