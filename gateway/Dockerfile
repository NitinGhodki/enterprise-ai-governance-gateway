# ═══════════════════════════════════════════════════════════════
# Stage 1: Dependency resolution
# Separate layer so Docker cache is reused when only source changes
# ═══════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jdk-alpine AS deps

WORKDIR /build

# System packages for Maven build only — not copied to runtime
RUN apk add --no-cache git

# Copy Maven wrapper and POM first — cached until pom.xml changes
COPY mvnw ./
COPY .mvn/ .mvn/
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
COPY src/ src/

# Build JAR, skip tests (tests run in CI pipeline separately)
# -T 2: use 2 threads for faster parallel compilation
RUN ./mvnw package \
        -DskipTests \
        --no-transfer-progress \
        -T 2 \
        -q && \
    # Verify the JAR was created
    ls -la target/*.jar

# ═══════════════════════════════════════════════════════════════
# Stage 3: Runtime — minimal JRE image
# eclipse-temurin:21-jre-alpine = ~190MB vs JDK ~340MB
# ═══════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user
# addgroup/adduser in Alpine: -S = system, -G = assign group
RUN addgroup -g 10001 -S gateway && \
    adduser -u 10001 -S gateway -G gateway

# Remove apk and package manager tools from runtime image
# Prevents an attacker from installing packages if they get a shell
RUN apk del apk-tools 2>/dev/null || true

WORKDIR /app

# Copy only the fat JAR from builder stage
# Nothing else from the build environment reaches the runtime image
# Copy JAR with correct ownership — no write permission for app user
COPY --from=builder \
     --chown=10001:10001 \
     --chmod=444 \
     /build/target/gateway-*.jar \
     app.jar

USER 10001:10001

EXPOSE 8080

STOPSIGNAL SIGTERM
# Health check: wget is available in Alpine JRE image
# --spider: only check HTTP status, do not download body
# --quiet: suppress wget output
HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=45s \
    --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM security and performance flags:
#   UseContainerSupport:       respect cgroup memory limits
#   MaxRAMPercentage=67:       300MB heap in 450MB container
#   UseG1GC:                   low-pause GC for reactive workloads
#   MaxGCPauseMillis=200:      G1GC target pause time
#   ExitOnOutOfMemoryError:    fast fail instead of degraded state
#   -Xss512k:                  reduce thread stack (default 1MB)
#                              saves ~20MB on 40 thread stack frames
#   DisableAttachMechanism:    prevent JVM attach for security
#   HeapDumpOnOutOfMemoryError: capture heap dump for diagnosis
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=67.0", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
    "-Xss512k", \
    "-XX:+DisableAttachMechanism", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=${SPRING_PROFILE:production}", \
    "-jar", "app.jar", "sh", "-c", "java $JAVA_OPTS -jar app.jar"]