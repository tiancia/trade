package com.trade.textgame.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class TextGameStoryRow {
    private Long id;
    private String storyKey;
    private String title;
    private String summary;
    private boolean enabled;
    private int sortOrder;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
