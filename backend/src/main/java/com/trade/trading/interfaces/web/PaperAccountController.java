package com.trade.trading.interfaces.web;

import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.paper.PaperAccountService.PaperAccountView;
import com.trade.trading.domain.paper.PaperEquityPoint;
import com.trade.trading.domain.paper.PaperFill;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** Operator API behind the same private/gateway deployment boundary as the trading cockpit. */
@RestController
@RequestMapping("/api/trading/paper")
public class PaperAccountController {
    private final PaperAccountService service;
    public PaperAccountController(PaperAccountService service) { this.service = service; }
    @GetMapping("/account") public ResponseEntity<?> current() {
        PaperAccountView account = service.current();
        return account == null ? ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null")
                : ResponseEntity.ok(account);
    }
    @PostMapping("/sessions") public PaperAccountView start(@RequestBody StartRequest request) {
        return api(() -> {
            if (request == null) throw new IllegalArgumentException("Request body is required");
            return service.start(request.initialCash(), request.expectedSessionId(), request.expectedVersion());
        });
    }
    @GetMapping("/sessions") public List<PaperAccountView> sessions(
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int limit) {
        return api(() -> service.sessions(beforeId, limit));
    }
    @GetMapping("/sessions/{sessionId}") public PaperAccountView session(@PathVariable String sessionId) {
        return api(() -> service.session(sessionId));
    }
    @GetMapping("/sessions/{sessionId}/fills") public List<PaperFill> fills(@PathVariable String sessionId,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "100") int limit) {
        return api(() -> service.fills(sessionId, beforeId, limit));
    }
    @GetMapping("/sessions/{sessionId}/equity") public List<PaperEquityPoint> equity(@PathVariable String sessionId,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "100") int limit) {
        return api(() -> service.equity(sessionId, beforeId, limit));
    }
    private <T> T api(Supplier<T> action) {
        try { return action.get(); }
        catch (ConcurrentModificationException | IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e); }
        catch (NoSuchElementException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e); }
    }
    public record StartRequest(BigDecimal initialCash, String expectedSessionId, Long expectedVersion) {}
}
