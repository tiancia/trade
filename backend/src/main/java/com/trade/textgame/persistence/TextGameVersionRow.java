package com.trade.textgame.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class TextGameVersionRow {
    private Long id;
    private Long storyId;
    private String storyKey;
    private String title;
    private String summary;
    private boolean enabled;
    private int sortOrder;
    private int versionNumber;
    private String status;
    private long revision;
    private String storyJson;
    private String checksum;
    private Timestamp publishedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
