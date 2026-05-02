package com.trade.polymarket.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketClientProperties;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.PolymarketOrderRequest;
import com.trade.trading.support.TradingMath;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PolymarketPythonOrderRunner implements PolymarketOrderRunner {
    private final AiPolymarketProperties properties;
    private final PolymarketClientProperties clientProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PolymarketPythonOrderRunner(
            AiPolymarketProperties properties,
            PolymarketClientProperties clientProperties
    ) {
        this.properties = properties;
        this.clientProperties = clientProperties;
    }

    @Override
    public String placeOrder(PolymarketOrderRequest request) {
        AiPolymarketProperties.ExecutionProperties execution = properties.getExecution();
        Path scriptPath = resolvedScriptPath(execution.getScriptPath());
        ProcessBuilder builder = new ProcessBuilder(execution.getPythonCommand(), scriptPath.toString());
        builder.redirectErrorStream(false);

        try {
            Process process = builder.start();
            process.getOutputStream().write(buildPayload(request).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            boolean finished = process.waitFor(execution.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Polymarket order script timed out after "
                        + Duration.ofMillis(execution.getTimeoutMs()));
            }

            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0) {
                throw new RuntimeException("Polymarket order script failed, exitCode="
                        + process.exitValue()
                        + ", stderr="
                        + stderr
                        + ", stdout="
                        + stdout);
            }
            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Polymarket order script interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Run Polymarket order script error", e);
        }
    }

    private String buildPayload(PolymarketOrderRequest request) throws Exception {
        AiPolymarketProperties.ExecutionProperties execution = properties.getExecution();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", clientProperties.normalizedClobBaseUrl());
        payload.put("chainId", execution.getChainId());
        payload.put("signatureType", execution.getSignatureType());
        payload.put("orderType", execution.getOrderType());
        payload.put("privateKeyEnvName", execution.getPrivateKeyEnvName());
        payload.put("apiKeyEnvName", execution.getApiKeyEnvName());
        payload.put("apiSecretEnvName", execution.getApiSecretEnvName());
        payload.put("apiPassphraseEnvName", execution.getApiPassphraseEnvName());
        payload.put("funderAddressEnvName", execution.getFunderAddressEnvName());
        payload.put("tokenId", request.getTokenId());
        payload.put("side", firstText(request.getSide(), "BUY").toUpperCase());
        payload.put("price", plain(request.getPrice()));
        payload.put("size", plain(request.getSize()));
        payload.put("spendUsdc", plain(request.getSpendUsdc()));
        payload.put("tickSize", request.getTickSize());
        payload.put("negRisk", request.getNegRisk());
        payload.put("marketSlug", request.getMarketSlug());
        payload.put("outcome", request.getOutcome());
        return objectMapper.writeValueAsString(payload);
    }

    private static Path resolvedScriptPath(String scriptPath) {
        Path path = Path.of(scriptPath);
        if (path.isAbsolute()) {
            return path;
        }
        return Path.of("").toAbsolutePath().resolve(path).normalize();
    }

    private static String readAll(java.io.InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        inputStream.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String plain(BigDecimal value) {
        return TradingMath.plain(value);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
