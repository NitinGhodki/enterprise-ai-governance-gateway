"""
response_models.py — Pydantic v2 models for all outbound responses.
"""

from pydantic import BaseModel, Field, AliasGenerator
from pydantic.alias_generators import to_camel
from typing import Optional


class SafetyCheckResponse(BaseModel):
    """Response from /governance/safety"""

    model_config = {
        "alias_generator": AliasGenerator(
            serialization_alias=to_camel # Write camelCase back out to Java
        ),
        "populate_by_name": True
    }
    
    request_id: str
    is_safe: bool
    violations: list[str] = Field(default_factory=list)
    # Redacted version of the input if PII was found and redacted
    # None if input was safe and unmodified
    redacted_message: Optional[str] = None
    pii_detected: bool = False
    injection_detected: bool = False
    processing_ms: int


class QualityCheckResponse(BaseModel):
    """Response from /governance/quality"""

    model_config = {
        "alias_generator": AliasGenerator(
            serialization_alias=to_camel # Write camelCase back out to Java
        ),
        "populate_by_name": True
    }

    request_id: str
    quality_passed: bool
    faithfulness_score: float = Field(ge=0.0, le=1.0)
    relevancy_score: float = Field(ge=0.0, le=1.0)
    overall_score: float = Field(ge=0.0, le=1.0)
    failure_reasons: list[str] = Field(default_factory=list)
    processing_ms: int


class CostEstimateResponse(BaseModel):
    """Response from /governance/cost"""

    model_config = {
        "alias_generator": AliasGenerator(
            serialization_alias=to_camel # Write camelCase back out to Java
        ),
        "populate_by_name": True
    }

    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    cost_usd: float
    provider: str
    model: str


class EmbeddingResponse(BaseModel):
    """Response from /governance/embed"""

    model_config = {
        "alias_generator": AliasGenerator(
            serialization_alias=to_camel # Write camelCase back out to Java
        ),
        "populate_by_name": True
    }
    
    embedding: list[float]
    dimensions: int
    model: str


class HealthResponse(BaseModel):
    """Response from /health"""
    
    model_config = {
        "alias_generator": AliasGenerator(
            serialization_alias=to_camel # Write camelCase back out to Java
        ),
        "populate_by_name": True
    }

    status: str
    service: str
    embedding_model_loaded: bool
    spacy_model_loaded: bool