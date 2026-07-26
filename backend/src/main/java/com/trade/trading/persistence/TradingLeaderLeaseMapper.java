package com.trade.trading.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;

@Mapper
public interface TradingLeaderLeaseMapper {

    Timestamp currentTime();

    int insertIfAbsent(TradingLeaderLeaseRow row);

    int acquireOrRenew(
            @Param("leaseName") String leaseName,
            @Param("ownerId") String ownerId,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil
    );

    TradingLeaderLeaseRow find(@Param("leaseName") String leaseName);

    int release(
            @Param("leaseName") String leaseName,
            @Param("ownerId") String ownerId,
            @Param("now") Instant now
    );
}
