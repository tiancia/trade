package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StrategySelectionMapper {
    int insertIfAbsent(StrategySelectionRow row);
    StrategySelectionRow find(@Param("scope") String scope, @Param("instId") String instId);
    StrategySelectionRow lock(@Param("scope") String scope, @Param("instId") String instId);
    int update(StrategySelectionRow row);
    boolean hasExposure();
}
