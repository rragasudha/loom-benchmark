#!/usr/bin/env bash
# =============================================================================
# loadtest.sh — Load test script for loom-benchmark
#
# Compares throughput and latency between:
#   - /api/aggregate/classic  (fixed thread pool, 20 threads by default)
#   - /api/aggregate/loom     (Project Loom virtual threads)
#
# Usage:
#   chmod +x loadtest.sh
#   ./loadtest.sh
#
# Options (env vars):
#   HOST=http://localhost:8080   target host
#   SOURCES=50                   data sources per request (>20 to stress classic pool)
#   AB_REQUESTS=200              total requests per test run
#   AB_CONCURRENCY=50            concurrent request count
#
# Tools required:
#   - ab (Apache Bench): usually in apache2-utils / httpd-tools
#     macOS: brew install httpd
#     Ubuntu: sudo apt-get install apache2-utils
#     Windows: included with XAMPP or use WSL
#   - curl (for the quick sanity checks)
#
# Note: If ab is not available, the script falls back to a simple curl loop.
# =============================================================================

HOST="${HOST:-http://localhost:8080}"
SOURCES="${SOURCES:-50}"        # Use 50 so classic (pool=20) has to queue 30 tasks
AB_REQUESTS="${AB_REQUESTS:-200}"
AB_CONCURRENCY="${AB_CONCURRENCY:-50}"

CLASSIC_URL="${HOST}/api/aggregate/classic?sources=${SOURCES}"
LOOM_URL="${HOST}/api/aggregate/loom?sources=${SOURCES}"
COMPARE_URL="${HOST}/api/aggregate/compare?sources=20"

# ---- Colours ----------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Colour

# =============================================================================
# 1. Quick sanity check — single request to each endpoint
# =============================================================================
echo -e "\n${CYAN}=== 1. Sanity Check (single request each) ===${NC}"

echo -e "\n${YELLOW}Classic endpoint (sources=${SOURCES}):${NC}"
curl -s "${CLASSIC_URL}" | python3 -m json.tool 2>/dev/null \
  || curl -s "${CLASSIC_URL}"

echo -e "\n\n${YELLOW}Loom endpoint (sources=${SOURCES}):${NC}"
curl -s "${LOOM_URL}" | python3 -m json.tool 2>/dev/null \
  || curl -s "${LOOM_URL}"

echo -e "\n\n${YELLOW}Compare endpoint (sources=20):${NC}"
curl -s "${COMPARE_URL}" | python3 -m json.tool 2>/dev/null \
  || curl -s "${COMPARE_URL}"

# =============================================================================
# 2. Apache Bench load test
# =============================================================================
if command -v ab &> /dev/null; then
    echo -e "\n\n${CYAN}=== 2. Apache Bench Load Test ===${NC}"
    echo -e "  Requests:    ${AB_REQUESTS}"
    echo -e "  Concurrency: ${AB_CONCURRENCY}"
    echo -e "  Sources:     ${SOURCES}"
    echo -e ""
    echo -e "${YELLOW}Note: sources=${SOURCES} > pool size 20, so CLASSIC will queue tasks.${NC}"
    echo -e "${YELLOW}LOOM should remain near single-request latency regardless.${NC}\n"

    echo -e "${GREEN}--- Classic Thread Pool ---${NC}"
    ab -n "${AB_REQUESTS}" -c "${AB_CONCURRENCY}" -q "${CLASSIC_URL}" 2>&1 \
      | grep -E "Requests per second|Time per request|Failed requests|Concurrency"

    echo -e "\n${GREEN}--- Project Loom (Virtual Threads) ---${NC}"
    ab -n "${AB_REQUESTS}" -c "${AB_CONCURRENCY}" -q "${LOOM_URL}" 2>&1 \
      | grep -E "Requests per second|Time per request|Failed requests|Concurrency"

else
    # ---- Fallback: curl loop ------------------------------------------------
    echo -e "\n\n${CYAN}=== 2. Curl Loop Load Test (ab not found) ===${NC}"
    echo -e "${YELLOW}Install ab for better statistics: apt-get install apache2-utils${NC}\n"

    CURL_CONCURRENT=10
    CURL_ROUNDS=5

    run_curl_loop() {
        local url="$1"
        local label="$2"
        local start total_ms=0

        echo -e "${GREEN}--- ${label} (${CURL_CONCURRENT} concurrent × ${CURL_ROUNDS} rounds) ---${NC}"

        for round in $(seq 1 $CURL_ROUNDS); do
            start=$(date +%s%3N)
            for i in $(seq 1 $CURL_CONCURRENT); do
                curl -s -o /dev/null "${url}" &
            done
            wait
            local elapsed=$(( $(date +%s%3N) - start ))
            total_ms=$(( total_ms + elapsed ))
            echo "  Round ${round}: ${elapsed}ms for ${CURL_CONCURRENT} concurrent requests"
        done

        local avg=$(( total_ms / CURL_ROUNDS ))
        echo -e "  ${YELLOW}Average round time: ${avg}ms${NC}\n"
    }

    run_curl_loop "${CLASSIC_URL}" "Classic Thread Pool"
    run_curl_loop "${LOOM_URL}" "Project Loom (Virtual Threads)"
fi

# =============================================================================
# 3. JVM thread count observation (requires Actuator)
# =============================================================================
echo -e "\n${CYAN}=== 3. JVM Thread Metrics (via Actuator) ===${NC}"
echo -e "${YELLOW}Live thread count during idle:${NC}"
curl -s "${HOST}/actuator/metrics/jvm.threads.live" | python3 -m json.tool 2>/dev/null \
  || curl -s "${HOST}/actuator/metrics/jvm.threads.live"

echo -e "\n\n${CYAN}=== Load Test Complete ===${NC}"
echo -e "What to observe:"
echo -e "  1. ${GREEN}Classic:${NC} totalDurationMs grows when sources > pool size (queuing)"
echo -e "  2. ${GREEN}Loom:${NC}    totalDurationMs stays near max single-source delay (~800ms)"
echo -e "  3. ${GREEN}threadName${NC} in results: 'classic-pool-N [platform]' vs 'virtual-thread-N [virtual]'"
echo -e "  4. ${GREEN}Requests per second:${NC} Loom should be significantly higher under load"
echo -e ""
echo -e "For deeper analysis:"
echo -e "  wrk -t4 -c100 -d30s '${CLASSIC_URL}'"
echo -e "  wrk -t4 -c100 -d30s '${LOOM_URL}'"
