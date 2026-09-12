#!/usr/bin/env bash
# FlowForge crash-recovery demo.
#
#   ./scripts/demo.sh up    # postgres + server (with embedded worker)
#   ./scripts/demo.sh run   # submit a DAG whose middle task crashes its worker
#                           # on attempt 1; watch the engine recover and finish it
#   ./scripts/demo.sh down
#
# The run phase polls until the execution settles, then prints the task trace
# showing the recovery (payment attempt 1 abandoned -> READY -> attempt 2
# SUCCESS) and the final execution status.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${FF_BASE:-http://localhost:8080}"
up() {
  # -a: a stopped container from a previous `down` still owns the name;
  # restart it instead of failing on the name collision in `docker run`.
  if docker ps -a --format "{{.Names}}" | grep -q "^flowforge-postgres$"; then
    docker start flowforge-postgres >/dev/null
  else
    docker run -d --name flowforge-postgres \
      -e POSTGRES_DB=flowforge -e POSTGRES_USER=flowforge -e POSTGRES_PASSWORD=flowforge \
      -p 5432:5432 docker.io/library/postgres:16-alpine
  fi
  echo "waiting for postgres..."
  until docker exec flowforge-postgres pg_isready -U flowforge -d flowforge >/dev/null 2>&1; do
    sleep 1
  done
  echo "starting server (embedded worker enabled)..."
  # Short lease + fast sweep + short backoff so the demo runs in seconds.
  # (Spring relaxed binding: FLOWFORGE_SCHEDULER_LEASE_DURATION -> flowforge.scheduler.lease-duration)
  FLOWFORGE_SCHEDULER_LEASE_DURATION=3s \
  FLOWFORGE_SCHEDULER_SCAN_INTERVAL_MS=500 \
  FLOWFORGE_RETRY_INITIAL_BACKOFF=500ms \
    nohup java -jar target/flowforge-1.0.0.jar \
      > /tmp/flowforge-demo.log 2>&1 &
  echo $! > /tmp/flowforge-demo.pid
  echo "waiting for server..."
  until curl -sf "$BASE/stats" >/dev/null 2>&1; do sleep 1; done
  echo "server up at $BASE (log: /tmp/flowforge-demo.log)"
}

run() {
  echo "--- submitting workflow (payment crashes its worker on attempt 1) ---"
  WF=$(curl -sf -X POST "$BASE/workflows" -H 'Content-Type: application/json' -d '{
    "name": "order-processing",
    "tasks": [
      {"id": "payment", "type": "HTTP", "params": {"crashOnAttempt": 1, "sleepMs": 500}},
      {"id": "fraud-check", "type": "HTTP", "dependsOn": ["payment"]},
      {"id": "shipping", "type": "HTTP", "dependsOn": ["fraud-check"]}
    ]}')
  WF_ID=$(echo "$WF" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  echo "workflow id=$WF_ID"
  EX=$(curl -sf -X POST "$BASE/workflows/$WF_ID/execute")
  EX_ID=$(echo "$EX" | python3 -c 'import json,sys; print(json.load(sys.stdin)["executionId"])')
  echo "execution id=$EX_ID"

  echo "--- waiting for settlement (crash -> lease expiry -> recovery -> done) ---"
  for _ in $(seq 1 60); do
    STATUS=$(curl -sf "$BASE/executions/$EX_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
    echo "  status=$STATUS"
    if [ "$STATUS" != "RUNNING" ]; then break; fi
    sleep 2
  done

  echo "--- task trace ---"
  curl -sf "$BASE/executions/$EX_ID" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for t in d["tasks"]:
    print("  %-12s %-10s attempt=%s worker=%s" % (t["taskId"], t["status"], t["attempt"], t.get("workerId")))
print("--- events ---")
for e in d["events"]:
    print("  %s %-12s %-10s attempt=%s %s" % (e["at"][11:19], e["taskId"] or "-", e["status"], e["attempt"], e["detail"] or ""))
print("final:", d["status"])
'
}

down() {
  if [ -f /tmp/flowforge-demo.pid ]; then kill "$(cat /tmp/flowforge-demo.pid)" 2>/dev/null || true; fi
  docker stop flowforge-postgres >/dev/null 2>&1 || true
  echo "demo stopped (postgres container kept; docker rm flowforge-postgres to drop data)"
}

case "${1:-}" in
  up) up ;;
  run) run ;;
  down) down ;;
  *) echo "usage: $0 {up|run|down}"; exit 1 ;;
esac
