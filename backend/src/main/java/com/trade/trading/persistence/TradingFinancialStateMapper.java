package com.trade.trading.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TradingFinancialStateMapper {
    int insertPositionIfAbsent(TradingPositionRow row);

    TradingPositionRow findPosition(
            @Param("accountScope") String accountScope,
            @Param("instId") String instId
    );

    TradingPositionRow lockPosition(
            @Param("accountScope") String accountScope,
            @Param("instId") String instId
    );

    int updatePosition(TradingPositionRow row);

    int insertRiskStateIfAbsent(TradingRiskStateRow row);

    TradingRiskStateRow findRiskState(@Param("accountScope") String accountScope);

    TradingRiskStateRow lockRiskState(@Param("accountScope") String accountScope);

    int updateRiskState(TradingRiskStateRow row);

    int recordReconciliationSuccess(
            @Param("accountScope") String accountScope,
            @Param("reconciledAt") java.time.Instant reconciledAt,
            @Param("updatedAt") java.time.Instant updatedAt
    );

    int recordReconciliationFailure(
            @Param("accountScope") String accountScope,
            @Param("reconciledAt") java.time.Instant reconciledAt,
            @Param("error") String error,
            @Param("updatedAt") java.time.Instant updatedAt
    );

    int insertFillLedgerIfAbsent(OrderFillLedgerRow row);

    OrderFillLedgerRow lockFillLedger(@Param("orderId") long orderId);

    int updateFillLedger(OrderFillLedgerRow row);
}
