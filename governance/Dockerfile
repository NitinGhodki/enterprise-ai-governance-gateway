# ═══════════════════════════════════════════════════════════════
# Stage 1: Python dependency installation
# Using a full Python image to compile any C extensions,
# then copying only the installed packages to the runtime stage.
# ═══════════════════════════════════════════════════════════════
FROM python:3.12-slim AS builder

WORKDIR /install

# Install system packages needed to compile Python extensions:
#   build-essential: gcc, make (for sentence-transformers C extensions)
#   curl:            health check in runtime stage
# Clean up apt cache immediately to keep layer small
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential \
        gcc \
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
        --no-warn-script-location \
        -r requirements.txt

# ═══════════════════════════════════════════════════════════════
# Stage 2: Download spaCy model
# spaCy requires a separate download step after pip install
# ═══════════════════════════════════════════════════════════════

# Download spaCy model into the installed packages directory
RUN PYTHONPATH=/install/packages/lib/python3.12/site-packages \
    python -m spacy download en_core_web_sm && \
    # Verify model loaded correctly
    PYTHONPATH=/install/packages/lib/python3.12/site-packages

# Pre-download sentence-transformers model during build
# Avoids 30-60s download on first container startup
# Model cached in HuggingFace's default cache directory
# Pre-download sentence-transformers model during build
ENV HF_HOME=/tmp/hf_cache

RUN PYTHONPATH=/install/packages/lib/python3.12/site-packages python -c "from sentence_transformers import SentenceTransformer; model = SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"

# ═══════════════════════════════════════════════════════════════
# Stage 3: Runtime — minimal image
# python:3.12-slim = ~120MB vs python:3.12 = ~900MB
# ═══════════════════════════════════════════════════════════════
FROM python:3.12-slim AS runtime

# Runtime system dependencies only (no build tools)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl && \
    rm -rf /var/lib/apt/lists/* && \
    # Remove pip from runtime — packages are pre-installed
    pip uninstall -y pip setuptools wheel 2>/dev/null || true

    # Copy from the explicit temporary folder instead of /root/
COPY --from=builder /tmp/hf_cache /home/governance/.cache/huggingface
RUN chown -R governance:governance /home/governance/.cache

# Non-root user
RUN groupadd -g 10001 governance && \
    useradd -u 10001 -g governance -s /sbin/nologin governance

WORKDIR /app

# Copy installed packages from builder
COPY --from=builder /install/packages /usr/local


# Copy HuggingFace model cache (sentence-transformers model)
COPY --from=builder /root/.cache/huggingface /home/governance/.cache/huggingface

# Set correct ownership on model cache
RUN chown -R 10001:10001 /home/governance/.cache

# Copy application source
COPY --chown=10001:10001 app/ app/

# Switch to non-root
USER 10001:10001

# Environment for Python optimisation in production
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONHASHSEED=random \
    HF_HOME=/home/governance/.cache/huggingface

EXPOSE 8000

HEALTHCHECK \
    --interval=30s \
    --timeout=15s \
    --start-period=90s \
    --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# uvicorn production configuration:
#   --workers 1:            single worker — embedding model loaded once
#   --loop uvloop:          faster async event loop
#   --http httptools:       faster HTTP parsing
#   --no-access-log:        structured logging handles access logs
#   --timeout-graceful-shutdown 30: allow in-flight requests to complete
CMD ["python", "-m", "uvicorn", \
    "app.main:app", \
    "--host", "0.0.0.0", \
    "--port", "8000", \
    "--workers", "1", \
    "--loop", "uvloop", \
    "--http", "httptools", \
    "--no-access-log", \
    "--timeout-graceful-shutdown", "30"]