package com.trade.dto.ai;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class GeminiContent {
    private String role;
    private List<GeminiPart> parts;
}
