package com.trade.trading.infrastructure.persistence;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.port.PaperAccountStore;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.paper.PaperAccount;
import com.trade.trading.domain.paper.PaperEquityPoint;
import com.trade.trading.domain.paper.PaperFill;
import com.trade.trading.domain.paper.PaperDailyPerformance;
import com.trade.trading.domain.risk.RiskStateTransitions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** The head row fences all writers, including replacement of a trial during an in-flight decision. */
@Component
public class MyBatisPaperAccountStore implements PaperAccountStore {
    private final Cache initializedRows = new TransactionAwareCacheDecorator(
            new ConcurrentMapCache("initialized-rows"));

    private final PaperAccountMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    public MyBatisPaperAccountStore(PaperAccountMapper mapper) { this.mapper = mapper; }

    @Override public PaperAccount active(String instId) { return account(mapper.active(instId)); }
    @Override public PaperAccount session(String instId, String sessionId) { return account(mapper.session(instId, sessionId)); }

    /** Fence AI maintenance against fills, session deletion and replacement across instances. */
    @Override @Transactional
    public boolean changeStrategyWhileFlat(String instId, String sessionId, long expectedVersion,
                                           java.util.function.BooleanSupplier action) {
        PaperAccount current = lock(instId);
        if (current == null || !Objects.equals(current.sessionId(), sessionId)
                || current.version() != expectedVersion || current.positionQuantity().signum() != 0) conflict();
        boolean changed = action.getAsBoolean();
        // Invalidate decisions collected before maintenance, including on another instance.
        if (changed && mapper.advanceVersion(sessionId, expectedVersion) != 1) conflict();
        return changed;
    }

    @Override
    @Transactional
    public PaperAccount start(String instId, String quoteCcy, BigDecimal initialCash,
                              String expectedSessionId, Long expectedVersion, Instant now) {
        PaperAccount next = PaperAccount.start(UUID.randomUUID().toString(), instId, quoteCcy, initialCash, now);
        PaperAccount current = lock(instId);
        if (current == null) {
            if (expectedSessionId != null || expectedVersion != null) conflict();
        } else if (!Objects.equals(current.sessionId(), expectedSessionId)
                || expectedVersion == null || current.version() != expectedVersion) {
            conflict();
        }
        if (current != null && mapper.archive(current.sessionId()) != 1) conflict();
        TradingRiskState seed = new TradingRiskState().setCurrentEquity(initialCash)
                .setEquityHighWatermark(initialCash).setDayStartEquity(initialCash)
                .setDayStartDate(now.atZone(ZoneOffset.UTC).toLocalDate().toString());
        PaperAccountRow row = row(next).setRiskStateJson(write(seed));
        mapper.insertSession(row);
        if (mapper.updateHead(instId, next.sessionId()) != 1) conflict();
        PaperAccount created = account(row);
        sample(created, null, null, "INITIAL", now);
        return created;
    }

    @Override
    @Transactional
    public void delete(String instId, String sessionId, long expectedVersion) {
        // Use the same head lock as fills, marks and session replacement, even for archived trials.
        PaperAccount current = lock(instId);
        PaperAccount target = session(instId, sessionId);
        if (target == null) throw new NoSuchElementException("Simulation session not found");
        if (target.version() != expectedVersion) conflict();
        if (current != null && Objects.equals(current.sessionId(), sessionId)
                && mapper.updateHead(instId, null) != 1) conflict();
        mapper.deleteEquity(sessionId);
        mapper.deleteFills(sessionId);
        if (mapper.deleteSession(instId, sessionId, expectedVersion) != 1) conflict();
    }

