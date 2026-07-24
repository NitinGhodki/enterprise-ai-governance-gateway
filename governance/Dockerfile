# ═══════════════════════════════════════════════════════════════
# Stage 1: Python dependency installation
# Using a full Python image to compile any C extensions,
# then copying only the installed packages to the runtime stage.
# ═══════════════════════════════════════════════════════════════
FROM python:3.12-slim AS deps

WORKDIR /install

# Install system packages needed to compile Python extensions:
#   build-essential: gcc, make (for sentence-transformers C extensions)
#   curl:            health check in runtime stage
# Clean up apt cache immediately to keep layer small
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential \
        curl && \
    rm -rf /var/lib/apt/lists/*

# Copy requirements first — cached until requirements.txt changes
COPY requirements.txt ./

# Install all Python packages into /install/packages
# --prefix: installs to a separate directory we can COPY cleanly
# --no-cache-dir: do not cache pip downloads in the image layer
# --no-compile: skip .pyc compilation here, done at runtime startup
RUN pip install \
        --no-cache-dir \
        --prefix=/install/packages \
        --no-compile \
        -r requirements.txt

# ═══════════════════════════════════════════════════════════════
# Stage 2: Download spaCy model
# spaCy requires a separate download step after pip install
# ═══════════════════════════════════════════════════════════════
FROM deps AS model-download

# en_core_web_sm: small English NLP model for PII entity recognition
# ~12MB, sufficient for person/email/phone/credit card detection
RUN PYTHONPATH=/install/packages/lib/python3.12/site-packages \
    python -m spacy download en_core_web_sm

# ═══════════════════════════════════════════════════════════════
# Stage 3: Runtime — minimal image
# python:3.12-slim = ~120MB vs python:3.12 = ~900MB
# ═══════════════════════════════════════════════════════════════
FROM python:3.12-slim AS runtime

# Runtime system dependencies only (no build tools)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl && \
    rm -rf /var/lib/apt/lists/*

# Security: non-root user
RUN addgroup --system governance && \
    adduser --system --ingroup governance governance

WORKDIR /app

# Copy installed packages from deps stage
COPY --from=model-download /install/packages /usr/local

# Copy spaCy model data
COPY --from=model-download \
    /usr/local/lib/python3.12/site-packages/en_core_web_sm \
    /usr/local/lib/python3.12/site-packages/en_core_web_sm

# Copy application source
COPY --chown=governance:governance app/ app/

USER governance

EXPOSE 8000

# Health check: curl to /health endpoint
# start-period=60s: allow time for sentence-transformers model loading
HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=60s \
    --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# uvicorn configuration:
#   --workers 1:        single worker — model loaded once in memory
#                       multiple workers × model size = OOM
#   --loop uvloop:      faster async event loop (C extension)
#   --http httptools:   faster HTTP parser (C extension)
#   --no-access-log:    reduces I/O overhead, structured logging handles this
#   --timeout-keep-alive 30: close idle connections after 30s
CMD ["python", "-m", "uvicorn", \
    "app.main:app", \
    "--host", "0.0.0.0", \
    "--port", "8000", \
    "--workers", "1", \
    "--loop", "uvloop", \
    "--http", "httptools", \
    "--no-access-log", \
    "--timeout-keep-alive", "30"]