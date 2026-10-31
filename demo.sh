#!/usr/bin/env bash
set -e
export PATH="/opt/homebrew/bin:$PATH"

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

prompt() {
    echo -e "\n${YELLOW}=== $1 ===${NC}"
    echo -e "${CYAN}Press [Enter] to run...${NC}"
    read -r
}

command -v jq >/dev/null || { echo "jq is required (brew install jq)"; exit 1; }

BASE_URL="http://localhost:8080/api/v1"
AUTH_OP="Authorization: Bearer dev-operator-acme"
AUTH_ADMIN="Authorization: Bearer dev-admin-acme"

# State is in memory: a second run would find demo-trip already invoiced (and cached routes would hide the outage).
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/trips/demo-trip" -H "$AUTH_OP" || true)
if [ "$code" = "200" ]; then echo "State from a previous run found. Restart the app (state is in-memory), then re-run."; exit 1; fi

clear
echo -e "${GREEN}================================================================${NC}"
echo -e "${GREEN}  GPS Gap Backfill & Billable Distance Reconstruction Demo Script ${NC}"
echo -e "${GREEN}================================================================${NC}"

prompt "STEP 1: Submit Trip with GPS Hole (Routes via Provider)"
curl -s -X PUT "$BASE_URL/trips/demo-trip" \
  -H "$AUTH_OP" \
  -H "Content-Type: application/json" \
  -d '{
    "pings": [
      {"lat": 12.9000, "lon": 77.6000, "ts": 1700000000000, "accuracyM": 5.0},
      {"lat": 12.9360, "lon": 77.6000, "ts": 1700000600000, "accuracyM": 5.0}
    ],
    "stops": [],
    "endTs": 1700000600000
  }' | jq '{tripId, status, ruleVersion, totalKm, byRule, stats}'

prompt "STEP 2: Repeat Trip Corridor (Demonstrating Cache Hit - 0 Provider Calls)"
curl -s -X PUT "$BASE_URL/trips/demo-trip-2" \
  -H "$AUTH_OP" \
  -H "Content-Type: application/json" \
  -d '{
    "pings": [
      {"lat": 12.9000, "lon": 77.6000, "ts": 1700000000000, "accuracyM": 5.0},
      {"lat": 12.9360, "lon": 77.6000, "ts": 1700000600000, "accuracyM": 5.0}
    ],
    "stops": [],
    "endTs": 1700000600000
  }' | jq '{tripId, status, totalKm, stats: {routableSegments: .stats.routableSegments, providerRequests: .stats.providerRequests, cacheHits: .stats.cacheHits}}'

prompt "STEP 3: Freeze Invoice (Close Invoice as Admin)"
curl -s -X POST "$BASE_URL/trips/demo-trip/close-invoice" \
  -H "$AUTH_ADMIN" | jq .

prompt "STEP 4: Bad GPS fix ~17 km away within 5 min (implied ~200 km/h) is dropped; invoice untouched"
curl -s -X POST "$BASE_URL/trips/demo-trip/pings" \
  -H "$AUTH_OP" \
  -H "Content-Type: application/json" \
  -d '[
    {"lat": 13.0500, "lon": 77.6000, "ts": 1700000300000, "accuracyM": 5.0}
  ]' | jq '{tripId, stats: {teleports: .stats.teleports, pingsIn: .stats.pingsIn}, billing}'

prompt "STEP 5: Real Detour Late Ping (Generates Signed Audit Delta)"
curl -s -X POST "$BASE_URL/trips/demo-trip/pings" \
  -H "$AUTH_OP" \
  -H "Content-Type: application/json" \
  -d '[
    {"lat": 12.9090, "lon": 77.6050, "ts": 1700000150000, "accuracyM": 5.0}
  ]' | jq '{tripId, totalKm, billing}'

echo -e "\n${GREEN}Checking Adjustments Ledger:${NC}"
curl -s "$BASE_URL/trips/demo-trip/adjustments" \
  -H "$AUTH_OP" | jq .

prompt "STEP 6: Simulated Provider Outage & Degradation"
curl -s -X POST "$BASE_URL/admin/provider-outage?on=true" -H "$AUTH_ADMIN" | jq .
curl -s -X PUT "$BASE_URL/trips/outage-trip" \
  -H "$AUTH_OP" \
  -H "Content-Type: application/json" \
  -d '{
    "pings": [
      {"lat": 12.8000, "lon": 77.5000, "ts": 1700000000000, "accuracyM": 5.0},
      {"lat": 12.8360, "lon": 77.5000, "ts": 1700000600000, "accuracyM": 5.0}
    ],
    "stops": [],
    "endTs": 1700000600000
  }' | jq '{tripId, status, totalKm, byRule}'

echo -e "\n${CYAN}On-call view (retries/failures recorded, trip queued):${NC}"
curl -s http://localhost:8081/actuator/prometheus | grep -E '^gps_(provider_failures|provider_retries|degraded_queue_depth|provider_circuit_state)' || true

echo -e "\n${CYAN}Turning Provider Back ON...${NC}"
curl -s -X POST "$BASE_URL/admin/provider-outage?on=false" -H "$AUTH_ADMIN" | jq .
echo -e "${YELLOW}Waiting 6 seconds for background @Scheduled RecomputeJob to recover...${NC}"
sleep 6
curl -s "$BASE_URL/trips/outage-trip" -H "$AUTH_OP" | jq '{tripId, status, totalKm, byRule}'

prompt "STEP 7: High-Throughput Stress Benchmark (10,000 trips / 12.4M pings)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
$JAVA_HOME/bin/java -Xmx256m -cp target/classes com.moveinsync.gps.bench.Benchmark 10000 4

prompt "STEP 8: Automated Test Suite (23/23 Passing)"
mvn test -o

echo -e "\n${GREEN}Demo Complete!${NC}"