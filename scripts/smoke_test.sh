#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# smoke_test.sh — Production smoke test suite
#
# Usage:
#   ./scripts/smoke_test.sh https://your-gateway.railway.app
#   ./scripts/smoke_test.sh http://localhost:8080   (local testing)
#
# Exit codes:
#   0 = all tests passed
#   1 = one or more tests failed
#
# Runtime: ~30-60 seconds (no LLM calls)
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
PASS=0
FAIL=0
ERRORS=()

# ── Colour codes ─────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ── Test helpers ──────────────────────────────────────────────────────────────
assert_status() {
    local name="$1"
    local expected="$2"
    local actual="$3"

    if [ "$actual" -eq "$expected" ]; then
        echo -e "${GREEN}✓${NC} $name (HTTP $actual)"
        ((PASS++))
    else
        echo -e "${RED}✗${NC} $name (expected HTTP $expected, got HTTP $actual)"
        ((FAIL++))
        ERRORS+=("$name: expected $expected got $actual")
    fi
}

assert_contains() {
    local name="$1"
    local expected="$2"
    local actual="$3"

    if echo "$actual" | grep -q "$expected"; then
        echo -e "${GREEN}✓${NC} $name (contains '$expected')"
        ((PASS++))
    else
        echo -e "${RED}✗${NC} $name (missing '$expected' in response)"
        ((FAIL++))
        ERRORS+=("$name: missing '$expected'")
    fi
}

assert_header_present() {
    local name="$1"
    local header="$2"
    local headers="$3"

    if echo "$headers" | grep -iq "$header"; then
        echo -e "${GREEN}✓${NC} $name (header '$header' present)"
        ((PASS++))
    else
        echo -e "${RED}✗${NC} $name (header '$header' missing)"
        ((FAIL++))
        ERRORS+=("$name: missing header $header")
    fi
}

# ── Test suite ────────────────────────────────────────────────────────────────
echo ""
echo -e "${YELLOW}═══════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}  Enterprise AI Governance Gateway — Smoke Tests   ${NC}"
echo -e "${YELLOW}  Target: $BASE_URL${NC}"
echo -e "${YELLOW}═══════════════════════════════════════════════════${NC}"
echo ""

# ── Test 1: Health endpoint ───────────────────────────────────────────────────
echo "── Section 1: Health ───────────────────────────────"
HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/actuator/health")
HEALTH_STATUS=$(echo "$HEALTH_RESPONSE" | tail -1)
HEALTH_BODY=$(echo "$HEALTH_RESPONSE" | head -1)

assert_status "Health endpoint accessible" 200 "$HEALTH_STATUS"
assert_contains "Health status is UP" '"status":"UP"' "$HEALTH_BODY"
assert_contains "Health shows DB component" '"db"' "$HEALTH_BODY"

# ── Test 2: Auth endpoints ────────────────────────────────────────────────────
echo ""
echo "── Section 2: Authentication ───────────────────────"

# Generate unique email for this test run
TEST_EMAIL="smoke-test-$(date +%s)@example.com"
TEST_PASSWORD="SmokeTest123!"

# Register
REGISTER_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"$TEST_EMAIL\", \"password\": \"$TEST_PASSWORD\"}")
REGISTER_STATUS=$(echo "$REGISTER_RESPONSE" | tail -1)
REGISTER_BODY=$(echo "$REGISTER_RESPONSE" | head -1)

assert_status "User registration returns 201" 201 "$REGISTER_STATUS"
assert_contains "Registration returns JWT token" '"token"' "$REGISTER_BODY"

# Extract token
TOKEN=$(echo "$REGISTER_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null || echo "")

if [ -z "$TOKEN" ]; then
    echo -e "${RED}✗${NC} Could not extract JWT token — skipping auth-dependent tests"
    FAIL=$((FAIL + 1))
else
    echo -e "${GREEN}✓${NC} JWT token extracted successfully"
    PASS=$((PASS + 1))
fi

# Login
LOGIN_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"$TEST_EMAIL\", \"password\": \"$TEST_PASSWORD\"}")
LOGIN_STATUS=$(echo "$LOGIN_RESPONSE" | tail -1)
LOGIN_BODY=$(echo "$LOGIN_RESPONSE" | head -1)

assert_status "Login returns 200" 200 "$LOGIN_STATUS"
assert_contains "Login returns JWT token" '"token"' "$LOGIN_BODY"

# Wrong password
WRONG_PASS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"$TEST_EMAIL\", \"password\": \"WrongPassword\"}")
assert_status "Wrong password returns 401" 401 "$WRONG_PASS_STATUS"

