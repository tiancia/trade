package com.trade.trading.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface FundSafetyMapper {
    int insertIfAbsent(FundSafetyStateRow row);

    FundSafetyStateRow find(@Param("accountScope") String accountScope);

    int halt(
            @Param("accountScope") String accountScope,
            @Param("source") String source,
            @Param("reason") String reason,
            @Param("haltedAt") Instant haltedAt,
            @Param("updatedAt") Instant updatedAt
    );

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
