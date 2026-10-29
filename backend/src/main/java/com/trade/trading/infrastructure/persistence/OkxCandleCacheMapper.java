package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OkxCandleCacheMapper {
    List<OkxCandleCacheRow> findRange(
            @Param("instId") String instId,
            @Param("bar") String bar,
            @Param("fromTs") long fromTs,
            @Param("toTs") long toTs
    );

    void upsert(OkxCandleCacheRow row);

    List<Long> findExistingTimestamps(
            @Param("instId") String instId,
            @Param("bar") String bar,
            @Param("timestamps") List<Long> timestamps
    );

    /** Updates existing candles without allocating AUTO_INCREMENT values. */
    void updateBatch(
            @Param("instId") String instId,
            @Param("bar") String bar,
            @Param("rows") List<OkxCandleCacheRow> rows
    );

    /** Inserts newly observed candles; the duplicate path handles concurrent first writes. */
    void upsertBatch(@Param("rows") List<OkxCandleCacheRow> rows);
}
