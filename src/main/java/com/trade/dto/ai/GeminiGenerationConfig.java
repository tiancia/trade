package com.trade.dto.ai;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GeminiGenerationConfig {
    private Double temperature;
    private Double topP;
    private Integer topK;
    private Integer candidateCount;
    private Integer maxOutputTokens;
    private String responseMimeType;
}
