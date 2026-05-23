package com.trade.polymarket.model;

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

    public Optional<PolymarketMarketSnapshot> findMarketByTokenId(String tokenId) {
        if (tokenId == null || markets == null) {
            return Optional.empty();
        }
        return markets.stream()
                .filter(market -> market.getOutcomes() != null)
                .filter(market -> market.getOutcomes().stream()
                        .anyMatch(outcome -> tokenId.equals(outcome.getTokenId())))
                .findFirst();
    }

    public Optional<PolymarketOutcomeSnapshot> findOutcomeByTokenId(String tokenId) {
        if (tokenId == null || markets == null) {
            return Optional.empty();
        }
        return markets.stream()
                .filter(market -> market.getOutcomes() != null)
                .flatMap(market -> market.getOutcomes().stream())
                .filter(outcome -> tokenId.equals(outcome.getTokenId()))
                .findFirst();
    }
}
