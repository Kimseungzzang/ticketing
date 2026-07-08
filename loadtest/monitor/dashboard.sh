#!/usr/bin/env bash
# 티켓팅 구간별 부하 모니터 — Redis/Postgres를 주기 폴링해 4구간을 한 화면에 띄운다.
#   A 대기열(queue) · B 예약(booking) · C 발행(MyKafka) · D 동기화(CQRS query)
#
# k6는 HTTP 구간(A,B)의 RPS/latency만 본다. 이 대시보드는 k6가 못 보는
#   "이벤트가 흘러가는 인프라 상태"(큐 깊이·좌석 lock·발행/소비 누적·수렴 lag)를 실시간으로 보여준다.
#   → k6를 한 창, 이 대시보드를 다른 창에 띄우고 같이 본다(cmux면 new-split).
#
# 사용법: loadtest/monitor/dashboard.sh [EVENT_ID] [간격초]
#   예:   loadtest/monitor/dashboard.sh EVT2026-001 1
set -u

EVENT="${1:-EVT2026-001}"
INTERVAL="${2:-1}"
REDIS_PORT="${REDIS_PORT:-6379}"
PG="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}/psql"
PG_USER="${PG_USER:-hbrc}"

# 색상
R='\033[31m'; G='\033[32m'; Y='\033[33m'; C='\033[36m'; B='\033[1m'; N='\033[0m'

rc() { redis-cli -p "$REDIS_PORT" "$@" 2>/dev/null; }
pq() { "$PG" -U "$PG_USER" -d "$1" -tA -c "$2" 2>/dev/null; }
# lag 색상: 임계 넘으면 경고색
lagcol() { local v=$1; if [ "$v" -gt 100 ]; then echo "$R"; elif [ "$v" -gt 10 ]; then echo "$Y"; else echo "$G"; fi; }

prev_taken=0; prev_sold=0; prev_t=0
# 초기 스냅샷(첫 화면 처리율 0 방지)
prev_t=$(date +%s)

trap 'printf "\n종료.\n"; exit 0' INT

while true; do
  now=$(date +%s)

  # ── A 대기열 (Redis) ──
  qdepth=$(rc ZCARD "queue:sorted:$EVENT"); qdepth=${qdepth:-?}
  slot=$(rc GET "queue:active:count:$EVENT"); slot=${slot:-0}
  tokens=$(rc --scan --pattern "queue:entry:*" | wc -l | tr -d ' ')

  # ── B 예약 (Redis) ──
  locks=$(rc --scan --pattern "booking:lock:$EVENT:*" | wc -l | tr -d ' ')
  remain=$(rc GET "booking:seats:remaining:$EVENT"); remain=${remain:-?}

  # ── C 발행 (booking_db: confirm된 좌석 = 발행된 booking-events 누적) ──
  taken=$(pq booking_db "SELECT count(*) FROM seats WHERE status='TAKEN'"); taken=${taken:-0}

  # ── D 동기화 (ticket_read_db: 소비 반영된 좌석) + 수렴 lag ──
  sold=$(pq ticket_read_db "SELECT count(*) FROM seats WHERE status='SOLD'"); sold=${sold:-0}
  lag=$((taken - sold)); [ "$lag" -lt 0 ] && lag=0
  # 참고: ticket-command(outbox) 경로 백로그 — booking 경로엔 outbox 없으나 staged 부하용으로 표시
  outbox=$(pq ticket_db "SELECT count(*) FROM outbox WHERE status='PENDING'"); outbox=${outbox:-0}

  # ── 처리율 (초당 증가) ──
  dt=$((now - prev_t)); [ "$dt" -le 0 ] && dt=1
  prate=$(( (taken - prev_taken) / dt ))
  crate=$(( (sold - prev_sold) / dt ))
  prev_taken=$taken; prev_sold=$sold; prev_t=$now
  lc=$(lagcol "$lag")

  clear
  printf "${B}┌─ 티켓팅 구간별 부하 모니터 — %s ──────── %s ┐${N}\n" "$EVENT" "$(date +%H:%M:%S)"
  printf "│ ${C}${B}A 대기열${N}  큐깊이 ${B}%-7s${N} 활성slot ${B}%-5s${N} 발급토큰 %-6s │\n" "$qdepth" "$slot" "$tokens"
  printf "│ ${C}${B}B 예약  ${N}  좌석lock ${B}%-6s${N} 남은좌석 ${B}%-7s${N}            │\n" "$locks" "$remain"
  printf "│ ${C}${B}C 발행  ${N}  confirm누적 ${B}%-6s${N} 발행율 ${G}%s/s${N}             │\n" "$taken" "$prate"
  printf "│ ${C}${B}D 동기화${N}  read SOLD ${B}%-7s${N} 소비율 ${G}%s/s${N}  수렴lag ${lc}${B}%s${N}   │\n" "$sold" "$crate" "$lag"
  printf "│           outbox PENDING %-6s (ticket-command 경로)     │\n" "$outbox"
  printf "${B}└──────────────────────────────────────────────────────┘${N}\n"
  printf "  ${lc}●${N} 수렴lag = 발행(C)−소비(D), 부하 중 계속 커지면 consumer가 병목\n"
  printf "  (Ctrl+C 종료 · %ss 간격)\n" "$INTERVAL"

  sleep "$INTERVAL"
done
