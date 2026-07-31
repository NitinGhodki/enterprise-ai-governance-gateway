"""
main.py — FastAPI application entry point.

Lifecycle:
  startup: load embedding model and spaCy model into memory once.
           These are heavy — loading per-request would be catastrophic.
  shutdown: release resources cleanly.

All endpoints are async. No blocking I/O anywhere.
"""

import time
import structlog
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.models.request_models import (
    SafetyCheckRequest,
    QualityCheckRequest,
    CostEstimateRequest,
    EmbeddingRequest,
)
from app.models.response_models import HealthResponse
from app.services.safety_service import SafetyService
from app.services.quality_service import QualityService
from app.services.cost_service import CostService
from app.services.embedding_service import EmbeddingService
from app.exceptions.governance_exceptions import GovernanceException

import logging
import os

def configure_logging() -> None:
    """
    Configure structlog for the environment.

    Local:      human-readable coloured output (ConsoleRenderer)
    Production: JSON output (JSONRenderer) queryable by log aggregators

    structlog is already used throughout the services with:
        log = structlog.get_logger()
        log.info("event_name", field1=value1, field2=value2)

    Every log call produces a structured event with all fields
    available for querying in Railway logs or Grafana Loki.
    """
    is_production = os.getenv("ENVIRONMENT", "production") == "production"
    log_level_str = os.getenv("LOG_LEVEL", "INFO")

    logging.basicConfig(
        format="%(message)s",
        level=logging.getLevelName(log_level_str),
    )
    shared_processors = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
    ]

    if is_production:
        # JSON output — one JSON object per line
        # Queryable by any log aggregation system
        processors = shared_processors + [
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ]
    else:
        # Human-readable coloured output for local development
        processors = shared_processors + [
            structlog.dev.ConsoleRenderer(colors=True),
        ]

    structlog.configure(
        processors=processors,
        wrapper_class=structlog.stdlib.BoundLogger,
        context_class=dict,
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )


# Call before app creation
configure_logging()
log = structlog.get_logger()
settings = get_settings()

