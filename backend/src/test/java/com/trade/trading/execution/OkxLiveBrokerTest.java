package com.trade.trading.execution;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.order.InMemoryTradingOrderRepository;
import com.trade.trading.order.OrderIdempotencyKeyFactory;
import com.trade.trading.order.OrderLifecycleService;
import com.trade.trading.order.OrderSettlementService;
import com.trade.trading.order.OrderStatus;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.RiskControlService;
import com.trade.trading.risk.FundSafetyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

class OkxLiveBrokerTest {
    @TempDir
    Path tempDir;

    @Test
    void liveBrokerRequiresExplicitDoubleEnableBeforeOkxOrder() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, okxApi).execute(buyDecision(), context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
    }

    @Test
    void liveBrokerPlacesSpotMarketQuoteOrderWhenEnabled() {
        TradingProperties properties = properties();
        properties.setExecutionMode(TradingProperties.ExecutionMode.LIVE);
        properties.setLiveEnabled(true);
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, okxApi).execute(buyDecision(), context("0", "1000"), record);

        assertEquals("BTC-USDT", okxApi.orderReq.getInstId());
        assertEquals("cash", okxApi.orderReq.getTdMode());
        assertEquals("buy", okxApi.orderReq.getSide());
        assertEquals("market", okxApi.orderReq.getOrdType());
        assertEquals("quote_ccy", okxApi.orderReq.getTgtCcy());
        assertEquals("100", okxApi.orderReq.getSz());
        assertEquals("FILLED", record.getExecutionStatus());
        assertEquals(OrderStatus.FILLED.name(), record.getOrderStatus());
        assertEquals(32, record.getClientOrderId().length());
    }

    @Test
    void liveDerivativeOrderFailsClosedUntilItsFinancialLedgerIsImplemented() {
        TradingProperties properties = liveProperties();
        properties.setInstType("SWAP");
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "1", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();
        StrategyDecision decision = new StrategyDecision()
                .setStrategyId("test")
                .setAction(TradingAction.OPEN_LONG)
                .setOrderSize(BigDecimal.ONE);

        broker(properties, okxApi).execute(decision, context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertTrue(record.getSkipReason().startsWith("Live derivative order blocked"));
    }

    @Test
    void sameBusinessOrderIsSubmittedOnlyOnce() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        OkxLiveBroker broker = broker(properties, okxApi, orders);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("same-decision");
        TradingDecisionRecord replay = new TradingDecisionRecord().setDecisionId("same-decision");

        broker.execute(buyDecision(), context("0", "1000"), first);
        broker.execute(buyDecision(), context("0", "1000"), replay);

        assertEquals(1, okxApi.placeOrderCount);
        assertEquals(first.getIdempotencyKey(), replay.getIdempotencyKey());
        assertEquals(first.getClientOrderId(), replay.getClientOrderId());
        assertTrue(replay.isIdempotentReplay());
        assertEquals(OrderStatus.FILLED.name(), replay.getOrderStatus());
    }

    @Test
    void persistentFundStopBlocksLiveSubmission() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        doThrow(new FundSafetyService.TradingFundsHaltedException("Live trading halted: test"))
                .when(fundSafetyService)
                .requireActive();
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(
                properties,
                okxApi,
                new InMemoryTradingOrderRepository(),
                fundSafetyService
        ).execute(buyDecision(), context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("Live trading halted: test", record.getSkipReason());
    }

    @Test
    void ambiguousSubmitIsReconciledWithoutSecondPlaceOrder() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        okxApi.failNextPlace = true;
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        OkxLiveBroker broker = broker(properties, okxApi, orders);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("timeout-decision");

        assertThrows(IllegalStateException.class,
                () -> broker.execute(buyDecision(), context("0", "1000"), first));
        assertEquals(OrderStatus.SUBMIT_UNKNOWN.name(), first.getOrderStatus());

        TradingDecisionRecord replay = new TradingDecisionRecord().setDecisionId("timeout-decision");
        broker.execute(buyDecision(), context("0", "1000"), replay);

        assertEquals(1, okxApi.placeOrderCount);
        assertTrue(replay.isIdempotentReplay());
        assertEquals(OrderStatus.FILLED.name(), replay.getOrderStatus());
    }

    private OkxLiveBroker broker(TradingProperties properties, OkxApi okxApi) {
        return broker(properties, okxApi, new InMemoryTradingOrderRepository());
    }

    private OkxLiveBroker broker(
            TradingProperties properties,
            OkxApi okxApi,
            InMemoryTradingOrderRepository orders
    ) {
        return broker(properties, okxApi, orders, mock(FundSafetyService.class));
    }

    private OkxLiveBroker broker(
            TradingProperties properties,
            OkxApi okxApi,
            InMemoryTradingOrderRepository orders,
            FundSafetyService fundSafetyService
    ) {
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("state-" + System.nanoTime() + ".json"));
        RiskControlService riskControlService = new RiskControlService(properties, stateRepository);
        OrderLifecycleService lifecycleService = new OrderLifecycleService(orders, new SimpleMeterRegistry());
        return new OkxLiveBroker(
                okxApi,
                new OrderSizingService(properties),
                properties,
                riskControlService,
                fundSafetyService,
                lifecycleService,
                new OrderSettlementService(
                        lifecycleService,
                        stateRepository,
                        riskControlService,
                        properties
                ),
                new OrderIdempotencyKeyFactory()
        );
    }

    private static TradingProperties liveProperties() {
        TradingProperties properties = properties();
        properties.setExecutionMode(TradingProperties.ExecutionMode.LIVE);
        properties.setLiveEnabled(true);
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        return properties;
    }

    private static TradingProperties properties() {
        TradingProperties properties = new TradingProperties();
        properties.setOrderFillQueryDelayMs(0);
        properties.getRisk().setEnabled(false);
        return properties;
    }

    private static StrategyDecision buyDecision() {
        return new StrategyDecision()
                .setStrategyId("test")
                .setAction(TradingAction.BUY)
                .setReason("test buy")
                .setBuyQuoteAmount(new BigDecimal("500"));
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
                .setTicker(ticker)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setInstrument(instrument);
    }

    private static OrderInfoResp filledOrder(String side, String fillSize, String avgPrice) {
        OrderInfoResp order = new OrderInfoResp();
        order.setSide(side);
        order.setAccFillSz(fillSize);
        order.setAvgPx(avgPrice);
        order.setState("filled");
        return order;
    }

    private static class FakeOkxApi extends OkxApi {
        private final OrderInfoResp orderInfoResp;
        private PlaceOrderReq orderReq;
        private int placeOrderCount;
        private boolean failNextPlace;

        FakeOkxApi(OrderInfoResp orderInfoResp) {
            super(new NoopOkxRestClient());
            this.orderInfoResp = orderInfoResp;
        }

        @Override
        public OkxResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
            placeOrderCount++;
            this.orderReq = req;
            if (failNextPlace) {
                failNextPlace = false;
                throw new IllegalStateException("simulated response timeout");
            }
            OrderActionResp resp = new OrderActionResp();
            resp.setOrdId("1");
            resp.setClOrdId(req.getClOrdId());
            resp.setSCode("0");
            return OkxResponse.success(List.of(resp));
        }

        @Override
        public OkxResponse<OrderInfoResp> getOrder(OrderQueryReq req) {
            orderInfoResp.setOrdId(req.getOrdId() == null ? "1" : req.getOrdId());
            orderInfoResp.setClOrdId(req.getClOrdId());
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
}
