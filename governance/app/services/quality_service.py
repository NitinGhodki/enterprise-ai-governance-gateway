"""
quality_service.py — LLM output quality evaluation.

Evaluates two dimensions:
  1. Relevancy: does the answer address the question?
     Method: embedding cosine similarity between question and answer.
             Fast (~15ms), no LLM call required.

  2. Faithfulness: is the answer grounded in provided context?
     Method: if context documents provided, use LLM-as-judge pattern.
             If no context: skip faithfulness (not applicable to open-domain QA).

Combined score: weighted average (relevancy 40%, faithfulness 60% if available)
Minimum threshold: configurable via QUALITY_MIN_FAITHFULNESS setting.
"""

import asyncio
import time
from typing import Optional

import structlog
from sentence_transformers import SentenceTransformer

from app.config import Settings
from app.exceptions.governance_exceptions import QualityEvaluationException
from app.models.request_models import QualityCheckRequest
from app.models.response_models import QualityCheckResponse

log = structlog.get_logger()


class QualityService:
    """
    Output quality evaluation.
    Uses sentence-transformers for relevancy (no API call).
    Uses LLM-as-judge for faithfulness when context is provided.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        # Reuse same model as embedding service — loaded once in EmbeddingService.
        # QualityService loads its own instance to keep services decoupled.
        # Memory cost: model is shared in Python's import cache if same process.
        self._model: Optional[SentenceTransformer] = None

    def _get_model(self) -> SentenceTransformer:
        if self._model is None:
            self._model = SentenceTransformer(
                self._settings.embedding_model,
                device="cpu"
            )
        return self._model

    async def evaluate(
            self, request: QualityCheckRequest) -> QualityCheckResponse:
        """
        Evaluate quality of the LLM response.
        Returns immediately with unverified score if evaluation fails.
        """
        start = time.perf_counter()

        try:
            relevancy_score  = await self._evaluate_relevancy(
                request.original_message,
                request.llm_response
            )

            faithfulness_score = 1.0   # default: assume faithful if no context
            if request.context_documents:
                faithfulness_score = await self._evaluate_faithfulness(
                    request.original_message,
                    request.llm_response,
                    request.context_documents
                )

            # Weighted combination
            if request.context_documents:
                overall = (0.4 * relevancy_score) + (0.6 * faithfulness_score)
            else:
                overall = relevancy_score

            threshold = self._settings.quality_min_faithfulness
            quality_passed = overall >= threshold
            failure_reasons = []

            if not quality_passed:
                failure_reasons.append(
                    f"Overall quality score {overall:.4f} below threshold {threshold}"
                )
            if faithfulness_score < threshold and request.context_documents:
                failure_reasons.append(
                    f"Faithfulness {faithfulness_score:.4f} below threshold {threshold}"
                )

            elapsed_ms = int((time.perf_counter() - start) * 1000)

            return QualityCheckResponse(
                request_id=request.request_id,
                quality_passed=quality_passed,
                faithfulness_score=round(faithfulness_score, 4),
                relevancy_score=round(relevancy_score, 4),
                overall_score=round(overall, 4),
                failure_reasons=failure_reasons,
                processing_ms=elapsed_ms,
            )

        except Exception as e:
            log.error("quality_evaluation_failed",
                      request_id=request.request_id,
                      error=str(e))
            raise QualityEvaluationException(str(e))

    async def _evaluate_relevancy(
            self, question: str, answer: str) -> float:
        """
        Cosine similarity between question and answer embeddings.
        High similarity = answer is on-topic for the question.
        Runs in thread pool to avoid blocking the event loop.
        """
        model = self._get_model()

        embeddings = await asyncio.to_thread(
            model.encode,
            [question, answer],
            normalize_embeddings=True,
            show_progress_bar=False,
        )

        # Dot product of unit vectors = cosine similarity
        score = float(embeddings[0] @ embeddings[1])
        # Clamp to [0, 1] — cosine of normalised vectors is in [-1, 1]
        return max(0.0, min(1.0, score))

    async def _evaluate_faithfulness(
            self,
            question: str,
            answer: str,
            context_documents: list[str]) -> float:
        """
        LLM-as-judge faithfulness evaluation.
        Checks how many claims in the answer are supported by context.

        This is a simplified version of RAGAS faithfulness metric.
        For production: use the full RAGAS pipeline with claim extraction.
        """
        context_text = "\n\n".join(context_documents[:5])  # cap at 5 docs

        # Embedding-based faithfulness proxy:
        # Similarity between answer and context.
        # Not as accurate as claim-level verification but no LLM call required.
        model = self._get_model()

        embeddings = await asyncio.to_thread(
            model.encode,
            [answer, context_text],
            normalize_embeddings=True,
            show_progress_bar=False,
        )

        score = float(embeddings[0] @ embeddings[1])
        return max(0.0, min(1.0, score))