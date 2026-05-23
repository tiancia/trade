package com.trade.polymarket.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketClientProperties;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.PolymarketOrderRequest;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges the Java scheduler to the Python CLOB order script.
 *
 * <p>Secrets are passed through environment variables, while order details are
 * sent as a single JSON payload on stdin.</p>
 */
@Component
public class PolymarketPythonOrderRunner implements PolymarketOrderRunner {
    private static final Logger log = LoggerFactory.getLogger(PolymarketPythonOrderRunner.class);

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
        if (!Files.isRegularFile(scriptPath)) {
            throw new RuntimeException("Polymarket order script not found: " + scriptPath);
        }
        ProcessBuilder builder = new ProcessBuilder(execution.getPythonCommand(), scriptPath.toString());
        builder.redirectErrorStream(false);
        configureSecretEnvironment(builder.environment(), execution);
        log.info(
                "Start Polymarket order script: command={}, scriptPath={}, timeoutMs={}, marketSlug={}, outcome={}, tokenId={}, side={}, price={}, size={}, orderType={}",
                execution.getPythonCommand(),
                scriptPath,
                execution.getTimeoutMs(),
                request.getMarketSlug(),
                request.getOutcome(),
                request.getTokenId(),
                request.getSide(),
                request.getPrice(),
                request.getSize(),
                request.getOrderType()
        );

        try {
            Process process = builder.start();
            String payload = buildPayload(request);
            log.debug("Polymarket order script payload: {}", payload);
            // Stdin avoids leaking order payload details into process arguments.
            process.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
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
                log.warn(
                        "Polymarket order script failed: exitCode={}, stdoutChars={}, stderrChars={}",
                        process.exitValue(),
                        stdout.length(),
                        stderr.length()
                );
                throw new RuntimeException("Polymarket order script failed, exitCode="
                        + process.exitValue()
                        + ", stderr="
                        + stderr
                        + ", stdout="
                        + stdout);
            }
            log.info(
                    "Polymarket order script finished: exitCode=0, stdoutChars={}, stderrChars={}",
                    stdout.length(),
                    stderr.length()
            );
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

    static void configureSecretEnvironment(
            Map<String, String> environment,
            AiPolymarketProperties.ExecutionProperties execution
    ) {
        putRequiredSecret(
                environment,
                execution.getPrivateKeyEnvName(),
                execution.getPrivateKey(),
                "trade.polymarket.execution.private-key",
                "private key"
        );
        putOptionalSecret(
                environment,
                execution.getApiKeyEnvName(),
                execution.getApiKey(),
                "trade.polymarket.execution.api-key"
        );
        putOptionalSecret(
                environment,
                execution.getApiSecretEnvName(),
                execution.getApiSecret(),
                "trade.polymarket.execution.api-secret"
        );
        putOptionalSecret(
                environment,
                execution.getApiPassphraseEnvName(),
                execution.getApiPassphrase(),
                "trade.polymarket.execution.api-passphrase"
        );
        putOptionalSecret(
                environment,
                execution.getFunderAddressEnvName(),
                execution.getFunderAddress(),
                "trade.polymarket.execution.funder-address"
        );
    }

    static Path resolvedScriptPath(String scriptPath) {
        return resolveScriptPath(scriptPath, Path.of("").toAbsolutePath());
    }

