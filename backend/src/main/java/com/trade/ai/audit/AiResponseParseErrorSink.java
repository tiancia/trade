package com.trade.ai.audit;

/**
 * Output port used by AI workflows to record parse failures.
 */
public interface AiResponseParseErrorSink {
    AiResponseParseErrorSink NOOP = record -> {
    };

    void save(AiResponseParseErrorRecord record);
}
