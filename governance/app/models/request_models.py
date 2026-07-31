"""
request_models.py — Pydantic v2 models for all inbound requests.
Strict validation: extra fields are forbidden to prevent injection
of unexpected parameters that could alter governance behaviour.
"""

from enum import Enum
from pydantic import BaseModel, Field, field_validator, model_validator, AliasGenerator  
from pydantic.alias_generators import to_camel
from typing import Optional


class LlmProvider(str, Enum):
    HUGGINGFACE = "huggingface"
    OLLAMA = "ollama"


class SafetyCheckRequest(BaseModel):
    """
    Input safety evaluation request.
    Sent by Java gateway BEFORE forwarding to LLM provider.
    """

    model_config = {
        "extra": "forbid",
        "alias_generator": AliasGenerator(
            validation_alias=to_camel,  # Read camelCase from incoming JSON
            serialization_alias=to_camel # Write camelCase back out if needed
        ),
        "populate_by_name": True  # Allows Python code to still use snake_case keyword args
    }
    request_id: str = Field(
        ...,
        min_length=1,
        max_length=36,
        description="Unique request ID for log correlation"
    )
    user_id: str = Field(
        ...,
        min_length=1,
        max_length=36,
        description="Authenticated user ID"
    )
    message: str = Field(
        ...,
        min_length=1,
        max_length=8000,
        description="User's input message"
    )
    system_prompt: Optional[str] = Field(
        default=None,
        max_length=4000,
        description="Optional system prompt"
    )
    provider: LlmProvider = Field(
        default=LlmProvider.HUGGINGFACE,
        description="LLM provider this request will be sent to"
    )

    @field_validator("message")
    @classmethod
    def message_must_not_be_whitespace_only(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("message must not be whitespace only")
        return v


class QualityCheckRequest(BaseModel):
    """
    Output quality evaluation request.
    Sent by Java gateway AFTER receiving LLM response.
    Evaluates faithfulness and relevancy.
    """

    model_config = {
        "extra": "forbid",
        "alias_generator": AliasGenerator(
            validation_alias=to_camel,  # Read camelCase from incoming JSON
            serialization_alias=to_camel # Write camelCase back out if needed
        ),
        "populate_by_name": True  # Allows Python code to still use snake_case keyword args
    }

    request_id: str = Field(..., min_length=1, max_length=36)
    user_id: str = Field(..., min_length=1, max_length=36)
    original_message: str = Field(..., min_length=1, max_length=8000)
    llm_response: str = Field(..., min_length=1, max_length=16000)
    context_documents: list[str] = Field(
        default_factory=list,
        max_length=10,
        description="Retrieved context chunks if RAG was used"
    )
    provider: LlmProvider = Field(default=LlmProvider.HUGGINGFACE)
    model: str = Field(..., min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_response_not_identical_to_input(self) -> "QualityCheckRequest":
        if self.llm_response.strip() == self.original_message.strip():
            raise ValueError("llm_response must not be identical to original_message")
        return self


class CostEstimateRequest(BaseModel):
    """
    Cost estimation request.
    Counts tokens accurately using tiktoken and returns USD cost.
    """

    model_config = {
        "extra": "forbid",
        "alias_generator": AliasGenerator(
            validation_alias=to_camel,  # Read camelCase from incoming JSON
            serialization_alias=to_camel # Write camelCase back out if needed
        ),
        "populate_by_name": True  # Allows Python code to still use snake_case keyword args
    }

    prompt_text: str = Field(..., min_length=1, max_length=12000)
    completion_text: str = Field(..., min_length=0, max_length=16000)
    provider: LlmProvider = Field(default=LlmProvider.HUGGINGFACE)
    model: str = Field(default="mistralai/Mistral-7B-Instruct-v0.3")


class EmbeddingRequest(BaseModel):
    """
    Embedding request for semantic cache scoring.
    Returns a float vector for cosine similarity comparison in Java.
    """

    model_config = {
        "extra": "forbid",
        "alias_generator": AliasGenerator(
            validation_alias=to_camel,  # Read camelCase from incoming JSON
            serialization_alias=to_camel # Write camelCase back out if needed
        ),
        "populate_by_name": True  # Allows Python code to still use snake_case keyword args
    }

    text: str = Field(..., min_length=1, max_length=8000)