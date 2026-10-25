package com.trade.client.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenAiCompatibleAiTextClientSafetyTest {
    private static final String SENSITIVE = "synthetic-private-provider-content";

    @Test
    void successfulContentIsReturnedWithoutBeingCopiedToApplicationLogs() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleAiTextClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            var client = clientReturning(200, "{\"choices\":[{\"message\":{\"content\":\"" + SENSITIVE + "\"}}]}");
            assertEquals(SENSITIVE, client.generateJson("synthetic prompt"));
            assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains(SENSITIVE)));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void providerErrorBodyCannotLeakThroughExceptionsOrLogs() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleAiTextClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            var client = clientReturning(401, "{\"error\":\"" + SENSITIVE + "\"}");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> client.generateJson("synthetic prompt"));
            assertTrue(failure.getMessage().contains("401"));
            assertFalse(stackTrace(failure).contains(SENSITIVE));
            assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains(SENSITIVE)));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void malformedResponseRemainsAvailableToAuditWithoutLeakingThroughParserExceptions() throws Exception {
        var client = clientReturning(200, SENSITIVE);
        AiResponseParseException failure = assertThrows(AiResponseParseException.class,
                () -> client.generateJson("synthetic prompt"));
        assertEquals(SENSITIVE, failure.getRawResponse());
        assertFalse(stackTrace(failure).contains(SENSITIVE));
    }

    @SuppressWarnings("unchecked")
    private static OpenAiCompatibleAiTextClient clientReturning(int status, String body) throws Exception {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.OPENAI_COMPATIBLE);
        properties.setBaseUrl("https://synthetic.example");
        properties.setModel("synthetic-model");
        properties.setApiKey("synthetic-key");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return new OpenAiCompatibleAiTextClient(properties, http);
    }

    private static String stackTrace(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
