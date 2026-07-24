# ═══════════════════════════════════════════════════════════════
# Stage 1: Dependency resolution
# Separate layer so Docker cache is reused when only source changes
# ═══════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jdk-alpine AS deps

WORKDIR /build

# Copy Maven wrapper and POM first — cached until pom.xml changes
COPY mvnw ./
COPY .mvn .mvn/
COPY pom.xml ./

# Make mvnw executable and download all dependencies
# --no-transfer-progress: suppresses download noise in CI logs
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline \
        --no-transfer-progress \
        -q

# ═══════════════════════════════════════════════════════════════
# Stage 2: Build
# ═══════════════════════════════════════════════════════════════
FROM deps AS builder

# Copy source code — this layer is invalidated on any source change
COPY src src/

# Build JAR, skip tests (tests run in CI pipeline separately)
# -T 2: use 2 threads for faster parallel compilation
RUN ./mvnw package \
        -DskipTests \
        --no-transfer-progress \
        -T 2 \
        -q

# ═══════════════════════════════════════════════════════════════
# Stage 3: Runtime — minimal JRE image
# eclipse-temurin:21-jre-alpine = ~190MB vs JDK ~340MB
# ═══════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user
# addgroup/adduser in Alpine: -S = system, -G = assign group
RUN addgroup -S gateway && \
    adduser -S gateway -G gateway

WORKDIR /app

# Copy only the fat JAR from builder stage
# Nothing else from the build environment reaches the runtime image
COPY --from=builder --chown=gateway:gateway \
    /build/target/gateway-*.jar \
    app.jar

USER gateway

EXPOSE 8080

# Health check: wget is available in Alpine JRE image
# --spider: only check HTTP status, do not download body
# --quiet: suppress wget output
HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=45s \
    --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM flags:
#   UseContainerSupport:   JVM reads cgroup memory limits correctly
#   MaxRAMPercentage=67:   300MB heap in a 450MB container (300/450=0.67)
#   UseG1GC:               Low-pause GC — important for reactive latency
#   MaxGCPauseMillis=200:  G1GC target: pause under 200ms
#   egd:                   Faster SecureRandom on Linux (avoids /dev/random blocking)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=67", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]