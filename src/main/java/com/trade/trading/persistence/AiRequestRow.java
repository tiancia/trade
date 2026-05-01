package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AiRequestRow {
    private String tableName;
    private String decisionId;
    private String promptText;
    private String aiParametersJson;
}
