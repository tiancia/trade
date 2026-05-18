package com.trade.trading.application;

import com.trade.ai.persistence.AiResponseParseErrorRecord;
import com.trade.ai.persistence.AiResponseParseErrorSink;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.decision.AiPromptBuilder;
import com.trade.trading.decision.AiTradingDecisionParser;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.execution.OrderSizingService;
import com.trade.trading.execution.TradingOrderExecutor;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.model.AiDecisionAuditRecord;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.AiDecisionAuditSink;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.RiskControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTradingServiceTest {
    private static final AiDecisionAuditSink NOOP_AUDIT_SINK = record -> {
    };

    @TempDir
    Path tempDir;

    @Test
    void buyPlacesCappedSpotMarketQuoteOrder() {
        TradingProperties properties = properties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        AiTradingService service = service(
                properties,
                okxApi,
                "{\"action\":\"BUY\",\"reason\":\"test buy\",\"buyQuoteAmountUsdt\":500,\"winProbability\":0.62,\"confidence\":0.74"
                        + contractFieldsJson() + "}",
                context("0", "1000")
        );

        service.runDecision(TradingTrigger.scheduled());

        assertEquals("BTC-USDT", okxApi.orderReq.getInstId());
        assertEquals("cash", okxApi.orderReq.getTdMode());
        assertEquals("buy", okxApi.orderReq.getSide());
        assertEquals("market", okxApi.orderReq.getOrdType());
        assertEquals("quote_ccy", okxApi.orderReq.getTgtCcy());
        assertEquals("100", okxApi.orderReq.getSz());
    }

    @Test
    void sellPlacesAvailableSpotMarketBaseOrder() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("sell", "0.1234", "50000"));
        AiTradingService service = service(
                properties,
                okxApi,
                "{\"action\":\"SELL\",\"reason\":\"test sell\",\"sellBaseAmountBtc\":2,\"winProbability\":0.58,\"confidence\":0.7"
                        + contractFieldsJson() + "}",
                context("0.123456", "1000")
        );

        service.runDecision(TradingTrigger.scheduled());

        assertEquals("BTC-USDT", okxApi.orderReq.getInstId());
        assertEquals("cash", okxApi.orderReq.getTdMode());
        assertEquals("sell", okxApi.orderReq.getSide());
        assertEquals("market", okxApi.orderReq.getOrdType());
        assertEquals("base_ccy", okxApi.orderReq.getTgtCcy());
        assertEquals("0.1234", okxApi.orderReq.getSz());
    }

    @Test
    void buyRejectedByOkxReturnsFalseAfterParsingOrderFailureDetails() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(
                filledOrder("buy", "0.002", "50000"),
                rejectedOrder("51008", "Order failed. Insufficient balance.")
        );
        AiTradingService service = service(
                properties,
                okxApi,
                "{\"action\":\"BUY\",\"reason\":\"test buy\",\"buyQuoteAmountUsdt\":500,\"winProbability\":0.62,\"confidence\":0.74"
                        + contractFieldsJson() + "}",
                context("0", "1000")
        );

        boolean result = service.runDecision(TradingTrigger.scheduled());

        assertFalse(result);
        assertEquals("buy", okxApi.orderReq.getSide());
    }

    @Test
    void nonHoldDecisionFailingStrategyContractIsSkippedBeforeOrder() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("risk-gate-state.json"));
        AiTradingService service = new AiTradingService(
                prompt -> """
                        {
                          "action": "BUY",
                          "reason": "weak setup",
                          "buyQuoteAmountUsdt": 100,
                          "winProbability": 0.55,
                          "confidence": 0.70,
                          "objectiveAlignment": "FAIL",
                          "expectedNetEdgePercent": 0.0005,
                          "riskRewardRatio": 1.2,
                          "thesisChangeEvidence": "minor price movement",
                          "strategyBias": "LONG",
                          "strategyThesis": "weak long",
                          "strategyInvalidation": "support fails",
                          "strategyHorizon": "4-24 hours"
                        }
                        """,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(properties),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );

        service.runDecision(TradingTrigger.scheduled());

        assertNull(okxApi.orderReq);
        TradingDecisionRecord record = stateRepository.getState().getRecentDecisions().getFirst();
        assertEquals(TradingAction.BUY, record.getAction());
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("BUY skipped: objectiveAlignment must be PASS for non-HOLD actions", record.getSkipReason());
    }

    @Test
    void riskBlockedBuyIsSkippedBeforeOrderAndRecorded() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("risk-block-state.json"));
        stateRepository.recordRiskState(new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000"))
                .setDayStartDate("2026-05-17")
                .setConsecutiveOpenActions(2));
        AiTradingService service = new AiTradingService(
                prompt -> "{\"action\":\"BUY\",\"reason\":\"valid but too many opens\",\"buyQuoteAmountUsdt\":100,\"winProbability\":0.62,\"confidence\":0.74"
                        + contractFieldsJson() + "}",
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(properties),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );

        service.runDecision(TradingTrigger.scheduled());

        assertNull(okxApi.orderReq);
        TradingDecisionRecord record = stateRepository.getState().getRecentDecisions().getFirst();
        assertEquals(TradingAction.BUY, record.getAction());
        assertEquals("valid but too many opens", record.getReason());
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertTrue(record.getSkipReason().startsWith("RISK_CONSECUTIVE_OPEN_ACTIONS:"));
    }

    @Test
    void buyTracksBaseFeeAdjustedCostAndDecisionRecord() {
        TradingProperties properties = properties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000", "-0.000001", "BTC"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("fee-state.json"));
        AiTradingService service = new AiTradingService(
                prompt -> "{\"action\":\"BUY\",\"reason\":\"test buy\",\"buyQuoteAmountUsdt\":100,\"winProbability\":0.62,\"confidence\":0.74"
                        + contractFieldsJson() + "}",
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );

        service.runDecision(TradingTrigger.scheduled());

        TradingState state = stateRepository.getState();
        assertDecimal("0.001999", state.getTrackedBaseAmount());
        BigDecimal expectedAverageCost = new BigDecimal("100")
                .divide(new BigDecimal("0.001999"), 18, RoundingMode.HALF_UP);
        assertDecimal(expectedAverageCost.toPlainString(), state.getAverageCost());
        assertEquals(1, state.getRecentDecisions().size());
        assertEquals("FILLED", state.getRecentDecisions().getFirst().getExecutionStatus());
        assertDecimal("-0.000001", state.getRecentDecisions().getFirst().getFee());
        assertEquals("BTC", state.getRecentDecisions().getFirst().getFeeCcy());
    }

    @Test
    void derivativeCloseLongPlacesReduceOnlyOrderAndPersistsStrategyState() {
        TradingProperties properties = properties();
        properties.setInstId("BTC-USDT-SWAP");
        properties.setInstType("SWAP");
        properties.setTdMode("isolated");
        properties.setMaxDerivativeOrderSize(new BigDecimal("5"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("sell", "2", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("derivative-state.json"));
        String aiResponse = """
                {
                  "action": "CLOSE_LONG",
                  "reason": "thesis invalidated",
                  "orderSize": 2,
                  "winProbability": 0.57,
                  "confidence": 0.73,
                  "objectiveAlignment": "PASS",
                  "expectedNetEdgePercent": 0.003,
                  "riskRewardRatio": 1.8,
                  "thesisChangeEvidence": "long thesis invalidated by decisive support loss",
                  "strategyBias": "NEUTRAL",
                  "strategyThesis": "stand aside until trend resets",
                  "strategyInvalidation": "new breakout with volume",
                  "strategyHorizon": "1-2 days"
                }
                """;
        AiTradingService service = new AiTradingService(
                prompt -> aiResponse,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(properties),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );

        service.runDecision(TradingTrigger.scheduled());

        assertEquals("BTC-USDT-SWAP", okxApi.orderReq.getInstId());
        assertEquals("isolated", okxApi.orderReq.getTdMode());
        assertEquals("sell", okxApi.orderReq.getSide());
        assertEquals("long", okxApi.orderReq.getPosSide());
        assertNull(okxApi.orderReq.getReduceOnly());
        assertNull(okxApi.orderReq.getTgtCcy());
        assertEquals("2", okxApi.orderReq.getSz());

        TradingState state = stateRepository.getState();
        assertEquals("NEUTRAL", state.getStrategyState().getBias());
        assertEquals("stand aside until trend resets", state.getStrategyState().getThesis());
        assertEquals("new breakout with volume", state.getStrategyState().getInvalidation());
        assertEquals("1-2 days", state.getStrategyState().getHorizon());
        assertEquals(1, state.getRecentDecisions().size());
        assertEquals(TradingAction.CLOSE_LONG, state.getRecentDecisions().getFirst().getAction());
    }

    @Test
    void openShortIsSkippedWhenStrategyDisallowsShorts() {
        TradingProperties properties = properties();
        properties.setInstId("BTC-USDT-SWAP");
        properties.setInstType("SWAP");
        properties.setTdMode("isolated");
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("sell", "2", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("short-disabled-state.json"));
        AiTradingService service = new AiTradingService(
                prompt -> """
                        {
                          "action":"OPEN_SHORT",
                          "reason":"breakdown",
                          "orderSize":2,
                          "winProbability":0.6,
                          "confidence":0.75,
                          "objectiveAlignment":"PASS",
                          "expectedNetEdgePercent":0.003,
                          "riskRewardRatio":1.8,
                          "thesisChangeEvidence":"breakdown confirmed by volume",
                          "strategyBias":"SHORT",
                          "strategyThesis":"short while price remains below breakdown level",
                          "strategyInvalidation":"price reclaims breakdown level with volume",
                          "strategyHorizon":"4-24 hours"
                        }
                        """,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(properties),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );

        service.runDecision(TradingTrigger.scheduled());

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", stateRepository.getState().getRecentDecisions().getFirst().getExecutionStatus());
    }

    @Test
    void derivativeCloseLongUsesReduceOnlyInNetPositionMode() {
        TradingProperties properties = properties();
        properties.setInstId("BTC-USDT-SWAP");
        properties.setInstType("SWAP");
        properties.setTdMode("isolated");
        properties.setPositionMode("net");
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("sell", "1", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("net-derivative-state.json"));
        AiTradingService service = new AiTradingService(
                prompt -> """
                        {
                          "action":"CLOSE_LONG",
                          "reason":"reduce exposure",
                          "orderSize":1,
                          "winProbability":0.58,
                          "confidence":0.7,
                          "objectiveAlignment":"PASS",
                          "expectedNetEdgePercent":0.003,
                          "riskRewardRatio":1.8,
                          "thesisChangeEvidence":"long thesis invalidated by support failure",
                          "strategyBias":"NEUTRAL",
                          "strategyThesis":"stand aside until trend resets",
                          "strategyInvalidation":"new breakout with volume",
                          "strategyHorizon":"4-24 hours"
                        }
                        """,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(properties),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );

        service.runDecision(TradingTrigger.scheduled());

        assertEquals("sell", okxApi.orderReq.getSide());
        assertNull(okxApi.orderReq.getPosSide());
        assertEquals("true", okxApi.orderReq.getReduceOnly());
    }

    @Test
    void sendsAuditRecordWithPromptResponseTriggerAndExecutionStatus() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("audit-state.json"));
        CapturingAuditSink auditSink = new CapturingAuditSink();
        String aiResponse = "{\"action\":\"BUY\",\"reason\":\"audit buy\",\"buyQuoteAmountUsdt\":5,\"winProbability\":0.63,\"confidence\":0.77"
                + contractFieldsJson() + "}";
        TradingTrigger trigger = TradingTrigger.event("price moved", Map.of("priceMovePercent", "0.03"));
        TradingDecisionContext context = context("0", "1000")
                .setAiParametersJson("{\"instrumentId\":\"BTC-USDT\"}");
        AiTradingService service = new AiTradingService(
                prompt -> aiResponse,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context),
                new AiPromptBuilder(),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                auditSink,
                properties
        );

        service.runDecision(trigger);

        AiDecisionAuditRecord audit = auditSink.record;
        assertNotNull(audit);
        assertNotNull(audit.getDecisionId());
        assertEquals(trigger, audit.getTrigger());
        assertEquals("{\"instrumentId\":\"BTC-USDT\"}", audit.getContext().getAiParametersJson());
        assertEquals(aiResponse, audit.getRawAiResponse());
        assertEquals("FILLED", audit.getDecisionRecord().getExecutionStatus());
        assertDecimal("0.63", audit.getAiDecision().getWinProbability());
        assertDecimal("0.77", audit.getAiDecision().getConfidence());
        assertDecimal("0.63", audit.getDecisionRecord().getWinProbability());
        assertDecimal("0.77", audit.getDecisionRecord().getConfidence());
        assertEquals(audit.getDecisionId().toString(), audit.getDecisionRecord().getDecisionId());
        assertNotNull(audit.getPrompt());
    }

    @Test
    void invalidAiDecisionFallsBackToHoldAndRecordsParseError() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("invalid-ai-state.json"));
        CapturingAuditSink auditSink = new CapturingAuditSink();
        CapturingParseErrorSink parseErrorSink = new CapturingParseErrorSink();
        String aiResponse = "{\"action\":\"WAIT\",\"reason\":\"unsupported action\"}";
        AiTradingService service = new AiTradingService(
                prompt -> aiResponse,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context("0", "1000")),
                new AiPromptBuilder(),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                auditSink,
                properties,
                parseErrorSink
        );

        boolean result = service.runDecision(TradingTrigger.scheduled());

        assertTrue(result);
        assertNull(okxApi.orderReq);
        assertNotNull(parseErrorSink.record);
        assertEquals("OKX_TRADING", parseErrorSink.record.getSource());
        assertEquals("DECISION_PAYLOAD", parseErrorSink.record.getPhase());
        assertEquals("1", parseErrorSink.record.getRelatedId());
        assertEquals(aiResponse, parseErrorSink.record.getRawResponse());
        assertEquals("HOLD", parseErrorSink.record.getFallbackAction());
        assertTrue(parseErrorSink.record.getErrorMessage().contains("action must be"));
        assertEquals(TradingAction.HOLD, auditSink.record.getAiDecision().getAction());
        assertEquals("HELD", auditSink.record.getDecisionRecord().getExecutionStatus());
    }

    private AiTradingService service(
            TradingProperties properties,
            FakeOkxApi okxApi,
            String aiResponse,
            TradingDecisionContext context
    ) {
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("state.json"));
        return new AiTradingService(
                prompt -> aiResponse,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context),
                new AiPromptBuilder(),
                orderExecutor(okxApi, stateRepository, properties),
                stateRepository,
                NOOP_AUDIT_SINK,
                properties
        );
    }

    private static TradingOrderExecutor orderExecutor(
            OkxApi okxApi,
            TradingStateRepository stateRepository,
            TradingProperties properties
    ) {
        return new TradingOrderExecutor(
                okxApi,
                new OrderSizingService(properties),
                stateRepository,
                properties,
                new RiskControlService(properties, stateRepository)
        );
    }

    private static TradingProperties properties() {
        TradingProperties properties = new TradingProperties();
        properties.setOrderFillQueryDelayMs(0);
        return properties;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static String contractFieldsJson() {
        return """
                ,"objectiveAlignment":"PASS","expectedNetEdgePercent":0.003,"riskRewardRatio":1.8,"thesisChangeEvidence":"fresh market evidence supports the action","strategyBias":"LONG","strategyThesis":"trade only while edge remains confirmed","strategyInvalidation":"edge disappears or support fails","strategyHorizon":"4-24 hours"
                """.trim();
    }

    private static TradingDecisionContext context(String baseAvail, String quoteAvail) {
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");

        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setAvailBal(baseAvail);

        BalanceDetail quote = new BalanceDetail();
        quote.setCcy("USDT");
        quote.setAvailBal(quoteAvail);

        InstrumentInfoResp instrument = new InstrumentInfoResp();
        instrument.setMinSz("0.00001");
        instrument.setLotSz("0.0001");
        instrument.setMaxMktAmt("1000");
        instrument.setMaxMktSz("10");

        return new TradingDecisionContext()
                .setAiParameters(Map.of())
                .setAiParametersJson("{}")
                .setTicker(ticker)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setInstrument(instrument)
                .setTradingState(new TradingState());
    }

    private static OrderInfoResp filledOrder(String side, String fillSize, String avgPrice) {
        return filledOrder(side, fillSize, avgPrice, null, null);
    }

    private static OrderInfoResp filledOrder(
            String side,
            String fillSize,
            String avgPrice,
            String fee,
            String feeCcy
    ) {
        OrderInfoResp order = new OrderInfoResp();
        order.setSide(side);
        order.setAccFillSz(fillSize);
        order.setAvgPx(avgPrice);
        order.setState("filled");
        order.setFee(fee);
        order.setFeeCcy(feeCcy);
        return order;
    }

    private static OkxResponse<OrderActionResp> rejectedOrder(String sCode, String sMsg) {
        OrderActionResp resp = new OrderActionResp();
        resp.setOrdId("");
        resp.setClOrdId("client-1");
        resp.setSCode(sCode);
        resp.setSMsg(sMsg);

        OkxResponse<OrderActionResp> response = OkxResponse.success("Operation failed.", List.of(resp));
        response.setCode("1");
        return response;
    }

    private static class FakeMarketContextCollector extends MarketContextCollector {
        private final TradingDecisionContext context;

        FakeMarketContextCollector(TradingDecisionContext context) {
            super(null, null, null);
            this.context = context;
        }

        @Override
        public TradingDecisionContext collect(TradingTrigger trigger) {
            return context;
        }
    }

    private static class FakeOkxApi extends OkxApi {
        private final OrderInfoResp orderInfoResp;
        private final OkxResponse<OrderActionResp> placeOrderResponse;
        private PlaceOrderReq orderReq;

        FakeOkxApi(OrderInfoResp orderInfoResp) {
            this(orderInfoResp, null);
        }

        FakeOkxApi(OrderInfoResp orderInfoResp, OkxResponse<OrderActionResp> placeOrderResponse) {
            super(new NoopOkxRestClient());
            this.orderInfoResp = orderInfoResp;
            this.placeOrderResponse = placeOrderResponse;
        }

        @Override
        public OkxResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
            this.orderReq = req;
            if (placeOrderResponse != null) {
                return placeOrderResponse;
            }
            OrderActionResp resp = new OrderActionResp();
            resp.setOrdId("1");
            resp.setClOrdId(req.getClOrdId());
            resp.setSCode("0");
            return OkxResponse.success(List.of(resp));
        }

        @Override
        public OkxResponse<OrderInfoResp> getOrder(OrderQueryReq req) {
            return OkxResponse.success(List.of(orderInfoResp));
        }
    }

    private static class NoopOkxRestClient implements OkxRestClient {
        @Override
        public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }

        @Override
        public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }
    }

    private static class CapturingAuditSink implements AiDecisionAuditSink {
        private AiDecisionAuditRecord record;
        private long nextId = 1;

        @Override
        public Long start(AiDecisionAuditRecord record) {
            record.setDecisionId(nextId++);
            return record.getDecisionId();
        }

        @Override
        public void save(AiDecisionAuditRecord record) {
            this.record = record;
        }
    }

    private static class CapturingParseErrorSink implements AiResponseParseErrorSink {
        private AiResponseParseErrorRecord record;

        @Override
        public void save(AiResponseParseErrorRecord record) {
            this.record = record;
        }
    }
}
