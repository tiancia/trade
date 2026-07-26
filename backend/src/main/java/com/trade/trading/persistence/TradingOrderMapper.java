package com.trade.trading.persistence;

import com.trade.trading.order.TradingOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TradingOrderMapper {
    int insertIfAbsent(TradingOrder order);

    TradingOrder findByIdempotencyKey(String idempotencyKey);

    List<TradingOrder> findReconciliationCandidates(
            @Param("instId") String instId,
            @Param("limit") int limit
    );

    int compareAndSet(
            @Param("current") TradingOrder current,
            @Param("next") TradingOrder next
    );

    void insertStatusHistory(
            @Param("orderId") Long orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("version") long version,
            @Param("reason") String reason
    );
}
