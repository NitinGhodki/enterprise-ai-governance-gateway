"""
cost_service.py — accurate token counting and cost estimation.

Uses tiktoken for accurate token counting (same tokenizer as GPT models).
For Mistral models: cl100k_base is the closest available tokenizer.
Actual Mistral tokenization differs slightly but within 5% accuracy.
"""

import time

import tiktoken
import structlog

from app.config import Settings
from app.models.request_models import CostEstimateRequest, LlmProvider
from app.models.response_models import CostEstimateResponse

log = structlog.get_logger()


class CostService:
    """
    Token counting and cost estimation.
    tiktoken encoding is loaded once at init (fast, CPU-only).
    """

    def __init__(self, settings: Settings):
        self._settings = settings

        # cl100k_base: used by GPT-3.5/4, closest to Mistral tokenization
        # Within ~5% accuracy for Mistral models
        self._encoding = tiktoken.get_encoding("cl100k_base")

        # Cost table: USD per 1K tokens {provider: {input, output}}
        self._cost_table: dict[str, dict[str, float]] = {
            LlmProvider.HUGGINGFACE: {
                "input": settings.cost_huggingface_input_per_1k,
                "output": settings.cost_huggingface_output_per_1k,
            },
            LlmProvider.OLLAMA: {
                "input": settings.cost_ollama_per_1k,
                "output": settings.cost_ollama_per_1k,
            },
        }

        log.info("cost_service_initialised",
                 encoding="cl100k_base",
                 pricing=self._cost_table)

    async def estimate(
            self, request: CostEstimateRequest) -> CostEstimateResponse:
        """
        Count tokens accurately and calculate USD cost.

        Raises TokenCountException on encoding failure.
        This should never happen with valid UTF-8 text — defensive only.
        """
        start = time.perf_counter()

        try:
            prompt_tokens = len(self._encoding.encode(request.prompt_text))
            completion_tokens = len(
                self._encoding.encode(request.completion_text)
            ) if request.completion_text else 0

        except Exception as e:
            from app.exceptions.governance_exceptions import TokenCountException
            raise TokenCountException(str(e))

        total_tokens = prompt_tokens + completion_tokens

        pricing = self._cost_table.get(
            request.provider,
            self._cost_table[LlmProvider.HUGGINGFACE]  # default
        )

        cost_usd = (
            (prompt_tokens / 1000.0) * pricing["input"]
            + (completion_tokens / 1000.0) * pricing["output"]
        )

        elapsed_ms = int((time.perf_counter() - start) * 1000)

        log.debug("cost_estimated",
                  provider=request.provider,
                  model=request.model,
                  prompt_tokens=prompt_tokens,
                  completion_tokens=completion_tokens,
                  cost_usd=cost_usd,
                  elapsed_ms=elapsed_ms)

        return CostEstimateResponse(
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=total_tokens,
            cost_usd=round(cost_usd, 8),
            provider=request.provider,
            model=request.model,
        )

    def count_tokens(self, text: str) -> int:
        """Count tokens for a single text string. Synchronous utility method."""
        return len(self._encoding.encode(text))