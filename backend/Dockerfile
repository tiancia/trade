# syntax=docker/dockerfile:1
# Build from the repository root: docker build -f backend/Dockerfile .
ARG BUILD_JAVA_IMAGE=eclipse-temurin:21-jdk-jammy
ARG RUNTIME_JAVA_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${BUILD_JAVA_IMAGE} AS build
WORKDIR /workspace/backend
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
# Keep Maven dependencies in an ordinary build layer. External BuildKit caches
# retain this layer across fresh runners; cache mounts alone are not exported.
RUN ./mvnw -B -ntp dependency:go-offline
COPY backend/src/ src/
# The existing full test suite also validates these infrastructure files.
COPY backend/infrastructure/ infrastructure/
COPY compose.yaml /workspace/compose.yaml
# Package runs the offline provider tests; the opt-in exchange test stays off.
RUN RUN_INTEGRATION_TESTS=false ./mvnw -B -ntp clean package
# Rename before extraction so the slim executable jar keeps the runtime path.
RUN cp target/trade-0.0.1-SNAPSHOT.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${RUNTIME_JAVA_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 trade \
    && useradd --uid 10001 --gid 10001 --no-create-home trade \
    && install -d -o trade -g trade /app/data
WORKDIR /app
COPY tools/polymarket/requirements.txt /app/tools/polymarket/requirements.txt
RUN python3 -m venv /opt/polymarket-venv \
    && /opt/polymarket-venv/bin/pip install --no-cache-dir -r /app/tools/polymarket/requirements.txt \
    && /opt/polymarket-venv/bin/pip check
ENV PATH="/opt/polymarket-venv/bin:${PATH}" \
    POLYMARKET_ORDER_SCRIPT=/app/tools/polymarket/polymarket_place_order.py \
    TRADE_STORY_OUTPUT_DIR=/app/data/story \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
COPY --chown=10001:10001 tools/polymarket/polymarket_place_order.py /app/tools/polymarket/polymarket_place_order.py
# Imports and signature inspection only: constructing a client or placing an order is forbidden here.
RUN python -c "import runpy; bridge = runpy.run_path('/app/tools/polymarket/polymarket_place_order.py'); bridge['load_client_symbols']()"
# Stable dependencies are separate from application code, so a code-only change
# does not make the server download another complete executable archive.
COPY --from=build --chown=10001:10001 /workspace/backend/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/backend/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/backend/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/backend/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=6s --start-period=90s --retries=6 \
    CMD curl --fail --silent --max-time 5 http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
