package com.trade.trading.interfaces.web;

import com.trade.trading.application.order.UnknownOrderRecoveryService;
import com.trade.trading.domain.order.TradingOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/trading/orders")
public class UnknownOrderRecoveryController {
    private final UnknownOrderRecoveryService recovery;

    public UnknownOrderRecoveryController(UnknownOrderRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/unresolved")
    public List<TradingOrder> unresolved() {
        return recovery.unresolved();
    }

    @PostMapping("/{idempotencyKey}/abandon")
    public TradingOrder abandon(@PathVariable String idempotencyKey, @RequestBody AbandonRequest request) {
        try {
            return recovery.abandon(idempotencyKey, request.expectedVersion(), request.expectedSafetyVersion(),
                    request.clientOrderId(), request.operatorReference(), request.reason(), request.evidence(),
                    request.confirmation());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    public record AbandonRequest(long expectedVersion, long expectedSafetyVersion, String clientOrderId,
            String operatorReference, String reason, String evidence, String confirmation) {}
}
