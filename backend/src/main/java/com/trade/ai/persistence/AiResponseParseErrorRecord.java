package com.trade.ai.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AiResponseParseErrorRecord {
    private String source;
    private String phase;
    private String relatedId;
    private String promptText;
    private String rawResponse;
    private String errorMessage;
    private String fallbackAction;
    private String metadataJson;

}
