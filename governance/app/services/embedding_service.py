"""
embedding_service.py — text embedding for semantic cache scoring.

Uses sentence-transformers all-MiniLM-L6-v2:
  Model size: ~22MB
  Embedding dimensions: 384
  Inference time: ~10ms per text (CPU)
  Memory footprint: ~50MB including model weights

The model is loaded ONCE at startup.
Per-request inference is fast (~10ms) and runs synchronously
in the async context — no thread pool needed at this throughput.
"""

import asyncio
import time
from typing import Optional

import structlog
from sentence_transformers import SentenceTransformer

from app.config import Settings
from app.exceptions.governance_exceptions import EmbeddingModelException
from app.models.request_models import EmbeddingRequest
from app.models.response_models import EmbeddingResponse

log = structlog.get_logger()


class EmbeddingService:
    """
    Text embedding service.

    Thread safety: SentenceTransformer.encode() is thread-safe.
    Concurrent requests to /governance/embed are handled correctly
    by the single model instance.
    """

    def __init__(self, model_name: str):
        self._model_name = model_name
        self._model: Optional[SentenceTransformer] = None

    async def initialize(self) -> None:
        """
        Load the sentence-transformers model.
        Runs in a thread pool (model loading is blocking I/O).
        """
        log.info("loading_embedding_model", model=self._model_name)

        # Model loading is blocking — run in thread pool
        loop = asyncio.get_event_loop()
        self._model = await loop.run_in_executor(
            None,
            lambda: SentenceTransformer(
                self._model_name,
                device="cpu",           # CPU only — no GPU on Railway free tier
            )
        )

        # Verify model loaded correctly with a test inference
        test_embedding = self._model.encode("test", normalize_embeddings=True)
        dimensions = len(test_embedding)

        log.info("embedding_model_loaded",
                 model=self._model_name,
                 dimensions=dimensions)

    async def embed(self, request: EmbeddingRequest) -> EmbeddingResponse:
        """
        Generate normalised embedding for input text.
        Raises EmbeddingModelException if model not loaded or inference fails.
        """
        if self._model is None:
            raise EmbeddingModelException("Model not initialised")

        start = time.perf_counter()

        try:
            # encode() with normalize_embeddings=True produces unit vectors
            # Unit vectors: dot product == cosine similarity (faster to compute)
            embedding = self._model.encode(
                request.text,
                normalize_embeddings=True,
                show_progress_bar=False,
            )

        except Exception as e:
            raise EmbeddingModelException(f"Inference failed: {e}")

        elapsed_ms = int((time.perf_counter() - start) * 1000)

        log.debug("embedding_generated",
                  dimensions=len(embedding),
                  elapsed_ms=elapsed_ms)

        return EmbeddingResponse(
            embedding=embedding.tolist(),
            dimensions=len(embedding),
            model=self._model_name,
        )

    async def cleanup(self) -> None:
        """Release model from memory on shutdown."""
        if self._model is not None:
            del self._model
            self._model = None
            log.info("embedding_model_released")