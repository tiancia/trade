package com.trade.trading.interfaces.web;

import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.paper.PaperAccountService.PaperAccountView;
import com.trade.trading.domain.paper.PaperEquityPoint;
import com.trade.trading.domain.paper.PaperFill;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

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
        return service.start(request.initialCash(), request.expectedSessionId(), request.expectedVersion());
    }
    @GetMapping("/sessions") public List<PaperAccountView> sessions(
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int limit) {
        return service.sessions(beforeId, limit);
    }
    @GetMapping("/sessions/{sessionId}") public PaperAccountView session(@PathVariable String sessionId) {
        return service.session(sessionId);
    }
    @DeleteMapping("/sessions/{sessionId}") public ResponseEntity<Void> delete(@PathVariable String sessionId,
            @RequestParam Long expectedVersion) {
        service.delete(sessionId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/sessions/{sessionId}/fills") public List<PaperFill> fills(@PathVariable String sessionId,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "100") int limit) {
        return service.fills(sessionId, beforeId, limit);
    }
    @GetMapping("/sessions/{sessionId}/equity") public List<PaperEquityPoint> equity(@PathVariable String sessionId,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "100") int limit) {
        return service.equity(sessionId, beforeId, limit);
    }
    @ExceptionHandler(IllegalStateException.class)
    public void conflict(IllegalStateException exception, HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.CONFLICT.value(), exception.getMessage());
    }
    @ExceptionHandler(NoSuchElementException.class)
    public void notFound(NoSuchElementException exception, HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.NOT_FOUND.value(), exception.getMessage());
    }
    public record StartRequest(BigDecimal initialCash, String expectedSessionId, Long expectedVersion) {}
}
