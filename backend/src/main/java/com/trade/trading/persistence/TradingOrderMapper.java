package com.trade.trading.persistence;

import com.trade.trading.order.TradingOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TradingOrderMapper {
    int insertIfAbsent(TradingOrder order);

    TradingOrder findByIdempotencyKey(String idempotencyKey);

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
