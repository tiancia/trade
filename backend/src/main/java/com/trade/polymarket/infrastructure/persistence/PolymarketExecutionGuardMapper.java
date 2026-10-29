package com.trade.polymarket.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PolymarketExecutionGuardMapper {
    int insertIfAbsent();

    String lockGuard();

    int reserve(@Param("decisionId") Long decisionId);

    int release(@Param("decisionId") Long decisionId);
}
