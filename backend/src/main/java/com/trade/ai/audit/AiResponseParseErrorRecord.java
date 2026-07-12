package com.trade.ai.audit;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Provider-neutral details captured when an AI response cannot be parsed.
 *
 * <p>This contract is shared by business domains and deliberately contains no
 * database-specific fields.</p>
 */
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
