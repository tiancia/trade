package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.math.BigDecimal;

@Mapper
public interface FundSafetyMapper {
    String firstUnverifiedPosition(@Param("accountScope") String accountScope, @Param("haltedAt") Instant haltedAt,
                                   @Param("tolerance") BigDecimal tolerance, @Param("dedicated") boolean dedicated);

    int insertIfAbsent(FundSafetyStateRow row);

    FundSafetyStateRow find(@Param("accountScope") String accountScope);

    FundSafetyStateRow lock(@Param("accountScope") String accountScope);

    int halt(
            @Param("accountScope") String accountScope,
            @Param("source") String source,
            @Param("reason") String reason,
            @Param("haltedAt") Instant haltedAt,
            @Param("updatedAt") Instant updatedAt
    );

    int invalidateReconciliation(@Param("accountScope") String accountScope, @Param("now") Instant now);

    int resume(
            @Param("accountScope") String accountScope,
            @Param("expectedVersion") long expectedVersion,
            @Param("resumeReason") String resumeReason,
            @Param("resumedAt") Instant resumedAt,
            @Param("updatedAt") Instant updatedAt
    );

    int recordActionError(
            @Param("accountScope") String accountScope,
            @Param("error") String error,
            @Param("updatedAt") Instant updatedAt
    );
}
