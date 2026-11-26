package com.trade.client.okx;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.PlaceOrderReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OkxClientSafetyTest {
    private static final String SENSITIVE = "synthetic-private-account-content";

    @ParameterizedTest
    @CsvSource({"400,51000", "400,50014", "401,50113"})
    void explicitOrderRefusalRetainsOnlyTheProviderCode(int httpStatus, String code) throws Exception {
        var request = new PlaceOrderReq().setClOrdId("this-order");
        for (String body : java.util.List.of(
                "{\"code\":\"" + code + "\",\"msg\":\"" + SENSITIVE + "\",\"data\":[]}",
                "{\"code\":\"1\",\"data\":[{\"clOrdId\":\"this-order\",\"sCode\":\"" + code + "\",\"sMsg\":\"" + SENSITIVE + "\"}]}")) {
            var response = clientReturning(httpStatus, body).post(OkxEndpoints.TRADE_ORDER, request, false, OrderActionResp.class);
            assertEquals(code, response.getCode());
            assertEquals("Request rejected (HTTP " + httpStatus + ")", response.getMsg());
            assertNull(response.getData());
        }
    }

    @Test
    void ambiguousAndMismatchedHttpFailuresNeverBecomeOrderRefusals() throws Exception {
        var request = new PlaceOrderReq().setClOrdId("this-order");
        for (String body : java.util.List.of("not json", "{\"code\":\"0\"}", "{\"code\":\"51016\"}", "{\"code\":\"51149\"}",
                "{\"code\":\"50071\"}", "{\"code\":\"51000\",\"code\":\"0\"}",
                "{\"code\":\"51000\"} {\"code\":\"0\"}",
                "{\"code\":\"51000\",\"data\":[{\"sCode\":\"51000\",\"clOrdId\":\"another-order\"}]}",
                "{\"code\":\"51000\",\"data\":[{\"sCode\":\"0\",\"clOrdId\":\"this-order\"}]}")) {
            var client = clientReturning(400, body);
            assertThrows(RuntimeException.class, () -> client.post(OkxEndpoints.TRADE_ORDER, request, false, OrderActionResp.class));
        }
        var serverError = clientReturning(500, "{\"code\":\"51000\"}");
        assertThrows(RuntimeException.class, () -> serverError.post(OkxEndpoints.TRADE_ORDER, request, false, OrderActionResp.class));
    }

    @Test
    void httpErrorBodyCannotLeakThroughTheExceptionChain() throws Exception {
        OkxClient client = clientReturning(401, "{\"error\":\"" + SENSITIVE + "\"}");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client.get("/api/v5/market/candles", null, false, CandleResp.class));
        assertTrue(stackTrace(failure).contains("401"));
        assertFalse(stackTrace(failure).contains(SENSITIVE));
    }

    @Test
    void malformedAccountResponseCannotLeakThroughParserExceptions() throws Exception {
        OkxClient client = clientReturning(200, SENSITIVE);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client.get("/api/v5/account/balance", null, false, CandleResp.class));
        assertEquals("Parse OKX response error", failure.getMessage());
        assertFalse(stackTrace(failure).contains(SENSITIVE));
    }

    @Test
    void interruptedRequestPreservesTheThreadCancellationSignal() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("synthetic cancellation"));
        OkxClient client = new OkxClient(new OkxClientProperties(), http);
        try {
            assertThrows(RuntimeException.class,
                    () -> client.get("/api/v5/market/candles", null, false, CandleResp.class));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private static OkxClient clientReturning(int status, String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return new OkxClient(new OkxClientProperties(), http);
    }

    private static String stackTrace(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
