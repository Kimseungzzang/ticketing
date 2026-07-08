#!/usr/bin/env bash
# 부하테스트 스위트 러너 — 여러 k6 시나리오를 하나의 RUN id로 묶어 순차 실행한다.
#   · 각 run은 Prometheus에 testid 라벨로 기록 → Grafana "부하테스트 스위트" 대시보드(loadtest-suite)에서 run별 비교.
#   · 각 run의 k6 요약을 한 리포트(md)로 모아 마지막에 출력.
#   · 시나리오별 사전조건(DB/좌석/토큰 리셋)을 자동 처리.
#
# 사용법:
#   loadtest/run-suite.sh                              # 기본 세트(구간 A, B)
#   loadtest/run-suite.sh segment-B-booking staged-ramp B-contested-seats
#   (시나리오명 = loadtest/scenario-<이름>.js 의 <이름>)
set -u

PROM=http://localhost:9090/api/v1/write
PG="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"
PG_USER="${PG_USER:-hbrc}"
cd "$(dirname "$0")/.." || exit 1     # ticketing 루트
RUN=$(date +%m%d-%H%M%S)

# remote-write에 p95/p99/avg 트렌드 통계까지 내보내기(기본은 p99만)
export K6_PROMETHEUS_RW_TREND_STATS="avg,med,p(95),p(99),max"
export K6_PROMETHEUS_RW_SERVER_URL="$PROM"

SCEN=("$@")
[ ${#SCEN[@]} -eq 0 ] && SCEN=(segment-A-queue segment-B-booking)

REPORT="/tmp/loadtest-suite-$RUN.md"
{ echo "# 부하테스트 스위트 — RUN $RUN"; echo; echo "시나리오: ${SCEN[*]}"; echo; } > "$REPORT"

reset_ticket() {   # ticket-command 대상(staged-ramp, contested 등)
  "$PG/psql" -U "$PG_USER" -d ticket_db -f ticket-command-service/mock-data.sql >/dev/null 2>&1
  "$PG/psql" -U "$PG_USER" -d ticket_db -c "TRUNCATE outbox RESTART IDENTITY;" >/dev/null 2>&1
}
reset_booking() {  # booking 대상(segment-B) — 좌석 리셋 + 토큰 seed
  "$PG/psql" -U "$PG_USER" -d booking_db     -c "UPDATE seats SET status='AVAILABLE' WHERE status<>'AVAILABLE';" >/dev/null 2>&1
  "$PG/psql" -U "$PG_USER" -d ticket_read_db -c "UPDATE seats SET status='AVAILABLE' WHERE status<>'AVAILABLE'; TRUNCATE reservations;" >/dev/null 2>&1
  redis-cli -p 6379 --scan --pattern "booking:lock:*" 2>/dev/null | xargs -r redis-cli -p 6379 DEL >/dev/null 2>&1
  ./loadtest/monitor/seed-tokens.sh 200 >/dev/null 2>&1
}

for s in "${SCEN[@]}"; do
  file="loadtest/scenario-$s.js"
  if [ ! -f "$file" ]; then echo "  ⚠ $file 없음 — 건너뜀"; continue; fi
  testid="$s-$RUN"
  echo "▶ $s   (testid=$testid)"
  case "$s" in
    segment-B-booking)                                  reset_booking ;;
    staged-ramp|A-distinct-seats|B-contested-seats|W-churn) reset_ticket ;;
  esac
  k6 run --tag testid="$testid" -o experimental-prometheus-rw \
     --summary-trend-stats="avg,med,p(95),p(99),max" \
     "$file" > "/tmp/k6-$testid.txt" 2>&1
  {
    echo "## $s"
    echo '```'
    grep -E "http_reqs|http_req_duration|http_req_failed|iterations\.\.|checks_succ|vus_max|B_confirmed|B_seat_conflict|reservations_|dropped_iter" "/tmp/k6-$testid.txt" | sed 's/^ *//'
    echo '```'
    echo
  } >> "$REPORT"
  echo "  ✔ 완료 (testid=$testid)"
done

echo
echo "========== 스위트 요약 =========="
cat "$REPORT"
echo "리포트 파일: $REPORT"
echo "Grafana 비교 대시보드: http://localhost:3030/d/loadtest-suite  (상단 testid 드롭다운에서 이번 RUN=$RUN 선택)"