# ── Service instances (module-level singletons) ───────────────────────────────
# Initialised in lifespan, not at import time.
# This allows proper async startup and avoids loading models during testing.
safety_service: SafetyService | None = None
quality_service: QualityService | None = None
cost_service: CostService | None = None
embedding_service: EmbeddingService | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Application lifespan: load all models at startup, release at shutdown.

    Memory budget for Python service (~250MB total):
      all-MiniLM-L6-v2 embedding model: ~22MB
      spaCy en_core_web_sm: ~12MB
      presidio analyzer: ~50MB
      RAGAS + LangChain imports: ~80MB
      FastAPI + uvicorn runtime: ~30MB
      Headroom: ~56MB
    """
    global safety_service, quality_service, cost_service, embedding_service

    log.info("governance_service.startup", environment=settings.environment)

    log.info("loading_embedding_model", model=settings.embedding_model)
    embedding_service = EmbeddingService(model_name=settings.embedding_model)
    await embedding_service.initialize()
    log.info("embedding_model_loaded")

    log.info("loading_safety_service")
    safety_service = SafetyService(settings=settings)
    await safety_service.initialize()
    log.info("safety_service_ready")

    log.info("loading_quality_service")
    quality_service = QualityService(settings=settings)
    log.info("quality_service_ready")

    cost_service = CostService(settings=settings)
    log.info("cost_service_ready")

    log.info("governance_service.ready")
    yield

    log.info("governance_service.shutdown")
    # Release GPU/CPU resources held by embedding model
    if embedding_service:
        await embedding_service.cleanup()
    log.info("governance_service.stopped")


app = FastAPI(
    title="AI Governance Service",
    description="Safety, quality, cost, and embedding evaluation for the AI Gateway",
    version="1.0.0",
    lifespan=lifespan,
    # Disable docs in production — reduces memory and attack surface
    docs_url="/docs" if settings.environment != "production" else None,
    redoc_url=None,
)

# Allow only the Java gateway to call this service
# In production: restrict CORS to gateway's internal hostname only
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Restricted via Docker network in production
    allow_methods=["POST", "GET"],
    allow_headers=["Content-Type", "X-Request-Id"],
)


# ── Exception handler ─────────────────────────────────────────────────────────

@app.exception_handler(GovernanceException)
async def governance_exception_handler(
        request: Request, exc: GovernanceException) -> JSONResponse:
    log.warning(
        "governance_exception",
        error_code=exc.error_code,
        message=exc.message,
        path=str(request.url),
    )
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "errorCode": exc.error_code,
            "message": exc.message,
        }
    )


# ── Middleware: request timing ────────────────────────────────────────────────

@app.middleware("http")
async def add_timing_and_context(request: Request, call_next):
    import uuid
    request_id = request.headers.get("X-Request-Id", str(uuid.uuid4())[:8])
    start = time.perf_counter()

    # Bind request_id to structlog context for this request
    # Every log.info() call within this request automatically includes it
    structlog.contextvars.clear_contextvars()
    structlog.contextvars.bind_contextvars(request_id=request_id)

    response = await call_next(request)
    elapsed_ms = int((time.perf_counter() - start) * 1000)

    response.headers["X-Processing-Ms"] = str(elapsed_ms)
    response.headers["X-Request-Id"] = request_id

    log.info("http.request",
             method=request.method,
             path=request.url.path,
             status=response.status_code,
             elapsed_ms=elapsed_ms)

    return response


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Health check — called by Java gateway and Railway deployment checks."""
    return HealthResponse(
        status="healthy",
        service=settings.service_name,
        embedding_model_loaded=embedding_service is not None,
        spacy_model_loaded=safety_service is not None
                           and safety_service.is_spacy_loaded(),
    )


@app.post("/governance/safety")
async def check_safety(request: SafetyCheckRequest):
    """
    Input safety evaluation.
    Called by Java gateway BEFORE forwarding to LLM.

    Returns immediately if safe.
    Returns 422 with violation details if blocked.
    Redacts PII and returns sanitised message if PII detected.
    """
    assert safety_service is not None, "Safety service not initialised"

    result = await safety_service.evaluate(request)

    log.info(
        "safety_check_complete",
        request_id=request.request_id,
        user_id=request.user_id,
        is_safe=result.is_safe,
        pii_detected=result.pii_detected,
        violations=result.violations,
    )

    return result


@app.post("/governance/quality")
async def check_quality(request: QualityCheckRequest):
    """
    Output quality evaluation.
    Called by Java gateway AFTER receiving LLM response.

    Evaluates faithfulness and relevancy using RAGAS-compatible scoring.
    Returns a score between 0.0 and 1.0.
    Configured minimum threshold triggers a gateway retry or rejection.
    """
    assert quality_service is not None, "Quality service not initialised"

    result = await quality_service.evaluate(request)

    log.info(
        "quality_check_complete",
        request_id=request.request_id,
        quality_passed=result.quality_passed,
        faithfulness=result.faithfulness_score,
        relevancy=result.relevancy_score,
    )

    return result


@app.post("/governance/cost")
async def estimate_cost(request: CostEstimateRequest):
    """
    Cost estimation using accurate token counting (tiktoken).
    Called by Java gateway to record cost in the audit log.
    """
    assert cost_service is not None, "Cost service not initialised"
    return await cost_service.estimate(request)


@app.post("/governance/embed")
async def get_embedding(request: EmbeddingRequest):
    """
    Text embedding for semantic cache scoring in Java.
    Java sends the user message, Python returns a float vector.
    Java computes cosine similarity against cached vectors in Redis.
    """
    assert embedding_service is not None, "Embedding service not initialised"
    return await embedding_service.embed(request)