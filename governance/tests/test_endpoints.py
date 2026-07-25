"""
test_endpoints.py — integration tests for FastAPI endpoints.

Uses FastAPI's AsyncClient with ASGITransport — no real HTTP server.
Tests the full request/response cycle including validation,
serialisation, and service integration.

Note: the FastAPI lifespan (model loading) must complete before
these tests run. The conftest.py client fixture handles this.
"""

import pytest


class TestHealthEndpoint:

    @pytest.mark.asyncio
    async def test_health_returns_200(self, client):
        response = await client.get("/health")
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_health_response_structure(self, client):
        response = await client.get("/health")
        data = response.json()

        assert "status" in data
        assert "service" in data
        assert "embedding_model_loaded" in data
        assert "spacy_model_loaded" in data
        assert data["status"] == "healthy"


class TestSafetyEndpoint:

    @pytest.mark.asyncio
    async def test_safe_request_passes(self, client, safe_request_data):
        response = await client.post(
            "/governance/safety",
            json=safe_request_data
        )
        assert response.status_code == 200
        data = response.json()

        assert data["isSafe"] is True
        assert data["requestId"] == safe_request_data["request_id"]
        assert isinstance(data["violations"], list)
        assert isinstance(data["processingMs"], int)

    @pytest.mark.asyncio
    async def test_injection_request_blocked(self, client, injection_request_data):
        response = await client.post(
            "/governance/safety",
            json=injection_request_data
        )
        assert response.status_code == 200
        data = response.json()

        assert data["isSafe"] is False
        assert data["injectionDetected"] is True
        assert len(data["violations"]) > 0

    @pytest.mark.asyncio
    async def test_pii_request_passes_with_redaction(
            self, client, pii_request_data):
        response = await client.post(
            "/governance/safety",
            json=pii_request_data
        )
        assert response.status_code == 200
        data = response.json()

        assert data["isSafe"] is True
        assert data["piiDetected"] is True
        assert data["redactedMessage"] is not None
        assert "4532" not in data["redactedMessage"]

    @pytest.mark.asyncio
    async def test_missing_required_field_returns_422(self, client):
        response = await client.post(
            "/governance/safety",
            json={"message": "test"}  # missing request_id, user_id
        )
        assert response.status_code == 422

    @pytest.mark.asyncio
    async def test_extra_fields_rejected(self, client, safe_request_data):
        """extra='forbid' on Pydantic model rejects unknown fields."""
        payload = {**safe_request_data, "unknown_field": "value"}
        response = await client.post("/governance/safety", json=payload)
        assert response.status_code == 422


class TestCostEndpoint:

    @pytest.mark.asyncio
    async def test_cost_estimate_returns_valid_structure(
            self, client, cost_request_data):
        response = await client.post(
            "/governance/cost",
            json=cost_request_data
        )
        assert response.status_code == 200
        data = response.json()

        assert data["promptTokens"] > 0
        assert data["completionTokens"] > 0
        assert data["totalTokens"] == data["promptTokens"] + data["completionTokens"]
        assert data["costUsd"] >= 0.0
        assert data["provider"] == "huggingface"

    @pytest.mark.asyncio
    async def test_cost_score_in_valid_range(self, client, cost_request_data):
        response = await client.post(
            "/governance/cost",
            json=cost_request_data
        )
        data = response.json()
        assert 0.0 <= data["costUsd"] <= 1.0  # reasonable for small requests


class TestEmbeddingEndpoint:

    @pytest.mark.asyncio
    async def test_embedding_returns_384_dimensions(self, client):
        response = await client.post(
            "/governance/embed",
            json={"text": "What is enterprise AI governance?"}
        )
        assert response.status_code == 200
        data = response.json()

        assert data["dimensions"] == 384
        assert len(data["embedding"]) == 384
        assert all(isinstance(v, float) for v in data["embedding"])

    @pytest.mark.asyncio
    async def test_empty_text_rejected(self, client):
        response = await client.post(
            "/governance/embed",
            json={"text": ""}
        )
        assert response.status_code == 422


class TestQualityEndpoint:

    @pytest.mark.asyncio
    async def test_quality_check_structure(
            self, client, quality_request_data):
        response = await client.post(
            "/governance/quality",
            json=quality_request_data
        )
        assert response.status_code == 200
        data = response.json()

        assert "qualityPassed" in data
        assert "faithfulnessScore" in data
        assert "relevancyScore" in data
        assert "overallScore" in data
        assert "failureReasons" in data
        assert "processingMs" in data

    @pytest.mark.asyncio
    async def test_quality_scores_in_valid_range(
            self, client, quality_request_data):
        response = await client.post(
            "/governance/quality",
            json=quality_request_data
        )
        data = response.json()

        # Scores are -1.0 (unverified) or in [0.0, 1.0]
        for field in ["faithfulnessScore", "relevancyScore", "overallScore"]:
            score = data[field]
            assert score == -1.0 or 0.0 <= score <= 1.0, \
                f"{field} = {score} is not -1.0 or in [0.0, 1.0]"

    @pytest.mark.asyncio
    async def test_identical_message_and_response_rejected(self, client):
        """Pydantic validator rejects identical message and response."""
        response = await client.post(
            "/governance/quality",
            json={
                "request_id": "test-001",
                "user_id": "user-001",
                "original_message": "What is AI?",
                "llm_response": "What is AI?",  # identical to message
                "provider": "huggingface",
                "model": "mistralai/Mistral-7B-Instruct-v0.3"
            }
        )
        assert response.status_code == 422