package com.trade.trading.service;

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
import com.trade.trading.ai.AiPromptBuilder;
import com.trade.trading.ai.AiTradingDecisionParser;
import com.trade.trading.config.AiTradingProperties;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.TradingStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiTradingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void buyPlacesCappedSpotMarketQuoteOrder() {
        AiTradingProperties properties = properties();
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

    private AiTradingService service(
            AiTradingProperties properties,
            FakeOkxApi okxApi,
            String aiResponse,
            TradingDecisionContext context
    ) {
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("state.json"));
        return new AiTradingService(
                okxApi,
                prompt -> aiResponse,
                new AiTradingDecisionParser(),
                new FakeMarketContextCollector(context),
                new AiPromptBuilder(),
                new OrderSizingService(properties),
                stateRepository,
                properties
        );
    }

    private static AiTradingProperties properties() {
        AiTradingProperties properties = new AiTradingProperties();
        properties.setOrderFillQueryDelayMs(0);
        return properties;
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
        OrderInfoResp order = new OrderInfoResp();
        order.setSide(side);
        order.setAccFillSz(fillSize);
        order.setAvgPx(avgPrice);
        order.setState("filled");
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
}
