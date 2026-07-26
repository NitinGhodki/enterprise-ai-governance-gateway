"""
safety_service.py — input safety evaluation.

Three checks in order (fast to slow):
  1. Length check (microseconds)
  2. Injection pattern matching (milliseconds)
  3. PII detection via Presidio + spaCy (50-200ms)

Design: fail fast. If length check fails, do not run PII detection.
"""

import re
import time
from typing import Optional

import structlog
from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from presidio_anonymizer import AnonymizerEngine

from app.config import Settings
from app.models.request_models import SafetyCheckRequest
from app.models.response_models import SafetyCheckResponse

log = structlog.get_logger()

# Injection patterns — compiled once at module level for performance
_INJECTION_PATTERNS: list[re.Pattern] = [
    re.compile(p, re.IGNORECASE) for p in [
        r"ignore\s+(previous|all|prior)\s+instructions?",
        r"forget\s+(your|all|previous)\s+(instructions?|prompt|rules?)",
        r"disregard\s+(your|the)\s+system\s+prompt",
        r"you\s+are\s+now\s+(?:a|an|the)\s+\w+",
        r"pretend\s+you\s+(are|have|can)",
        r"act\s+as\s+if\s+you\s+(have|are|can)",
        r"your\s+new\s+instructions?\s+(are|is)",
        r"override\s+(your\s+)?(instructions?|rules?|constraints?)",
        r"jailbreak",
        r"do\s+anything\s+now\b",
        r"dan\s+mode",
        r"developer\s+mode",
        r"<\|?system\|?>",      # LLM prompt format injection
        r"\[INST\]",             # Mistral instruction injection
        r"#{3,}\s*system",       # Markdown-style system prompt injection
    ]
]


class SafetyService:
    """
    Input safety evaluation service.

    Loaded once at startup (spaCy + Presidio are heavy).
    All methods are async — I/O operations use asyncio.
    CPU-bound Presidio analysis is fast enough (~100ms) to
    run directly in the async context without a thread pool.
    For higher throughput: use asyncio.to_thread() to move
    Presidio to a thread pool.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        self._analyzer: Optional[AnalyzerEngine] = None
        self._anonymizer: Optional[AnonymizerEngine] = None
        self._spacy_loaded = False

    async def initialize(self) -> None:
        """
        Load spaCy model and Presidio engines.
        Called once during FastAPI lifespan startup.
        """
        import spacy
        log.info("loading_spacy_model")

        # Load en_core_web_sm — small English NLP model
        # Required by Presidio for NER-based PII detection
        nlp = spacy.load("/usr/local/lib/python3.12/site-packages/en_core_web_sm/en_core_web_sm-3.7.1")
        self._spacy_loaded = True
        log.info("spacy_model_loaded",
                 vocab_size=len(nlp.vocab))

        # Presidio AnalyzerEngine with default recognizers
        # Detects: PERSON, EMAIL, PHONE, CREDIT_CARD, IP_ADDRESS,
        #          LOCATION, DATE_TIME, NRP (nationality/religion)
        self._analyzer = AnalyzerEngine()
        self._anonymizer = AnonymizerEngine()

        log.info("presidio_engines_loaded")

    def is_spacy_loaded(self) -> bool:
        return self._spacy_loaded

    async def evaluate(self, request: SafetyCheckRequest) -> SafetyCheckResponse:
        """
        Run all safety checks on the input.
        Returns immediately on the first failure (fail fast).
        """
        start_ms = time.perf_counter()

        # Check 1: Length limit 
        if len(request.message) > self._settings.safety_max_input_length:
            return SafetyCheckResponse(
                request_id=request.request_id,
                is_safe=False,
                violations=[f"Input exceeds maximum length of "
                            f"{self._settings.safety_max_input_length} characters"],
                pii_detected=False,
                injection_detected=False,
                processing_ms=self._elapsed_ms(start_ms),
            )

        # Check 2: Injection detection 
        injection_result = self._check_injection(request.message)
        if injection_result and self._settings.safety_injection_block:
            log.warning("injection_detected",
                        request_id=request.request_id,
                        pattern=injection_result)
            return SafetyCheckResponse(
                request_id=request.request_id,
                is_safe=False,
                violations=[f"Prompt injection detected: pattern '{injection_result}'"],
                pii_detected=False,
                injection_detected=True,
                processing_ms=self._elapsed_ms(start_ms),
            )

        # Check 3: PII detection and redaction 
        pii_detected = False
        redacted_message: Optional[str] = None

        if self._settings.safety_pii_redact and self._analyzer:
            redacted_message, pii_detected = self._check_and_redact_pii(
                request.message
            )

        return SafetyCheckResponse(
            request_id=request.request_id,
            is_safe=True,
            violations=[],
            redacted_message=redacted_message if pii_detected else None,
            pii_detected=pii_detected,
            injection_detected=False,
            processing_ms=self._elapsed_ms(start_ms),
        )

    def _check_injection(self, text: str) -> Optional[str]:
        """
        Check for injection patterns.
        Returns the matched pattern string if found, None if safe.
        """
        for pattern in _INJECTION_PATTERNS:
            match = pattern.search(text)
            if match:
                return match.group(0)[:50]  # truncated for logging
        return None

    def _check_and_redact_pii(
            self, text: str) -> tuple[str, bool]:
        """
        Detect and redact PII using Presidio.
        Returns (redacted_text, pii_was_found).
        """
        results = self._analyzer.analyze(
            text=text,
            language="en",
            entities=[
                "PERSON",
                "EMAIL_ADDRESS",
                "PHONE_NUMBER",
                "CREDIT_CARD",
                "IP_ADDRESS",
                "IBAN_CODE",
                "MEDICAL_LICENSE",
            ]
        )

        if not results:
            return text, False

        anonymized = self._anonymizer.anonymize(
            text=text,
            analyzer_results=results,
        )

        log.info("pii_redacted",
                 entity_types=[r.entity_type for r in results],
                 count=len(results))

        return anonymized.text, True

    @staticmethod
    def _elapsed_ms(start: float) -> int:
        return int((time.perf_counter() - start) * 1000)