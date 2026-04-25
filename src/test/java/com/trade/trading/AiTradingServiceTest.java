package com.trade.trading;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxClient;
import com.trade.common.CommonResponse;
import com.trade.dto.BalanceDetail;
import com.trade.dto.InstrumentInfoResp;
import com.trade.dto.OrderActionResp;
import com.trade.dto.OrderInfoResp;
import com.trade.dto.OrderQueryReq;
import com.trade.dto.PlaceOrderReq;
import com.trade.dto.TickerResp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        private PlaceOrderReq orderReq;

        FakeOkxApi(OrderInfoResp orderInfoResp) {
            super(new OkxClient());
            this.orderInfoResp = orderInfoResp;
        }

        @Override
        public CommonResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
            this.orderReq = req;
            OrderActionResp resp = new OrderActionResp();
            resp.setOrdId("1");
            resp.setClOrdId(req.getClOrdId());
            resp.setSCode("0");
            return CommonResponse.success(List.of(resp));
        }

        @Override
        public CommonResponse<OrderInfoResp> getOrder(OrderQueryReq req) {
            return CommonResponse.success(List.of(orderInfoResp));
        }
    }
}
