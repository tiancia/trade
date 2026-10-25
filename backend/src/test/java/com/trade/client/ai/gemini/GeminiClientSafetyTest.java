package com.trade.client.ai.gemini;

import com.trade.client.ai.AiResponseParseException;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeminiClientSafetyTest {
    private static final String SENSITIVE = "synthetic-private-provider-content";

    @Test
    void providerErrorBodyCannotLeakThroughTheExceptionChain() throws Exception {
        var client = clientReturning(403, "{\"error\":\"" + SENSITIVE + "\"}");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client.generateJson("synthetic prompt"));
        assertTrue(stackTrace(failure).contains("403"));
        assertFalse(stackTrace(failure).contains(SENSITIVE));
    }

    @Test
    void malformedResponseRemainsAvailableToAuditWithoutLeakingThroughParserExceptions() throws Exception {
        var client = clientReturning(200, SENSITIVE);
        AiResponseParseException failure = assertThrows(AiResponseParseException.class,
                () -> client.generateJson("synthetic prompt"));
        assertEquals(SENSITIVE, failure.getRawResponse());
        assertFalse(stackTrace(failure).contains(SENSITIVE));
    }

    @Test
    void interruptedRequestPreservesTheThreadCancellationSignal() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("synthetic cancellation"));
        GeminiClient client = new GeminiClient(properties(), http);
        try {
            assertThrows(RuntimeException.class, () -> client.generateJson("synthetic prompt"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private static GeminiClient clientReturning(int status, String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return new GeminiClient(properties(), http);
    }

    private static GeminiClientProperties properties() {
        GeminiClientProperties properties = new GeminiClientProperties();
        properties.setApiKey("synthetic-key");
        return properties;
    }

    private static String stackTrace(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
