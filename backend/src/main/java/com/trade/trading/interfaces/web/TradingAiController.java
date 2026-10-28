package com.trade.trading.interfaces.web;

import com.trade.trading.application.strategy.TradingAiAdvisor;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingAiReview;
import com.trade.trading.infrastructure.config.TradingAiProperties;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trading/ai")
public class TradingAiController {
    private final TradingAiAdvisor advisor;
    private final TradingAiProperties ai;
    private final TradingProperties trading;
    public TradingAiController(TradingAiAdvisor advisor, TradingAiProperties ai, TradingProperties trading) {
        this.advisor = advisor; this.ai = ai; this.trading = trading;
    }
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", ai.isEnabled(), "autoApply", ai.isAutoApply(),
                "autoApplyEligible", ai.isEnabled() && ai.isAutoApply() && trading.isEnabled()
                        && trading.getExecutionMode() == ExecutionMode.PAPER && "SPOT".equalsIgnoreCase(trading.getInstType()),
                "executionMode", trading.getExecutionMode().name(), "reviewIntervalMs", ai.getReviewIntervalMs(),
                "minimumConfidence", ai.getMinimumConfidence(), "telegramEnabled", ai.getTelegram().isEnabled());
    }
    @GetMapping("/reviews")
    public List<TradingAiReview> reviews(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be from 1 to 100");
        return advisor.recent(limit);
    }
}
