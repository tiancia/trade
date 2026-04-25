package com.trade.dto.ai;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GeminiPart {
    private String text;
}
