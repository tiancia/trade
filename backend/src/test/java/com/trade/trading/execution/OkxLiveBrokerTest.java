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
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.RiskControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    }

    private OkxLiveBroker broker(TradingProperties properties, OkxApi okxApi) {
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("state-" + System.nanoTime() + ".json"));
        return new OkxLiveBroker(
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

        FakeOkxApi(OrderInfoResp orderInfoResp) {
            super(new NoopOkxRestClient());
            this.orderInfoResp = orderInfoResp;
        }

        @Override
        public OkxResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
            this.orderReq = req;
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
}
