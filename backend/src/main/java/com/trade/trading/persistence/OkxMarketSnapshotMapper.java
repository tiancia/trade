package com.trade.trading.persistence;

import org.apache.ibatis.annotations.Mapper;

/** MyBatis writes for OKX market snapshots. */
@Mapper
public interface OkxMarketSnapshotMapper {

    /** Inserts one immutable snapshot and populates its generated identifier. */
    void insert(OkxMarketSnapshotRow row);
}
