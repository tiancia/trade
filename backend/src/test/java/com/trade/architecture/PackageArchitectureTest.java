package com.trade.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight source-layout guard that needs no additional architecture library.
 *
 * <p>These checks protect the domain-first package structure. They intentionally
 * enforce only stable, high-value boundaries and leave internal domain design to
 * focused unit tests and code review.</p>
 */
class PackageArchitectureTest {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?(com\\.trade\\.[\\w.*]+);"
    );
    private static final List<String> BUSINESS_DOMAINS = List.of(
            "trading", "polymarket", "story", "textgame", "marketplace", "weibo"
    );
    private static final List<String> SHARED_PACKAGES = List.of("client", "ai", "common");
    private static final List<String> LAYERED_TOP_LEVEL_PACKAGES = Stream.concat(
            BUSINESS_DOMAINS.stream(),
            Stream.of("automation")
    ).toList();
    private static final List<String> DOCUMENTED_TOP_LEVEL_PACKAGES = Stream.concat(
            LAYERED_TOP_LEVEL_PACKAGES.stream(),
            SHARED_PACKAGES.stream()
    ).toList();
    private static final Set<String> LEGACY_MODEL_CLIENT_IMPORTS = Set.of(
            "com.trade.polymarket.model.PolymarketOutcomeSnapshot -> "
                    + "com.trade.client.polymarket.dto.PolymarketOrderBookLevel",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.AccountBalanceResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.BalanceDetail",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.CandleResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.FillResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.InstrumentInfoResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.OrderBookResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.OrderInfoResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.PositionResp",
            "com.trade.trading.model.TradingDecisionContext -> com.trade.client.okx.dto.TickerResp"
    );

    private final Path projectRoot = locateProjectRoot();
    private final Path mainJava = projectRoot.resolve("src/main/java");
    private final Path testJava = projectRoot.resolve("src/test/java");

    @Test
    void sourcePathsMirrorDeclaredPackages() throws IOException {
        for (Path sourceRoot : List.of(mainJava, testJava)) {
            for (Path source : javaSources(sourceRoot)) {
                String content = Files.readString(source);
                Matcher matcher = PACKAGE.matcher(content);
                assertTrue(matcher.find(), () -> "missing package declaration: " + source);
                Path expected = sourceRoot
                        .resolve(matcher.group(1).replace('.', '/'))
                        .resolve(source.getFileName())
                        .normalize();
                assertEquals(expected, source.normalize(), () -> "package/path mismatch: " + source);
            }
        }
    }

    @Test
    void layeredTopLevelRootsContainOnlyPackageDocumentation() throws IOException {
        Path tradeRoot = mainJava.resolve("com/trade");
        for (String topLevelPackage : LAYERED_TOP_LEVEL_PACKAGES) {
            try (Stream<Path> files = Files.list(tradeRoot.resolve(topLevelPackage))) {
                List<Path> misplaced = files
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                        .toList();
                assertTrue(
                        misplaced.isEmpty(),
                        () -> topLevelPackage + " root contains unlayered classes: " + misplaced
                );
            }
        }
    }

    @Test
    void topLevelModulesDocumentTheirOwnership() {
        Path tradeRoot = mainJava.resolve("com/trade");
        for (String topLevelPackage : DOCUMENTED_TOP_LEVEL_PACKAGES) {
            Path packageInfo = tradeRoot.resolve(topLevelPackage).resolve("package-info.java");
            assertTrue(
                    Files.isRegularFile(packageInfo),
                    () -> "top-level module must document its responsibility: " + packageInfo
            );
        }
    }

    @Test
    void sharedPackagesDoNotDependOnBusinessDomains() throws IOException {
        Path tradeRoot = mainJava.resolve("com/trade");
        for (String shared : SHARED_PACKAGES) {
            for (Path source : javaSources(tradeRoot.resolve(shared))) {
                for (String importedType : imports(source)) {
                    assertFalse(
                            Stream.concat(BUSINESS_DOMAINS.stream(), Stream.of("automation"))
                                    .anyMatch(domain -> importsPackage(importedType, domain)),
                            () -> shared + " must not depend on orchestration or a business domain: "
                                    + source + " -> " + importedType
                    );
                }
            }
        }
    }

    @Test
    void businessDomainsDoNotDependOnEachOther() throws IOException {
        Path tradeRoot = mainJava.resolve("com/trade");
        for (String owner : BUSINESS_DOMAINS) {
            for (Path source : javaSources(tradeRoot.resolve(owner))) {
                for (String importedType : imports(source)) {
                    assertFalse(
                            Stream.concat(BUSINESS_DOMAINS.stream(), Stream.of("automation"))
                                    .filter(domain -> !domain.equals(owner))
                                    .anyMatch(domain -> importsPackage(importedType, domain)),
                            () -> owner + " must not import orchestration or another business domain: "
                                    + source + " -> " + importedType
                    );
                }
            }
        }
    }

    @Test
    void webLayerDoesNotImportPersistenceTypes() throws IOException {
        for (Path source : javaSources(mainJava.resolve("com/trade"))) {
            String path = source.toString().replace('\\', '/');
            if (!path.contains("/web/")) {
                continue;
            }
            for (String importedType : imports(source)) {
                assertFalse(
                        importedType.contains(".persistence."),
                        () -> "web layer must not expose persistence types: " + source + " -> " + importedType
                );
            }
        }
    }

    @Test
    void nonWebLayersDoNotDependOnWebTypes() throws IOException {
        for (Path source : javaSources(mainJava.resolve("com/trade"))) {
            if (belongsToLayer(source, "web")) {
                continue;
            }
            for (String importedType : imports(source)) {
                assertFalse(
                        importedType.contains(".web."),
                        () -> "inner layers must not depend on web types: " + source + " -> " + importedType
                );
            }
        }
    }

    @Test
    void domainModelsDoNotDependOnAdapters() throws IOException {
        Set<String> observedLegacyImports = new HashSet<>();
        for (Path source : javaSources(mainJava.resolve("com/trade"))) {
            if (!belongsToLayer(source, "model") && !belongsToLayer(source, "domain")) {
                continue;
            }
            String sourceType = declaredType(source);
            for (String importedType : imports(source)) {
                String dependency = sourceType + " -> " + importedType;
                boolean legacyClientImport = LEGACY_MODEL_CLIENT_IMPORTS.contains(dependency);
                if (legacyClientImport) {
                    observedLegacyImports.add(dependency);
                }
                assertFalse(
                        importedType.contains(".web.")
                                || importedType.contains(".persistence.")
                                || importedType.startsWith("com.trade.client.") && !legacyClientImport,
                        () -> "domain model must stay adapter-neutral: " + source + " -> " + importedType
                );
            }
        }
        assertEquals(
                LEGACY_MODEL_CLIENT_IMPORTS,
                observedLegacyImports,
                "legacy model/client dependency baseline changed; remove resolved edges or review new coupling explicitly"
        );
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    private static List<String> imports(Path source) throws IOException {
        Matcher matcher = IMPORT.matcher(Files.readString(source));
        Stream.Builder<String> imports = Stream.builder();
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports.build().toList();
    }

    private static String declaredType(Path source) throws IOException {
        Matcher matcher = PACKAGE.matcher(Files.readString(source));
        if (!matcher.find()) {
            throw new IllegalArgumentException("missing package declaration: " + source);
        }
        String simpleName = source.getFileName().toString().replaceFirst("\\.java$", "");
        return matcher.group(1) + "." + simpleName;
    }

    private static boolean importsPackage(String importedType, String topLevelPackage) {
        String prefix = "com.trade." + topLevelPackage;
        return importedType.equals(prefix) || importedType.startsWith(prefix + ".");
    }

    private static boolean belongsToLayer(Path source, String layer) {
        String normalized = source.toString().replace('\\', '/');
        return normalized.contains("/" + layer + "/");
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            if (Files.isDirectory(current.resolve("backend/src/main/java"))) {
                return current.resolve("backend");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate backend project root");
    }
}
