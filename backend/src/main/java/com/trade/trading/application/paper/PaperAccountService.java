package com.trade.trading.application.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.application.port.PaperAccountStore;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.paper.PaperAccount;
import com.trade.trading.domain.paper.PaperEquityPoint;
import com.trade.trading.domain.paper.PaperFill;
import com.trade.trading.domain.order.OrderIdentity;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.UnaryOperator;

/** PAPER account use cases. No exchange account value or legacy tracked position enters a new trial. */
@Service
public class PaperAccountService {
    private final PaperAccountStore store;
    private final TradingProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public PaperAccountService(PaperAccountStore store, TradingProperties properties) {
        this(store, properties, Clock.systemUTC());
    }
    public PaperAccountService(PaperAccountStore store, TradingProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public PaperAccount activeAccount() { return store.active(properties.getInstId()); }
    public PaperAccountView current() { return view(activeAccount()); }
    public PaperAccountView session(String sessionId) { return view(requiredSession(sessionId)); }
    public PaperAccountView start(BigDecimal initialCash, String expectedSessionId, Long expectedVersion) {
        requirePaperSpot();
        return view(store.start(properties.getInstId(), properties.getQuoteCcy(), initialCash,
                expectedSessionId, expectedVersion, Instant.now(clock)));
    }
    public List<PaperAccountView> sessions(Long beforeId, int limit) {
        return store.sessions(properties.getInstId(), beforeId, bounded(limit)).stream().map(this::view).toList();
    }
    public void delete(String sessionId, Long expectedVersion) {
        requirePaperSpot();
        if (sessionId == null || sessionId.isBlank() || expectedVersion == null || expectedVersion < 0) {
            throw new IllegalArgumentException("Simulation session and non-negative expectedVersion are required");
        }
        store.delete(properties.getInstId(), sessionId, expectedVersion);
    }
    public List<PaperFill> fills(String sessionId, Long beforeId, int limit) {
        requiredSession(sessionId);
        return store.fills(properties.getInstId(), sessionId, beforeId, bounded(limit));
    }
    public List<PaperEquityPoint> equity(String sessionId, Long beforeId, int limit) {
        requiredSession(sessionId);
        return store.equity(properties.getInstId(), sessionId, beforeId, bounded(limit));
    }

    /** Called before strategy evaluation, AI parameter construction, risk, and sizing. */
    public TradingDecisionContext enrichContext(TradingDecisionContext context) {
        if (properties.getExecutionMode() != ExecutionMode.PAPER || context == null) return context;
        PaperAccount account = activeAccount();
        Instant marketTime = marketTime(context);
        BigDecimal price = TradingMarketInputs.lastPrice(context);
        if (account != null && price.signum() > 0 && marketTime != null) {
            account = store.mark(properties.getInstId(), account.sessionId(), price, marketTime,
                    null, null, Instant.now(clock));
        }
        overlay(context, account);
        context.setPaperPriceAt(marketTime);
        return context;
    }

    /** Samples HOLD as well as traded decisions, tagged with the decision's immutable config. */
    public void markDecision(TradingDecisionContext context, TradingDecisionRecord record) {
        if (properties.getExecutionMode() != ExecutionMode.PAPER || context == null
                || context.getPaperSessionId() == null || context.getPaperPriceAt() == null
                || TradingMarketInputs.lastPrice(context).signum() <= 0) return;
        store.mark(properties.getInstId(), context.getPaperSessionId(), TradingMarketInputs.lastPrice(context),
                context.getPaperPriceAt(), record.getStrategyId(), record.getStrategyConfigVersion(), Instant.now(clock));
        tag(record, context.getPaperSessionId());
    }

    public PaperAccountStore.AppliedFill fill(TradingDecisionContext context, TradingDecisionRecord record,
                                               String side, BigDecimal size) {
        requirePaperSpot();
        if (context == null || context.getPaperSessionId() == null || context.getPaperSessionVersion() == null) {
            throw new IllegalStateException("Initialize simulated funds before trading");
        }
        String snapshot;
        try { snapshot = json.writeValueAsString(record.getStrategyConfigSnapshot()); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize strategy snapshot", e); }
        // The fill table already has a UNIQUE decision_id. Stable signal IDs
        // survive retries/restarts and do not depend on mutable order sizing.
        record.setDecisionId(new OrderIdentity().paperDecisionId(properties.getInstId(), context.getPaperSessionId(),
                record.getStrategyId(), side, record.getMetadata(), record.getDecisionId()));
        var result = store.fill(new PaperAccountStore.FillCommand(properties.getInstId(), context.getPaperSessionId(),
                context.getPaperSessionVersion(), record.getDecisionId(), record.getStrategyId(),
                record.getStrategyConfigVersion(), snapshot, side, size, TradingMarketInputs.lastPrice(context),
                properties.getTakerFeeRate(), context.getPaperPriceAt(), Instant.now(clock)));
        // The fill transaction has committed. Refresh monetary facts locally;
        // defer any risk I/O to the separately guarded post-fill transition.
        overlay(context, result.account(), false);
        tag(record, result.account().sessionId());
        return result;
    }

    public TradingRiskState riskState(TradingDecisionContext context) {
        if (context == null || context.getPaperSessionId() == null) return new TradingRiskState();
        return store.risk(properties.getInstId(), context.getPaperSessionId());
    }
    public TradingRiskState updateRiskState(TradingDecisionContext context, UnaryOperator<TradingRiskState> transition) {
        if (context == null || context.getPaperSessionId() == null) return transition.apply(new TradingRiskState());
        if (context.getPaperSessionVersion() == null) {
            throw new IllegalStateException("Simulation account revision is missing; refresh before updating risk");
        }
        TradingRiskState next = store.updateRisk(properties.getInstId(), context.getPaperSessionId(),
                context.getPaperSessionVersion(), transition);
        if (context.getTradingState() != null) context.getTradingState().setRiskState(next);
        return next;
    }

    private void overlay(TradingDecisionContext context, PaperAccount a) {
        overlay(context, a, true);
    }
    private void overlay(TradingDecisionContext context, PaperAccount a, boolean loadRisk) {
        BigDecimal quantity = a == null ? BigDecimal.ZERO : a.positionQuantity();
        BigDecimal cash = a == null ? BigDecimal.ZERO : a.cash();
        BalanceDetail base = balance(properties.getBaseCcy(), quantity);
        BalanceDetail quote = balance(properties.getQuoteCcy(), cash);
        AccountBalanceResp account = new AccountBalanceResp();
        account.setDetails(List.of(base, quote));
        account.setTotalEq(a == null || a.equity() == null ? "0" : a.equity().toPlainString());
        context.setAccountBalance(account).setBaseBalance(base).setQuoteBalance(quote)
                .setPendingOrders(List.of()).setRecentOrders(List.of()).setRecentFills(List.of()).setPositions(List.of())
                .setPaperSessionId(a == null ? null : a.sessionId()).setPaperSessionVersion(a == null ? null : a.version());
        TradingState state = context.getTradingState();
        if (state == null) state = new TradingState();
        state.setTrackedBaseAmount(quantity).setAverageCost(a == null ? BigDecimal.ZERO : a.averageCost())
                .setExchangeBaseAmount(null).setFinancialAccountScope(a == null ? "paper:uninitialized" : "paper:" + a.sessionId())
                .setPositionVersion(a == null ? 0 : a.version()).setPositionLastReconciledAt(null)
                .setRiskState(a == null ? new TradingRiskState()
                        : loadRisk ? store.risk(properties.getInstId(), a.sessionId()) : state.getRiskState());
        context.setTradingState(state);
    }
    private static BalanceDetail balance(String ccy, BigDecimal value) {
        BalanceDetail detail = new BalanceDetail();
        detail.setCcy(ccy); detail.setAvailBal(value.toPlainString()); detail.setCashBal(value.toPlainString()); detail.setEq(value.toPlainString());
        return detail;
    }
    private Instant marketTime(TradingDecisionContext context) {
        try {
            return context.getTicker() == null || context.getTicker().getTs() == null ? null
                    : Instant.ofEpochMilli(Long.parseLong(context.getTicker().getTs()));
        } catch (RuntimeException ignored) { return null; }
    }
    private PaperAccount requiredSession(String sessionId) {
        PaperAccount a = store.session(properties.getInstId(), sessionId);
        if (a == null) throw new NoSuchElementException("Simulation session not found");
        return a;
    }
    private void requirePaperSpot() {
        if (properties.getExecutionMode() != ExecutionMode.PAPER) throw new IllegalStateException("Simulated funds can only be changed in PAPER mode");
        if (!properties.isSpotInstrument()) throw new IllegalStateException("Funded simulation currently supports SPOT instruments only");
    }
    private static int bounded(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Limit must be between 1 and 500");
        return limit;
    }
    private void tag(TradingDecisionRecord record, String sessionId) {
        var metadata = new LinkedHashMap<String, Object>();
        if (record.getMetadata() != null) metadata.putAll(record.getMetadata());
        metadata.put("paperSessionId", sessionId);
        record.setMetadata(metadata);
    }
    private PaperAccountView view(PaperAccount a) {
        if (a == null) return null;
        return new PaperAccountView(a.id(), a.sessionId(), a.instId(), a.quoteCcy(), a.status(), a.initialCash(),
                a.cash(), a.positionQuantity(), a.averageCost(), a.realizedPnl(), a.totalFeesQuote(), a.lastPrice(),
                a.equity(), a.unrealizedPnl(), a.totalReturnPercent(), a.version(), a.createdAt(), a.updatedAt(),
                a.valuationAt(), a.valuationAt() == null || a.valuationAt().isBefore(Instant.now(clock).minusSeconds(120)));
    }
    public record PaperAccountView(Long id, String sessionId, String instId, String quoteCcy, String status,
                                   BigDecimal initialCash, BigDecimal cash, BigDecimal positionQuantity,
                                   BigDecimal averageCost, BigDecimal realizedPnl, BigDecimal totalFeesQuote,
                                   BigDecimal lastPrice, BigDecimal equity, BigDecimal unrealizedPnl,
                                   BigDecimal totalReturnPercent, long version, Instant createdAt, Instant updatedAt,
                                   Instant valuationAt, boolean valuationStale) {}
}