    static Path resolveScriptPath(String scriptPath, Path workingDirectory) {
        if (scriptPath == null || scriptPath.isBlank()) {
            throw new IllegalArgumentException("trade.polymarket.execution.script-path is required");
        }
        Path path = Path.of(scriptPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path baseDirectory = workingDirectory.toAbsolutePath().normalize();
        Path cwdPath = baseDirectory.resolve(path).normalize();
        if (Files.exists(cwdPath)) {
            return cwdPath;
        }
        // The backend is often started from backend/, while the default script
        // path is rooted at the repository, so also try the parent directory.
        Path parentDirectory = baseDirectory.getParent();
        if (parentDirectory != null) {
            Path parentPath = parentDirectory.resolve(path).normalize();
            if (Files.exists(parentPath)) {
                return parentPath;
            }
        }
        return cwdPath;
    }

    private static String readAll(java.io.InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        inputStream.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void putRequiredSecret(
            Map<String, String> environment,
            String envName,
            String configuredValue,
            String propertyName,
            String secretName
    ) {
        String requiredEnvName = requireText(envName, propertyName + "-env-name is required");
        String resolved = configuredOrEnvironmentValue(environment, requiredEnvName, configuredValue);
        if (!hasText(resolved)) {
            throw new IllegalArgumentException(
                    "Polymarket " + secretName + " is required. Set "
                            + propertyName
                            + " or environment variable "
                            + requiredEnvName
            );
        }
        environment.put(requiredEnvName, resolved);
    }

    private static void putOptionalSecret(
            Map<String, String> environment,
            String envName,
            String configuredValue,
            String propertyName
    ) {
        if (!hasText(configuredValue) && !hasText(envName)) {
            return;
        }
        String requiredEnvName = requireText(envName, propertyName + "-env-name is required when " + propertyName + " is set");
        String resolved = configuredOrEnvironmentValue(environment, requiredEnvName, configuredValue);
        if (hasText(resolved)) {
            environment.put(requiredEnvName, resolved);
        }
    }

    private static String configuredOrEnvironmentValue(
            Map<String, String> environment,
            String envName,
            String configuredValue
    ) {
        // Resolution order: explicit config, inherited process env, local .env,
        // then Windows user/machine environment for desktop runs.
        if (hasText(configuredValue)) {
            return configuredValue.trim();
        }
        String value = environment.get(envName);
        if (hasText(value)) {
            return value.trim();
        }
        value = dotEnvValue(envName);
        if (hasText(value)) {
            return value.trim();
        }
        value = windowsEnvironmentValue(envName);
        return hasText(value) ? value.trim() : null;
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String plain(BigDecimal value) {
        return TradingMath.plain(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String dotEnvValue(String envName) {
        for (Path path : dotEnvPaths()) {
            String value = dotEnvValue(path, envName);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static List<Path> dotEnvPaths() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path parentDirectory = workingDirectory.getParent();
        if (parentDirectory == null) {
            return List.of(workingDirectory.resolve(".env"));
        }
        return List.of(
                workingDirectory.resolve(".env"),
                parentDirectory.resolve(".env"),
                workingDirectory.resolve("polymarket.env"),
                parentDirectory.resolve("polymarket.env")
        );
    }

    private static String dotEnvValue(Path path, String envName) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                if (!envName.equals(key)) {
                    continue;
                }
                return unquoteDotEnvValue(trimmed.substring(separator + 1).trim());
            }
        } catch (Exception e) {
            log.debug("Cannot read Polymarket secret from dot env file: path={}", path, e);
        }
        return null;
    }

    private static String unquoteDotEnvValue(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String windowsEnvironmentValue(String envName) {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        String value = windowsRegistryEnvironmentValue("HKCU\\Environment", envName);
        if (hasText(value)) {
            return value;
        }
        return windowsRegistryEnvironmentValue(
                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
                envName
        );
    }

    private static String windowsRegistryEnvironmentValue(String registryPath, String envName) {
        try {
            Process process = new ProcessBuilder("reg", "query", registryPath, "/v", envName)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String output = readAll(process.getInputStream());
            Pattern pattern = Pattern.compile(
                    "(?m)^\\s*" + Pattern.quote(envName) + "\\s+REG_\\S+\\s+(.*)\\s*$"
            );
            Matcher matcher = pattern.matcher(output);
            return matcher.find() ? matcher.group(1).trim() : null;
        } catch (Exception e) {
            log.debug("Cannot read Windows environment variable from registry: name={}, path={}", envName, registryPath, e);
            return null;
        }
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
