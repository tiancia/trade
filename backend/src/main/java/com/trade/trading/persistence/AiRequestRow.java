package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AiRequestRow {
    private Long decisionRunId;
    private String promptText;
    private String aiParametersJson;
}
