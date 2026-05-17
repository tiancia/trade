package com.trade.textgame.model;

import java.util.List;

public record TextGameCatalogResponse(
        List<TextGameThemeSummary> themes,
        List<TextGameModeSummary> modes
) {
}
