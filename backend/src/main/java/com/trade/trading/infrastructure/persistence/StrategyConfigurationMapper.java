package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StrategyConfigurationMapper {
    int insertHeadIfAbsent(StrategyConfigurationRow row);
    StrategyConfigurationRow findCurrent(@Param("strategyId") String strategyId);
    StrategyConfigurationRow lockCurrent(@Param("strategyId") String strategyId);
    int updateHead(StrategyConfigurationRow row);
    int insertHistory(StrategyConfigurationRow row);
    List<StrategyConfigurationRow> findHistory(@Param("strategyId") String strategyId,
            @Param("offset") int offset, @Param("limit") int limit);
}
