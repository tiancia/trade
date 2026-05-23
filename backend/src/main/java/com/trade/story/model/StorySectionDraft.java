package com.trade.story.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class StorySectionDraft {
    private int section;
    private String sectionTitle;
    private String content;
    private String endingHook;
    private String sectionSummary;
    private List<String> continuityNotes = new ArrayList<>();
    private List<String> openLoops = new ArrayList<>();
    private List<String> resolvedLoops = new ArrayList<>();
    private String rawResponse;
}
