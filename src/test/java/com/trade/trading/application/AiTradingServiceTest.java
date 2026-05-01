package com.trade.trading.application;

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
import com.trade.trading.config.AiTradingProperties;
import com.trade.trading.execution.OrderSizingService;
import com.trade.trading.execution.TradingOrderExecutor;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.model.AiDecisionAuditRecord;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.AiDecisionAuditSink;
import com.trade.trading.persistence.TradingStateRepository;
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

class AiTradingServiceTest {
    private static final AiDecisionAuditSink NOOP_AUDIT_SINK = record -> {
    };

    @TempDir
    Path tempDir;

    @Test
    void buyPlacesCappedSpotMarketQuoteOrder() {
        AiTradingProperties properties = properties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        AiTradingService service = service(
                properties,
                okxApi,
                "{\"action\":\"BUY\",\"reason\":\"test buy\",\"buyQuoteAmountUsdt\":500}",
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
        AiTradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("sell", "0.1234", "50000"));
        AiTradingService service = service(
                properties,
                okxApi,
                "{\"action\":\"SELL\",\"reason\":\"test sell\",\"sellBaseAmountBtc\":2}",
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
        AiTradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(
                filledOrder("buy", "0.002", "50000"),
                rejectedOrder("51008", "Order failed. Insufficient balance.")
        );
        AiTradingService service = service(
                properties,
                okxApi,
                "{\"action\":\"BUY\",\"reason\":\"test buy\",\"buyQuoteAmountUsdt\":500}",
                context("0", "1000")
        );

        boolean result = service.runDecision(TradingTrigger.scheduled());

        assertFalse(result);
        assertEquals("buy", okxApi.orderReq.getSide());
    }

    @Test
    void buyTracksBaseFeeAdjustedCostAndDecisionRecord() {
        AiTradingProperties properties = properties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000", "-0.000001", "BTC"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("fee-state.json"));
        AiTradingService service = new AiTradingService(
                prompt -> "{\"action\":\"BUY\",\"reason\":\"test buy\",\"buyQuoteAmountUsdt\":100}",
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
    void sendsAuditRecordWithPromptResponseTriggerAndExecutionStatus() {
        AiTradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("audit-state.json"));
        CapturingAuditSink auditSink = new CapturingAuditSink();
        String aiResponse = "{\"action\":\"BUY\",\"reason\":\"audit buy\",\"buyQuoteAmountUsdt\":5}";
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
        assertEquals(audit.getDecisionId(), audit.getDecisionRecord().getDecisionId());
        assertNotNull(audit.getPrompt());
    }

    private AiTradingService service(
            AiTradingProperties properties,
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
            AiTradingProperties properties
    ) {
        return new TradingOrderExecutor(okxApi, new OrderSizingService(properties), stateRepository, properties);
    }

    private static AiTradingProperties properties() {
        AiTradingProperties properties = new AiTradingProperties();
        properties.setOrderFillQueryDelayMs(0);
        return properties;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
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

        @Override
        public void save(AiDecisionAuditRecord record) {
            this.record = record;
        }
    }
}
