# ═══════════════════════════════════════════════════════════════
# Stage 1: Python dependency installation
# ═══════════════════════════════════════════════════════════════
FROM python:3.12-slim AS builder

WORKDIR /install

# Install system packages needed to compile Python extensions
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential \
        gcc \
        curl && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./

# Install packages into /install/packages prefix
RUN pip install \
        --no-cache-dir \
        --prefix=/install/packages \
        --no-warn-script-location \
        -r requirements.txt

# Download spaCy model into the installed packages directory
RUN PYTHONPATH=/install/packages/lib/python3.12/site-packages \
    python -m spacy download en_core_web_sm && \
    # Verify model loaded correctly
    PYTHONPATH=/install/packages/lib/python3.12/site-packages \
    python -c "import spacy; nlp = spacy.load('en_core_web_sm'); print('spaCy OK')"

# CRITICAL FIX: Direct the builder download paths to the matching cache folder
ENV HF_HOME=/tmp/hf_cache

# Cache SentenceTransformer weights during the compilation process
RUN PYTHONPATH=/install/packages/lib/python3.12/site-packages python -c \
    "from sentence_transformers import SentenceTransformer; model = SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"

# ═══════════════════════════════════════════════════════════════
# Stage 2: Runtime — Minimal production execution footprint
# ═══════════════════════════════════════════════════════════════
FROM python:3.12-slim AS runtime

# Install lightweight runtime utility assets
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl && \
    rm -rf /var/lib/apt/lists/* && \
    # Remove pip from runtime — packages are pre-installed
    pip uninstall -y pip setuptools wheel 2>/dev/null || true

# STEP A: Setup system user profile first so paths can accept permissions properly
RUN groupadd -g 10001 governance && \
    useradd -u 10001 -g governance -m -s /sbin/nologin governance

WORKDIR /app

# STEP B: Import pre-compiled application python libraries
COPY --from=builder /install/packages /usr/local

# STEP C: Explicitly build and import model assets and map security permissions safely
RUN mkdir -p /home/governance/.cache/huggingface && \
    chown -R 10001:10001 /home/governance/.cache

COPY --from=builder --chown=10001:10001 /tmp/hf_cache /home/governance/.cache/huggingface

# Copy application source code base files
COPY --chown=10001:10001 app/ app/

# Apply runtime system privileges restrictions
USER 10001:10001

# Configure optimal process thread variables
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
