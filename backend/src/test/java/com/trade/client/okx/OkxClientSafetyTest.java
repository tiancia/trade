package com.trade.client.okx;

import com.trade.client.okx.dto.CandleResp;
import org.junit.jupiter.api.Test;

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
