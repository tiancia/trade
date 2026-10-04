package com.trade.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            "trading", "polymarket", "story", "textgame", "marketplace", "weibo", "x"
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
    void applicationRootsKeepDataTypesAndSharedExceptionsOut() throws IOException {
        Pattern dataDeclaration = Pattern.compile(
                "(?m)^public\\s+(?:record|enum)\\s|^@Data\\b|"
                        + "^public\\s+(?:final\\s+)?class\\s+\\w+\\s+extends\\s+RuntimeException\\b"
        );
        for (String domain : LAYERED_TOP_LEVEL_PACKAGES) {
            Path application = mainJava.resolve("com/trade").resolve(domain).resolve("application");
            try (Stream<Path> files = Files.list(application)) {
                for (Path source : files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertFalse(dataDeclaration.matcher(Files.readString(source)).find(),
                            () -> "application root owns behavior; move shared data/enums to model "
                                    + "and use-case failures to exception: " + source);
                }
            }
        }
    }

    @Test
    void modulesExposeOnlyFourLayerDirectories() throws IOException {
        Set<String> layers = Set.of("interfaces", "application", "domain", "infrastructure");
        for (String module : Stream.concat(LAYERED_TOP_LEVEL_PACKAGES.stream(), Stream.of("ai")).toList()) {
            Path moduleRoot = mainJava.resolve("com/trade").resolve(module);
            for (Path source : javaSources(moduleRoot)) {
                Path relative = moduleRoot.relativize(source);
                if (relative.getNameCount() == 1) {
                    assertEquals("package-info.java", relative.toString());
                } else {
                    assertTrue(layers.contains(relative.getName(0).toString()),
                            () -> "module must group capabilities under the four layers: " + source);
                }
            }
        }
    }

    @Test
    void applicationPortsDoNotDependOnImplementations() throws IOException {
        for (Path source : javaSources(mainJava.resolve("com/trade"))) {
            if (!source.toString().replace('\\', '/').contains("/application/port/")) {
                continue;
            }
            for (String importedType : imports(source)) {
                assertFalse(importedType.contains(".infrastructure.") || importedType.contains(".interfaces."),
                        () -> "outbound contracts must not depend on adapters: " + source + " -> " + importedType);
            }
        }
    }

    @Test
    void innerLayersDoNotDependOnInboundAdapters() throws IOException {
        for (String module : Stream.concat(BUSINESS_DOMAINS.stream(), Stream.of("ai")).toList()) {
            for (Path source : javaSources(mainJava.resolve("com/trade").resolve(module))) {
                if (belongsToLayer(source, "interfaces")) {
                    continue;
                }
                for (String importedType : imports(source)) {
                    assertFalse(importedType.contains(".interfaces."),
                            () -> "inner layers must not depend on HTTP/scheduler adapters: " + source);
                }
            }
        }
    }

    @Test
    void mapperXmlReferencesResolveAfterPackageMoves() throws Exception {
        Pattern typeAttribute = Pattern.compile("(?:namespace|type|resultType|parameterType)=\"(com\\.trade\\.[^\"]+)\"");
        Path mapperRoot = projectRoot.resolve("src/main/resources/mapper");
        try (Stream<Path> files = Files.walk(mapperRoot)) {
            for (Path xml : files.filter(path -> path.toString().endsWith(".xml")).toList()) {
                Matcher references = typeAttribute.matcher(Files.readString(xml));
                while (references.find()) {
                    Class.forName(references.group(1), false, getClass().getClassLoader());
                }
            }
        }
    }

    @Test
    void domainModelsDoNotDependOnAdapters() throws IOException {
        for (Path source : javaSources(mainJava.resolve("com/trade"))) {
            if (!belongsToLayer(source, "model") && !belongsToLayer(source, "domain")) {
                continue;
            }
            for (String importedType : imports(source)) {
                assertFalse(
                        importedType.contains(".web.")
                                || importedType.contains(".interfaces.")
                                || importedType.contains(".infrastructure.")
                                || importedType.contains(".persistence.")
                                || importedType.contains(".application.")
                                || importedType.contains(".config.")
                                || importedType.startsWith("com.trade.client."),
                        () -> "domain model must stay adapter-neutral: " + source + " -> " + importedType
                );
            }
        }
    }

    @Test
    void domainRulesHaveNoRuntimeOrPersistenceFrameworkDependencies() throws IOException {
        Pattern forbidden = Pattern.compile("(?m)^import (?:org\\.springframework\\.|org\\.apache\\.ibatis\\.|java\\.sql\\.)");
        for (Path source : javaSources(mainJava.resolve("com/trade"))) {
            if (belongsToLayer(source, "domain")) {
                assertFalse(forbidden.matcher(Files.readString(source)).find(),
                        () -> "domain rules must run independently of frameworks: " + source);
            }
        }
    }

    @Test
    void tradingUseCasesDependOnStateMarketAndEventContracts() throws IOException {
        Set<String> adapters = Set.of(
                "com.trade.trading.infrastructure.persistence.TradingStateRepository",
                "com.trade.trading.infrastructure.market.MarketContextCollector",
                "com.trade.trading.infrastructure.market.HistoricalCandleService",
                "com.trade.trading.infrastructure.market.OkxMarketDataWebSocketFeed",
                "com.trade.trading.infrastructure.event.BoundedTradingEventBus");
        for (String layer : List.of("application", "interfaces")) {
            for (Path source : javaSources(mainJava.resolve("com/trade/trading").resolve(layer))) {
                for (String importedType : imports(source)) {
                    assertFalse(adapters.contains(importedType),
                            () -> "use a use-case service or outbound port instead of a concrete adapter: " + source);
                }
            }
        }
    }

    @Test
    void tradingExecutionUseCasesDoNotImportBrokerImplementations() throws IOException {
        for (Path source : javaSources(mainJava.resolve("com/trade/trading/application"))) {
            for (String importedType : imports(source)) {
                assertFalse(importedType.startsWith("com.trade.trading.infrastructure.broker."),
                        () -> "execution and simulation use cases must use a domain model or outbound port: " + source);
            }
        }
    }

    @Test
    void orderAndPositionRehydrationStayInsidePersistence() throws IOException {
        Pattern restore = Pattern.compile("(?:TradingOrder|TradingPositionState)\\s*\\.\\s*restore\\s*\\(");
        for (Path source : javaSources(mainJava.resolve("com/trade/trading"))) {
            if (belongsToLayer(source, "application") || belongsToLayer(source, "interfaces")) {
                String text = Files.readString(source);
                assertFalse(restore.matcher(text).find(), () -> "use named domain operations, not rehydration: " + source);
                assertFalse(text.contains("import static com.trade.trading.domain.order.TradingOrder.restore")
                        || text.contains("import static com.trade.trading.domain.model.TradingPositionState.restore"));
            }
        }
    }

    @Test
    void sessionUseCaseDoesNotHandlePersistenceRowsOrStoredJson() throws IOException {
        for (String name : List.of("TextGameSessionService.java", "TextGameSessionViews.java")) {
            Path source = mainJava.resolve("com/trade/textgame/application/service").resolve(name);
            for (String type : imports(source)) {
                assertFalse(type.contains(".persistence.") || type.startsWith("java.sql."));
            }
            String text = Files.readString(source);
            assertFalse(text.contains("readValue(") || text.contains("writeValueAsString("));
        }
        String backtest = Files.readString(mainJava.resolve("com/trade/trading/application/backtest/BacktestService.java"));
        assertFalse(backtest.contains("ThreadPoolExecutor") || backtest.contains("@PreDestroy"));
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
