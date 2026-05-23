package com.trade.ai.persistence;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MyBatisAiResponseParseErrorRepository implements AiResponseParseErrorSink {
    private final AiResponseParseErrorMapper mapper;

    public MyBatisAiResponseParseErrorRepository(AiResponseParseErrorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(AiResponseParseErrorRecord record) {
        if (record == null || isBlank(record.getSource()) || isBlank(record.getErrorMessage())) {
            return;
        }
        mapper.insert(new AiResponseParseErrorRow()
                .setSource(record.getSource())
                .setPhase(record.getPhase())
                .setRelatedId(record.getRelatedId())
                .setPromptText(record.getPromptText())
                .setRawResponse(record.getRawResponse())
                .setErrorMessage(record.getErrorMessage())
                .setFallbackAction(record.getFallbackAction())
                .setMetadataJson(record.getMetadataJson()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