    @Override
    @Transactional
    public PaperAccount mark(String instId, String expectedSessionId, BigDecimal price, Instant marketTime,
                             String strategyId, Long strategyConfigVersion, Instant now) {
        PaperAccount current = lock(instId);
        if (current == null) return null;
        if (expectedSessionId != null && !Objects.equals(expectedSessionId, current.sessionId())) conflict();
        if (marketTime == null || marketTime.isAfter(now.plusSeconds(30))) return current;
        // A delayed market snapshot must never replace a newer valuation.
        if (current.valuationAt() != null && current.valuationAt().isAfter(marketTime)) return current;
        PaperAccount marked = current.mark(price, marketTime, now);
        update(marked);
        PaperEquityPoint last = mapper.lastMark(marked.sessionId());
        boolean changedConfig = strategyId != null && (last == null
                || !Objects.equals(strategyId, last.strategyId())
                || !Objects.equals(strategyConfigVersion, last.strategyConfigVersion()));
        if (last == null || !last.timestamp().plusSeconds(60).isAfter(now) || changedConfig) {
            sample(marked, strategyId == null && last != null ? last.strategyId() : strategyId,
                    strategyId == null && last != null ? last.strategyConfigVersion() : strategyConfigVersion, "MARK", now);
        }
        return marked;
    }

    @Override
    @Transactional
    public AppliedFill fill(FillCommand command) {
        if (command.decisionId() == null || command.decisionId().isBlank()) {
            throw new IllegalArgumentException("A stable decision ID is required for simulated fill idempotency");
        }
        PaperAccount current = lock(command.instId());
        PaperFill replay = mapper.findFill(command.decisionId());
        if (replay != null) {
            if (!Objects.equals(replay.sessionId(), command.expectedSessionId())) conflict();
            return new AppliedFill(replay, session(command.instId(), replay.sessionId()), true);
        }
        if (current == null) throw new IllegalStateException("Initialize simulated funds before trading");
        if (!Objects.equals(current.sessionId(), command.expectedSessionId())
                || current.version() != command.expectedVersion()) conflict();
        if (command.marketTime() == null || command.marketTime().isBefore(command.now().minusSeconds(120))
                || command.marketTime().isAfter(command.now().plusSeconds(30))) {
            throw new IllegalArgumentException("Simulated fill skipped: market price timestamp is missing or stale");
        }
        if (current.valuationAt() != null && command.marketTime().isBefore(current.valuationAt())) {
            throw new ConcurrentModificationException("A newer market valuation is available; refresh the simulated decision");
        }
        PaperAccount.FillResult result = current.fill(command.side(), command.size(), command.price(),
                command.feeRate(), command.now());
        PaperAccount next = result.account().mark(command.price(), command.marketTime(), command.now());
        PaperFill fill = new PaperFill(null, next.sessionId(), command.decisionId(), command.strategyId(),
                command.strategyConfigVersion(), command.strategyConfigSnapshotJson(), command.side(), command.size(),
                result.grossBase(), result.positionDelta(), command.price(), result.cashDelta(), result.feeQuote(),
                result.realizedPnl(), next.cash(), next.positionQuantity(), next.equity(), next.version(), command.now());
        mapper.insertFill(fill);
        update(next);
        // Persist the trade fact with the fill, even when risk controls are off
        // or a later optional risk-counter update fails.
        TradingRiskState riskState = risk(command.instId(), next.sessionId());
        riskState.setLastTradeTime(RiskStateTransitions.latestTradeTime(riskState.getLastTradeTime(), command.now()));
        if (mapper.updateRisk(next.sessionId(), write(riskState)) != 1) conflict();
        sample(next, command.strategyId(), command.strategyConfigVersion(), "FILL", command.now());
        return new AppliedFill(fill, next, false);
    }

