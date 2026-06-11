package com.trade.trading.persistence;

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

    /** Upserts one API/WebSocket batch in a single database round trip. */
    void upsertBatch(@Param("rows") List<OkxCandleCacheRow> rows);
}
