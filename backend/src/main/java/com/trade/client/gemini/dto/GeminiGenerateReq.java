package com.trade.client.gemini.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class GeminiGenerateReq {
    private List<GeminiContent> contents;
    private GeminiContent systemInstruction;
    private GeminiGenerationConfig generationConfig;
}
