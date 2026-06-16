package com.trade.textgame.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class TextGameSessionEventRow {
    private Long id;
    private String sessionId;
    private int sequenceNo;
    private String nodeId;
    private String choiceId;
    private String effectsJson;
    private String stateAfterJson;
    private Timestamp createdAt;
}
