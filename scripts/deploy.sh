#!/usr/bin/env bash
# deploy.sh — Build, push, and deploy both services to Railway
# Usage: ./scripts/deploy.sh [--local | --railway]

set -euo pipefail

MODE="${1:---railway}"
echo "Deploy mode: $MODE"

# ── Local deployment ──────────────────────────────────────────────────────────
if [ "$MODE" = "--local" ]; then
    echo "Building and starting local Docker Compose stack..."

    # Validate .env exists
    if [ ! -f .env ]; then
        echo "Error: .env file not found. Copy .env.example and fill in values."
        exit 1
    fi

    # Build both images
    docker-compose build --no-cache

    # Start all services
    docker-compose up -d

    echo "Waiting for services to be healthy..."
    sleep 10

    # Wait for gateway health
    MAX_ATTEMPTS=30
    ATTEMPT=0
    until curl -s http://localhost:8080/actuator/health | grep -q '"status":"UP"'; do
        ATTEMPT=$((ATTEMPT + 1))
        if [ $ATTEMPT -ge $MAX_ATTEMPTS ]; then
            echo "Gateway failed to become healthy after ${MAX_ATTEMPTS} attempts"
            docker-compose logs gateway
            exit 1
        fi
        echo "Waiting for gateway... attempt $ATTEMPT/$MAX_ATTEMPTS"
        sleep 5
    done

    echo "All services healthy. Running smoke tests..."
    ./scripts/smoke_test.sh http://localhost:8080

    echo ""
    echo "Local deployment complete."
    echo "Gateway: http://localhost:8080"
    echo "Grafana: http://localhost:3000 (admin/admin)"
    echo "Prometheus: http://localhost:9090"
    exit 0
fi

# ── Railway deployment ────────────────────────────────────────────────────────
if [ "$MODE" = "--railway" ]; then
    # Verify Railway CLI is installed
    if ! command -v railway &> /dev/null; then
        echo "Railway CLI not found. Install: npm install -g @railway/cli"
        exit 1
    fi

    echo "Deploying governance service..."
    cd governance
    railway up --service governance --detach
    cd ..

    echo "Deploying gateway service..."
    cd gateway
    railway up --service gateway --detach
    cd ..

    echo "Waiting for deployment to complete..."
    sleep 30

    # Get public URL
    GATEWAY_URL=$(railway domain --service gateway 2>/dev/null || echo "")
    if [ -z "$GATEWAY_URL" ]; then
        echo "Could not retrieve gateway URL. Check Railway dashboard."
        exit 1
    fi

    echo "Running production smoke tests against $GATEWAY_URL..."
    ./scripts/smoke_test.sh "https://$GATEWAY_URL"

    echo ""
    echo "Railway deployment complete."
    echo "Gateway URL: https://$GATEWAY_URL"
    echo "Prometheus (local): http://localhost:9090"
    exit 0
fi

echo "Unknown mode: $MODE. Use --local or --railway"
exit 1