package com.trade.polymarket.infrastructure.broker;

import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketClientProperties;
import com.trade.polymarket.domain.model.PolymarketOrderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketPythonOrderRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativeScriptPathFromParentDirectoryWhenMissingUnderWorkingDirectory() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("backend"));
        Path scriptsDirectory = Files.createDirectories(tempDir.resolve("tools").resolve("polymarket"));
        Path script = Files.writeString(scriptsDirectory.resolve("polymarket_place_order.py"), "");

        Path resolved = PolymarketPythonOrderRunner.resolveScriptPath(
                "tools/polymarket/polymarket_place_order.py",
                workingDirectory
        );

        assertEquals(script.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void prefersRelativeScriptPathUnderWorkingDirectoryWhenItExists() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("backend"));
        Path scriptsDirectory = Files.createDirectories(workingDirectory.resolve("tools").resolve("polymarket"));
        Path script = Files.writeString(scriptsDirectory.resolve("polymarket_place_order.py"), "");

        Path resolved = PolymarketPythonOrderRunner.resolveScriptPath(
                "tools/polymarket/polymarket_place_order.py",
                workingDirectory
        );

        assertEquals(script.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void rejectsBlankScriptPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PolymarketPythonOrderRunner.resolveScriptPath(" ", tempDir)
        );
    }

    @Test
    void injectsConfiguredPrivateKeyIntoScriptEnvironment() {
        AiPolymarketProperties.ExecutionProperties execution = new AiPolymarketProperties.ExecutionProperties();
        execution.setPrivateKey(" 0xabc ");
        execution.setPrivateKeyEnvName("TEST_POLYMARKET_PRIVATE_KEY");
        Map<String, String> environment = new HashMap<>();

        PolymarketPythonOrderRunner.configureSecretEnvironment(environment, execution);

        assertEquals("0xabc", environment.get("TEST_POLYMARKET_PRIVATE_KEY"));
    }

    @Test
    void acceptsPrivateKeyAlreadyPresentInScriptEnvironment() {
        AiPolymarketProperties.ExecutionProperties execution = new AiPolymarketProperties.ExecutionProperties();
        execution.setPrivateKeyEnvName("TEST_POLYMARKET_PRIVATE_KEY");
        Map<String, String> environment = new HashMap<>();
        environment.put("TEST_POLYMARKET_PRIVATE_KEY", "0xabc");

        PolymarketPythonOrderRunner.configureSecretEnvironment(environment, execution);

        assertEquals("0xabc", environment.get("TEST_POLYMARKET_PRIVATE_KEY"));
    }

    @Test
    void reportsActionableErrorWhenPrivateKeyIsMissing() {
        AiPolymarketProperties.ExecutionProperties execution = new AiPolymarketProperties.ExecutionProperties();
        execution.setPrivateKeyEnvName("TEST_POLYMARKET_PRIVATE_KEY");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PolymarketPythonOrderRunner.configureSecretEnvironment(new HashMap<>(), execution)
        );

        assertTrue(exception.getMessage().contains("trade.polymarket.execution.private-key"));
        assertTrue(exception.getMessage().contains("TEST_POLYMARKET_PRIVATE_KEY"));
    }

    @Test
    void drainsBothOutputPipesBeforeWaitingForScriptExit() throws Exception {
        Process process = startFixture("output");
        try {
            var result = PolymarketPythonOrderRunner.runProcess(process, "{}", 10_000);

            assertEquals(0, result.exitCode());
            assertEquals("o".repeat(262_144), result.stdout());
            assertEquals("e".repeat(262_144), result.stderr());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void preservesRequestOrderTypeInsteadOfUsingCurrentConfiguration() throws Exception {
        var properties = new AiPolymarketProperties();
        properties.getExecution().setOrderType("FAK");
        var runner = new PolymarketPythonOrderRunner(properties, new PolymarketClientProperties());

        var payload = new ObjectMapper().readTree(runner.buildPayload(
                new PolymarketOrderRequest().setOrderType("FOK")));

        assertEquals("FOK", payload.path("orderType").asText());
        assertThrows(IllegalArgumentException.class,
                () -> runner.buildPayload(new PolymarketOrderRequest().setOrderType(" ")));
    }

    @Test
    void capsBothOutputStreamsAndTerminatesVerboseScripts() throws Exception {
        for (String mode : new String[]{"stdout-overflow", "stderr-overflow"}) {
            Process process = startFixture(mode);
            try {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> PolymarketPythonOrderRunner.runProcess(process, "{}", 10_000));

                assertTrue(failure.getCause().getMessage().contains("output exceeds"));
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                assertFalse(process.isAlive());
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void appliesTimeoutEvenWhenScriptDoesNotReadInput() throws Exception {
        Process process = startFixture("ignore-input");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> PolymarketPythonOrderRunner.runProcess(process, "x".repeat(2_097_152), 100));

            assertTrue(failure.getMessage().contains("timed out"));
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void excludesRawScriptOutputFromFailureExceptions() throws Exception {
        // Java source launching exercises the real runner without Python, a provider, or real secrets.
        Path script = Files.writeString(tempDir.resolve("FailingScript.java"), """
                class FailingScript {
                    public static void main(String[] args) throws Exception {
                        System.in.readAllBytes();
                        System.out.print("DUMMY_PROVIDER_SECRET");
                        System.err.print("DUMMY_PROVIDER_SECRET");
                        System.exit(1);
                    }
                }
                """);
        var properties = new AiPolymarketProperties();
        var execution = properties.getExecution();
        execution.setPythonCommand(javaExecutable().toString());
        execution.setScriptPath(script.toString());
        execution.setTimeoutMs(15_000);
        execution.setPrivateKey("dummy");
        execution.setApiKey("dummy");
        execution.setApiSecret("dummy");
        execution.setApiPassphrase("dummy");
        execution.setFunderAddress("dummy");
        var runner = new PolymarketPythonOrderRunner(properties, new PolymarketClientProperties());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> runner.placeOrder(new PolymarketOrderRequest().setOrderType("FAK")));

        assertTrue(failure.getCause().getMessage().contains("exitCode=1"));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains("DUMMY_PROVIDER_SECRET"));
        }
    }

    @Test
    void terminatesScriptAfterTimeout() throws Exception {
        Process process = startFixture("wait");
        try {
            var failure = assertThrows(RuntimeException.class,
                    () -> PolymarketPythonOrderRunner.runProcess(process, "{}", 50));

            assertTrue(failure.getMessage().contains("timed out"));
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void terminatesScriptWhenCallerIsInterrupted() throws Exception {
        Process process = startFixture("wait");
        try {
            Thread.currentThread().interrupt();

            assertThrows(InterruptedException.class,
                    () -> PolymarketPythonOrderRunner.runProcess(process, "{}", 10_000));
            Thread.interrupted();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        } finally {
            Thread.interrupted();
            process.destroyForcibly();
        }
    }

    private Process startFixture(String mode) throws Exception {
        Path java = javaExecutable();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java.toString(), "-cp", classPath, ScriptFixture.class.getName(), mode).start();
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    // A local Java process exercises real pipe pressure without Python or network access.
    public static class ScriptFixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("ignore-input")) {
                Thread.sleep(30_000);
                return;
            }
            System.in.readAllBytes();
            if (args[0].equals("wait")) {
                Thread.sleep(30_000);
                return;
            }
            if (args[0].equals("stdout-overflow") || args[0].equals("stderr-overflow")) {
                var stream = args[0].equals("stdout-overflow") ? System.out : System.err;
                stream.print("x".repeat(2_097_152));
                stream.flush();
                Thread.sleep(30_000);
                return;
            }
            System.out.print("o".repeat(262_144));
            System.err.print("e".repeat(262_144));
        }
    }
}
