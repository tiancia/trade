package com.trade.ai.persistence;

public interface AiResponseParseErrorSink {
    AiResponseParseErrorSink NOOP = record -> {
    };

    void save(AiResponseParseErrorRecord record);
}
