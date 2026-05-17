package com.trade.ai.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiResponseParseErrorMapper {
    void insert(AiResponseParseErrorRow row);
}