# Duplicate registration
DUPLICATE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"$TEST_EMAIL\", \"password\": \"$TEST_PASSWORD\"}")
assert_status "Duplicate registration returns 409" 409 "$DUPLICATE_STATUS"

# ── Test 3: Authentication enforcement ───────────────────────────────────────
echo ""
echo "── Section 3: Auth Enforcement ─────────────────────"

NO_TOKEN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/status")
assert_status "Protected endpoint without token returns 401" 401 "$NO_TOKEN_STATUS"

INVALID_TOKEN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/status" \
    -H "Authorization: Bearer invalid.jwt.token.here")
assert_status "Invalid JWT returns 401" 401 "$INVALID_TOKEN_STATUS"

if [ -n "$TOKEN" ]; then
    STATUS_RESPONSE=$(curl -s -w "\n%{http_code}" \
        "$BASE_URL/api/v1/status" \
        -H "Authorization: Bearer $TOKEN")
    STATUS_CODE=$(echo "$STATUS_RESPONSE" | tail -1)
    STATUS_BODY=$(echo "$STATUS_RESPONSE" | head -1)

    assert_status "Valid JWT accesses protected endpoint" 200 "$STATUS_CODE"
    assert_contains "Status shows gateway name" '"Enterprise AI Governance Gateway"' "$STATUS_BODY"
    assert_contains "Status shows providers" '"providers"' "$STATUS_BODY"
fi

# ── Test 4: Rate limiting ─────────────────────────────────────────────────────
echo ""
echo "── Section 4: Rate Limiting ─────────────────────────"

if [ -n "$TOKEN" ]; then
    # Check that rate limit header is present on successful request
    RATE_HEADERS=$(curl -s -I \
        "$BASE_URL/api/v1/status" \
        -H "Authorization: Bearer $TOKEN")
    assert_header_present "X-Rate-Limit-Remaining header present" \
        "x-rate-limit-remaining" "$RATE_HEADERS"
fi

# ── Test 5: Input validation ──────────────────────────────────────────────────
echo ""
echo "── Section 5: Input Validation ─────────────────────"

if [ -n "$TOKEN" ]; then
    # Empty message should return 400 (Bean validation)
    EMPTY_MSG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/chat" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"message": ""}')
    assert_status "Empty message returns 400" 400 "$EMPTY_MSG_STATUS"

    # Oversized message should return 400
    BIG_MESSAGE=$(python3 -c "print('word ' * 2000)")
    BIG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/chat" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"message\": \"$BIG_MESSAGE\"}")
    assert_status "Oversized message returns 400 or 422" 400 "$BIG_STATUS" || \
    assert_status "Oversized message returns 400 or 422" 422 "$BIG_STATUS"
fi

# ── Test 6: Actuator endpoints ────────────────────────────────────────────────
echo ""
echo "── Section 6: Observability ─────────────────────────"

PROMETHEUS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/actuator/prometheus")
assert_status "Prometheus metrics accessible" 200 "$PROMETHEUS_STATUS"

PROMETHEUS_BODY=$(curl -s "$BASE_URL/actuator/prometheus")
assert_contains "Prometheus has JVM metrics" "jvm_memory_used_bytes" "$PROMETHEUS_BODY"
assert_contains "Prometheus has HTTP metrics" "http_server_requests_seconds" "$PROMETHEUS_BODY"

INFO_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/actuator/info")
assert_status "Info endpoint accessible" 200 "$INFO_STATUS"

# ── Test 7: Admin requires ADMIN role ─────────────────────────────────────────
echo ""
echo "── Section 7: RBAC ──────────────────────────────────"

if [ -n "$TOKEN" ]; then
    # Regular user cannot access admin endpoints
    ADMIN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        "$BASE_URL/api/v1/admin/stats" \
        -H "Authorization: Bearer $TOKEN")
    assert_status "Regular user cannot access admin endpoints (403)" 403 "$ADMIN_STATUS"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${YELLOW}═══════════════════════════════════════════════════${NC}"
echo -e "Results: ${GREEN}$PASS passed${NC} / ${RED}$FAIL failed${NC}"

if [ ${#ERRORS[@]} -gt 0 ]; then
    echo ""
    echo -e "${RED}Failures:${NC}"
    for err in "${ERRORS[@]}"; do
        echo -e "  ${RED}•${NC} $err"
    done
fi

echo -e "${YELLOW}═══════════════════════════════════════════════════${NC}"
echo ""

if [ $FAIL -gt 0 ]; then
    exit 1
fi

exit 0