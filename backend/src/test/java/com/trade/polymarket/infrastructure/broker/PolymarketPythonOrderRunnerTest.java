package com.trade.polymarket.infrastructure.broker;

import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java.toString(), "-cp", classPath, ScriptFixture.class.getName(), mode).start();
    }

    // A local Java process exercises real pipe pressure without Python or network access.
    public static class ScriptFixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            if (args[0].equals("wait")) {
                Thread.sleep(30_000);
                return;
            }
            System.out.print("o".repeat(262_144));
            System.err.print("e".repeat(262_144));
        }
    }
}
