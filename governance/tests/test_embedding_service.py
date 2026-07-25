"""
test_embedding_service.py — unit tests for EmbeddingService.
Uses real model — all tests run with actual inference.
"""

import pytest

from app.models.request_models import EmbeddingRequest


class TestEmbedding:

    @pytest.mark.asyncio
    async def test_embedding_returns_correct_dimensions(self, embedding_service):
        request = EmbeddingRequest(text="What is machine learning?")
        result = await embedding_service.embed(request)

        assert result.dimensions == 384          # all-MiniLM-L6-v2
        assert len(result.embedding) == 384
        assert result.model is not None

    @pytest.mark.asyncio
    async def test_embedding_values_in_valid_range(self, embedding_service):
        request = EmbeddingRequest(text="Enterprise AI governance.")
        result = await embedding_service.embed(request)

        # Normalised embeddings: each component in [-1.0, 1.0]
        for value in result.embedding:
            assert -1.0 <= value <= 1.0

    @pytest.mark.asyncio
    async def test_similar_texts_have_high_cosine_similarity(
            self, embedding_service):
        import numpy as np

        req1 = EmbeddingRequest(text="What is the Professional plan price?")
        req2 = EmbeddingRequest(text="How much does the Professional plan cost?")
        req3 = EmbeddingRequest(text="What is quantum entanglement?")

        r1 = await embedding_service.embed(req1)
        r2 = await embedding_service.embed(req2)
        r3 = await embedding_service.embed(req3)

        v1 = np.array(r1.embedding)
        v2 = np.array(r2.embedding)
        v3 = np.array(r3.embedding)

        # Similar questions should have high similarity
        sim_similar  = float(v1 @ v2)
        # Unrelated question should have low similarity
        sim_unrelated = float(v1 @ v3)

        assert sim_similar > 0.85, \
            f"Expected similar questions >0.85, got {sim_similar:.4f}"
        assert sim_similar > sim_unrelated, \
            f"Similar ({sim_similar:.4f}) should exceed unrelated ({sim_unrelated:.4f})"

    @pytest.mark.asyncio
    async def test_embedding_normalised(self, embedding_service):
        """Unit vector: L2 norm should be ~1.0."""
        import math
        request = EmbeddingRequest(text="Test normalisation.")
        result = await embedding_service.embed(request)

        norm = math.sqrt(sum(v * v for v in result.embedding))
        assert abs(norm - 1.0) < 0.001, \
            f"Expected unit vector (norm≈1.0), got {norm:.6f}"