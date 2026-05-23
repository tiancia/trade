package com.trade.polymarket.execution;

import com.trade.polymarket.config.AiPolymarketProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
