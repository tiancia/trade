package com.trade.polymarket.domain.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Data
@Accessors(chain = true)
public class PolymarketDecisionContext {
    private Map<String, Object> aiParameters;
    private String aiParametersJson;
    private List<PolymarketMarketSnapshot> markets;

    public record TokenSelection(PolymarketMarketSnapshot market, PolymarketOutcomeSnapshot outcome) {}

    public Optional<TokenSelection> findUniqueToken(String tokenId) {
        if (tokenId == null || tokenId.isBlank() || markets == null) {
            return Optional.empty();
        }
        TokenSelection selected = null;
        for (var market : markets) {
            if (market == null || market.getOutcomes() == null) continue;
            for (var outcome : market.getOutcomes()) {
                if (outcome == null || !tokenId.equals(outcome.getTokenId())) continue;
                if (selected != null) return Optional.empty();
                selected = new TokenSelection(market, outcome);
            }
        }
        return Optional.ofNullable(selected);
    }

    public Optional<PolymarketMarketSnapshot> findMarketByTokenId(String tokenId) {
        return findUniqueToken(tokenId).map(TokenSelection::market);
    }

    public Optional<PolymarketOutcomeSnapshot> findOutcomeByTokenId(String tokenId) {
        return findUniqueToken(tokenId).map(TokenSelection::outcome);
    }
}
