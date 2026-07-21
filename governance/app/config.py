"""
config.py — centralised configuration for the governance service.

Uses pydantic-settings: reads from environment variables first,
falls back to defaults. Type-validated at startup — if HF_API_KEY
is missing and required, the service fails fast at startup, not
on the first request.
"""

from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


class Settings(BaseSettings):
    """
    All settings read from environment variables.
    Field names map to environment variable names (uppercase).
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
    )

    # Service identity
    service_name: str = "enterprise-ai-governance"
    environment: str = Field(default="production", alias="ENVIRONMENT")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")

    # HuggingFace (used by RAGAS evaluator)
    hf_api_key: str = Field(default="", alias="HF_API_KEY")
    hf_model: str = Field(
        default="mistralai/Mistral-7B-Instruct-v0.3",
        alias="HF_MODEL"
    )

    # Embedding model for semantic cache scoring
    # all-MiniLM-L6-v2: 22MB, 384 dims, runs in ~50MB RAM
    embedding_model: str = Field(
        default="sentence-transformers/all-MiniLM-L6-v2",
        alias="EMBEDDING_MODEL"
    )

    # Safety thresholds
    safety_max_input_length: int = Field(default=8000, alias="SAFETY_MAX_INPUT_LENGTH")
    safety_injection_block: bool = Field(default=True, alias="SAFETY_INJECTION_BLOCK")
    safety_pii_redact: bool = Field(default=True, alias="SAFETY_PII_REDACT")

    # Quality thresholds
    quality_min_faithfulness: float = Field(default=0.70, alias="QUALITY_MIN_FAITHFULNESS")
    quality_evaluation_enabled: bool = Field(default=True, alias="QUALITY_EVALUATION_ENABLED")

    # Cost pricing (per 1K tokens, USD)
    cost_huggingface_input_per_1k: float = Field(default=0.0001, alias="COST_HF_INPUT")
    cost_huggingface_output_per_1k: float = Field(default=0.0001, alias="COST_HF_OUTPUT")
    cost_ollama_per_1k: float = Field(default=0.0, alias="COST_OLLAMA")

    # Performance
    max_concurrent_evaluations: int = Field(default=4, alias="MAX_CONCURRENT_EVALS")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """
    Returns a cached Settings instance.
    @lru_cache ensures the .env file is read once at startup,
    not on every request.
    """
    return Settings()