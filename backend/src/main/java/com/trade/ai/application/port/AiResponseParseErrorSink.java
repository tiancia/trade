package com.trade.ai.application.port;

import com.trade.ai.domain.model.AiResponseParseErrorRecord;

/**
 * Output port used by AI workflows to record parse failures.
 */
public interface AiResponseParseErrorSink {
    AiResponseParseErrorSink NOOP = record -> {
    };

    void save(AiResponseParseErrorRecord record);
}
