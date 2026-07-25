"""
quality_service.py — production-grade LLM output quality evaluation.

Pipeline:
  1. Relevancy scoring   — embedding cosine similarity (fast, no LLM call)
  2. Faithfulness scoring — claim extraction + per-claim verification
     - Without context: skip (not applicable to open-domain QA)
     - With context: LLM-as-judge claim verification

RAGAS comparison:
  This pipeline implements the same logical steps as RAGAS faithfulness
  and answer_relevancy metrics. It uses sentence-transformers for
  relevancy (RAGAS uses a separate LLM call) and a lightweight
  claim-verification approach for faithfulness.

  For production evaluation pipelines with gold datasets: use full RAGAS.
  For per-request real-time scoring: this pipeline (faster, cheaper).
"""

import asyncio
import re
import time
from typing import Optional

import httpx
import structlog
from sentence_transformers import SentenceTransformer

from app.config import Settings
from app.exceptions.governance_exceptions import QualityEvaluationException
from app.models.request_models import QualityCheckRequest
from app.models.response_models import QualityCheckResponse

log = structlog.get_logger()


class QualityService:
    """
    Production quality evaluation service.

    Two scoring dimensions:
      relevancy:    Is the answer on-topic for the question?
      faithfulness: Are the answer's claims supported by the context?

    Both scores are in [0.0, 1.0]. Overall score is a weighted average.
    """

    # Claim extraction prompt — asks LLM to decompose answer into atomic facts
    _CLAIM_EXTRACTION_PROMPT = """
        Break the following answer into a list of simple, atomic factual claims.
        Each claim must be a single sentence that can be independently verified.
        Return ONLY a numbered list of claims, nothing else.

        Answer: {answer}

        Claims:
    """

    # Claim verification prompt — asks LLM whether a claim is supported
    _CLAIM_VERIFY_PROMPT = """
        Given the following context, determine if the claim is supported.
        Answer with exactly one word: SUPPORTED or NOT_SUPPORTED.

        Context: {context}

        Claim: {claim}

        Answer:
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        self._embedding_model: Optional[SentenceTransformer] = None
        self._hf_api_key = settings.hf_api_key
        self._hf_model = settings.hf_model
        self._hf_base_url = "https://api-inference.huggingface.co/v1"

    def _get_embedding_model(self) -> SentenceTransformer:
        if self._embedding_model is None:
            self._embedding_model = SentenceTransformer(
                self._settings.embedding_model,
                device="cpu"
            )
        return self._embedding_model

    async def evaluate(
            self, request: QualityCheckRequest) -> QualityCheckResponse:
        """
        Full quality evaluation pipeline.
        Returns a QualityCheckResponse with scores and failure reasons.
        """
        start = time.perf_counter()

        try:
            # Relevancy: always computed (fast, no LLM call)
            relevancy_score = await self._score_relevancy(
                request.original_message,
                request.llm_response
            )

            # Faithfulness: only when context documents provided
            faithfulness_score = 1.0
            faithfulness_details: list[str] = []

            if (request.context_documents
                    and self._settings.quality_evaluation_enabled):
                faithfulness_score, faithfulness_details = \
                    await self._score_faithfulness(
                        request.llm_response,
                        request.context_documents
                    )

            # Weighted combination
            has_context = bool(request.context_documents)
            if has_context:
                # Context available: faithfulness weighted higher
                overall = (0.35 * relevancy_score) + (0.65 * faithfulness_score)
            else:
                # No context: relevancy only
                overall = relevancy_score

            threshold = self._settings.quality_min_faithfulness
            quality_passed = overall >= threshold

            failure_reasons: list[str] = []
            if not quality_passed:
                failure_reasons.append(
                    f"Overall score {overall:.4f} below threshold {threshold:.2f}"
                )
            if has_context and faithfulness_score < threshold:
                failure_reasons.append(
                    f"Faithfulness {faithfulness_score:.4f} below threshold"
                )
                failure_reasons.extend(faithfulness_details[:3])

            elapsed_ms = int((time.perf_counter() - start) * 1000)

            log.info("quality_evaluated",
                     request_id=request.request_id,
                     relevancy=round(relevancy_score, 4),
                     faithfulness=round(faithfulness_score, 4),
                     overall=round(overall, 4),
                     passed=quality_passed,
                     elapsed_ms=elapsed_ms)

            return QualityCheckResponse(
                request_id=request.request_id,
                quality_passed=quality_passed,
                faithfulness_score=round(faithfulness_score, 4),
                relevancy_score=round(relevancy_score, 4),
                overall_score=round(overall, 4),
                failure_reasons=failure_reasons,
                processing_ms=elapsed_ms,
            )

        except QualityEvaluationException:
            raise
        except Exception as e:
            log.error("quality_evaluation_unexpected_error",
                      request_id=request.request_id,
                      error=str(e))
            # Return degraded response rather than 500
            # Quality should never block a response on internal failure
            return QualityCheckResponse(
                request_id=request.request_id,
                quality_passed=True,
                faithfulness_score=-1.0,
                relevancy_score=-1.0,
                overall_score=-1.0,
                failure_reasons=["Quality evaluation failed internally — unverified"],
                processing_ms=int((time.perf_counter() - start) * 1000),
            )

    # ── Relevancy scoring ─────────────────────────────────────────────────────

    async def _score_relevancy(
            self, question: str, answer: str) -> float:
        """
        Embedding cosine similarity between question and answer.
        Measures whether the answer addresses the question topic.

        Normalised vectors: dot product == cosine similarity.
        Clamp to [0.0, 1.0] — negative similarity treated as irrelevant.
        """
        model = self._get_embedding_model()

        embeddings = await asyncio.to_thread(
            model.encode,
            [question, answer],
            normalize_embeddings=True,
            show_progress_bar=False,
            batch_size=2,
        )

        similarity = float(embeddings[0] @ embeddings[1])
        return max(0.0, min(1.0, similarity))

    # Faithfulness scoring 

    async def _score_faithfulness(
            self,
            answer: str,
            context_documents: list[str]) -> tuple[float, list[str]]:
        """
        Claim-level faithfulness evaluation.

        Steps:
          1. Extract atomic claims from the answer
          2. For each claim: verify it appears in the context
          3. Score = verified_claims / total_claims

        Uses HuggingFace LLM for both extraction and verification.
        Falls back to embedding similarity if LLM is unavailable.
        """
        context = "\n\n".join(context_documents[:5])

        # Step 1: Extract claims
        try:
            claims = await self._extract_claims(answer)
        except Exception as e:
            log.warning("claim_extraction_failed",
                        error=str(e),
                        fallback="embedding_similarity")
            # Fallback: embedding similarity between answer and context
            return await self._embedding_faithfulness(answer, context), []

        if not claims:
            log.debug("no_claims_extracted_from_answer")
            return 1.0, []

        # Step 2: Verify each claim against context
        verification_tasks = [
            self._verify_claim(claim, context)
            for claim in claims
        ]

        # Run all verifications concurrently
        results = await asyncio.gather(
            *verification_tasks, return_exceptions=True
        )

        verified = 0
        unverified_claims: list[str] = []

        for claim, result in zip(claims, results):
            if isinstance(result, Exception):
                log.warning("claim_verification_error",
                            claim=claim[:50], error=str(result))
                # Conservative: count as unverified on error
                unverified_claims.append(f"Could not verify: {claim[:60]}")
            elif result:
                verified += 1
            else:
                unverified_claims.append(f"Not supported: {claim[:60]}")

        score = verified / len(claims) if claims else 1.0

        log.debug("faithfulness_scored",
                  total_claims=len(claims),
                  verified=verified,
                  score=score)

        return score, unverified_claims

    async def _extract_claims(self, answer: str) -> list[str]:
        """
        Use HuggingFace LLM to extract atomic claims from the answer.
        Returns list of claim strings.
        """
        prompt = self._CLAIM_EXTRACTION_PROMPT.format(answer=answer[:2000])

        response_text = await self._call_hf_llm(prompt, max_tokens=256)

        # Parse numbered list: "1. claim text\n2. claim text"
        claims = []
        for line in response_text.strip().split("\n"):
            line = line.strip()
            # Match "1. claim" or "- claim" or "• claim"
            cleaned = re.sub(r"^[\d]+[.):]\s*", "", line)
            cleaned = re.sub(r"^[-•*]\s*", "", cleaned)
            if cleaned and len(cleaned) > 10:
                claims.append(cleaned)

        return claims[:10]   # cap at 10 claims per answer

    async def _verify_claim(self, claim: str, context: str) -> bool:
        """
        Ask HuggingFace LLM whether a claim is supported by context.
        Returns True if supported, False if not.
        """
        prompt = self._CLAIM_VERIFY_PROMPT.format(
            context=context[:3000],
            claim=claim
        )

        response_text = await self._call_hf_llm(prompt, max_tokens=10)
        return "SUPPORTED" in response_text.upper()

    async def _call_hf_llm(self, prompt: str, max_tokens: int = 256) -> str:
        """
        Async HTTP call to HuggingFace OpenAI-compatible endpoint.
        Uses httpx.AsyncClient for true async I/O — no thread blocking.
        """
        if not self._hf_api_key:
            raise QualityEvaluationException(
                "HF_API_KEY not configured for quality evaluation"
            )

        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                f"{self._hf_base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self._hf_api_key}"},
                json={
                    "model": self._hf_model,
                    "messages": [{"role": "user", "content": prompt}],
                    "max_tokens": max_tokens,
                    "temperature": 0.0,    # deterministic for evaluation
                    "stream": False,
                }
            )
            response.raise_for_status()
            data = response.json()
            return data["choices"][0]["message"]["content"]

    async def _embedding_faithfulness(
            self, answer: str, context: str) -> float:
        """
        Fallback faithfulness: embedding cosine similarity.
        Less accurate than claim verification but always available.
        """
        model = self._get_embedding_model()
        embeddings = await asyncio.to_thread(
            model.encode,
            [answer, context],
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        score = float(embeddings[0] @ embeddings[1])
        return max(0.0, min(1.0, score))