    @Override public List<PaperAccount> sessions(String instId, Long beforeId, int limit) {
        return mapper.sessions(instId, beforeId, limit).stream().map(MyBatisPaperAccountStore::account).toList();
    }
    @Override public List<PaperFill> fills(String instId, String sessionId, Long beforeId, int limit) {
        return mapper.fills(instId, sessionId, beforeId, limit);
    }
    @Override public PaperDailyPerformance dailyPerformance(String instId, String sessionId, long throughVersion,
                                                             Instant fromInclusive, Instant toExclusive) {
        return mapper.dailyPerformance(instId, sessionId, throughVersion, fromInclusive, toExclusive);
    }
    @Override public List<PaperEquityPoint> equity(String instId, String sessionId, Long beforeId, int limit) {
        return mapper.equity(instId, sessionId, beforeId, limit);
    }
    @Override public TradingRiskState risk(String instId, String sessionId) {
        PaperAccountRow row = mapper.session(instId, sessionId);
        if (row == null) throw new IllegalStateException("Simulation session not found");
        return read(row.getRiskStateJson());
    }
    @Override
    @Transactional
    public TradingRiskState updateRisk(String instId, String sessionId, long expectedAccountVersion,
                                      UnaryOperator<TradingRiskState> transition) {
        PaperAccount current = lock(instId);
        if (current == null || !Objects.equals(current.sessionId(), sessionId)
                || current.version() != expectedAccountVersion) conflict();
        TradingRiskState stored = risk(instId, sessionId);
        String committedTradeTime = stored.getLastTradeTime();
        TradingRiskState next = Objects.requireNonNull(transition.apply(stored), "Risk transition result must not be null");
        next.setLastTradeTime(RiskStateTransitions.latestTradeTime(next.getLastTradeTime(),
                committedTradeTime == null || committedTradeTime.isBlank() ? null : Instant.parse(committedTradeTime)));
        if (mapper.updateRisk(sessionId, write(next)) != 1) conflict();
        return next;
    }

    private PaperAccount lock(String instId) {
        initializeOnce(instId, () -> mapper.insertHeadIfAbsent(instId), () -> mapper.lockHead(instId) != null);
        mapper.lockHead(instId);
        return active(instId);
    }
    private void update(PaperAccount account) {
        if (mapper.updateSession(row(account)) != 1) conflict();
    }
    private void sample(PaperAccount a, String strategyId, Long version, String kind, Instant now) {
        mapper.insertEquity(new PaperEquityPoint(null, a.sessionId(), now, a.cash(), a.positionQuantity(),
                a.lastPrice(), a.valuationAt(), a.equity(), a.realizedPnl(), a.unrealizedPnl(), a.version(), strategyId, version, kind));
    }
    private String write(TradingRiskState state) {
        try { return json.writeValueAsString(state); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize simulated risk state", e); }
    }
    private TradingRiskState read(String value) {
        try { return json.readValue(value, TradingRiskState.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot restore simulated risk state", e); }
    }
    private static void conflict() { throw new ConcurrentModificationException("Simulation session changed; refresh and retry"); }
    static PaperAccount account(PaperAccountRow r) {
        return r == null ? null : new PaperAccount(r.getId(), r.getSessionId(), r.getInstId(), r.getQuoteCcy(),
                r.getStatus(), r.getInitialCash(), r.getCash(), r.getPositionQuantity(), r.getPositionCost(),
                r.getRealizedPnl(), r.getTotalFeesQuote(), r.getLastPrice(), r.getValuationAt(), r.getVersion(), r.getCreatedAt(), r.getUpdatedAt());
    }
    private static PaperAccountRow row(PaperAccount a) {
        return new PaperAccountRow().setId(a.id()).setSessionId(a.sessionId()).setInstId(a.instId())
                .setQuoteCcy(a.quoteCcy()).setStatus(a.status()).setInitialCash(a.initialCash()).setCash(a.cash())
                .setPositionQuantity(a.positionQuantity()).setPositionCost(a.positionCost()).setRealizedPnl(a.realizedPnl())
                .setTotalFeesQuote(a.totalFeesQuote()).setLastPrice(a.lastPrice()).setValuationAt(a.valuationAt()).setVersion(a.version())
                .setCreatedAt(a.createdAt()).setUpdatedAt(a.updatedAt());
    }
    /** Memoize only committed existence; values and transactional locks remain in MySQL. */
    private void initializeOnce(Object key, Runnable insert, java.util.function.BooleanSupplier exists) {
        if (initializedRows.get(key) != null) {
            if (exists.getAsBoolean()) {
                return;
            }
            // A restored or manually cleared database must not retain an old existence marker.
            initializedRows.evictIfPresent(key);
        }
        insert.run();
        // Eviction only repeats a safe insert; bound account and replay identities.
        if (((java.util.Map<?, ?>) initializedRows.getNativeCache()).size() >= 1024) {
            initializedRows.clear();
        }
        initializedRows.put(key, Boolean.TRUE);
    }
}
