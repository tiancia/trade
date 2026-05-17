package com.trade.story.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class StorySectionPlan {
    private int section;
    private String title;
    private String summary;
    private String entryState;
    private List<String> keyBeats = new ArrayList<>();
    private String mustPayoff;
    private String exitState;
    private String cliffhanger;
    private int targetChars;
}
