"""
governance_exceptions.py — typed exceptions for the governance service.

All exceptions carry: HTTP status code, machine-readable error code,
and human-readable message. The FastAPI exception handler in main.py
converts these to structured JSON responses.
"""

from typing import Optional


class GovernanceException(Exception):
    """Base exception for all governance service errors."""

    def __init__(self, status_code: int, error_code: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.error_code = error_code
        self.message = message


class ServiceNotInitialisedException(GovernanceException):
    """
    Raised when an endpoint is called before the lifespan
    startup has completed loading models.
    """

    def __init__(self, service_name: str):
        super().__init__(
            status_code=503,
            error_code="SERVICE_NOT_INITIALISED",
            message=f"Service '{service_name}' is not yet ready. Retry in a few seconds."
        )


class EmbeddingModelException(GovernanceException):
    """Raised when the embedding model fails to generate a vector."""

    def __init__(self, detail: str):
        super().__init__(
            status_code=500,
            error_code="EMBEDDING_MODEL_ERROR",
            message=f"Embedding model error: {detail}"
        )


class QualityEvaluationException(GovernanceException):
    """Raised when RAGAS evaluation fails unexpectedly."""

    def __init__(self, detail: str):
        super().__init__(
            status_code=500,
            error_code="QUALITY_EVALUATION_ERROR",
            message=f"Quality evaluation error: {detail}"
        )


class TokenCountException(GovernanceException):
    """Raised when tiktoken fails to tokenise the provided text."""

    def __init__(self, detail: str):
        super().__init__(
            status_code=422,
            error_code="TOKEN_COUNT_ERROR",
            message=f"Token counting error: {detail}"
        )