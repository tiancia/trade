package com.trade.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureConfigurationTest {
    private static final List<Path> YAML_FILES = List.of(
            Path.of("..", "compose.yaml"),
            Path.of("infrastructure", "compose.yaml"),
            Path.of("infrastructure", "observability", "prometheus", "prometheus.yml"),
            Path.of("infrastructure", "observability", "prometheus", "rules", "trading-alerts.yml"),
            Path.of("infrastructure", "observability", "grafana", "provisioning", "datasources", "prometheus.yml"),
            Path.of("infrastructure", "observability", "grafana", "provisioning", "dashboards", "trading.yml")
    );

    @Test
    void observabilityYamlFilesAreWellFormed() throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        for (Path path : YAML_FILES) {
            try (Reader reader = Files.newBufferedReader(path)) {
                assertTrue(yaml.loadAll(reader).iterator().hasNext(), () -> path + " must contain YAML");
            }
        }
    }

    @Test
    void unifiedInfrastructureComposeDefinesExpectedServices() throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> compose;
        try (Reader reader = Files.newBufferedReader(Path.of("infrastructure", "compose.yaml"))) {
            compose = yaml.load(reader);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        assertEquals(Set.of("mysql", "redis", "prometheus", "grafana"), services.keySet());
    }

    @Test
    void rootComposeProvidesTheDefaultCommandEntryPoint() throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> compose;
        try (Reader reader = Files.newBufferedReader(Path.of("..", "compose.yaml"))) {
            compose = yaml.load(reader);
        }

        assertEquals("trade-infrastructure", compose.get("name"));
        assertEquals(List.of("backend/infrastructure/compose.yaml"), compose.get("include"));
    }

    @Test
    void provisionedDashboardHasStableIdentityAndPanels() throws IOException {
        Path dashboard = Path.of(
                "infrastructure",
                "observability",
                "grafana",
                "dashboards",
                "trading-overview.json"
        );
        JsonNode root = new ObjectMapper().readTree(dashboard.toFile());

        assertEquals("trade-trading-overview", root.path("uid").asText());
        assertEquals("Trading Module Overview", root.path("title").asText());
        assertFalse(root.path("panels").isEmpty());
        assertTrue(root.path("panels").size() >= 15);
    }
}
