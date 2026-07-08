#!/usr/bin/env bash
# 구간 B(예약) 부하용 — 대기열을 우회해 entryToken을 Redis에 직접 심는다.
#   부하테스트 "구간 격리" 기법: 앞 구간(대기열 admit, slot 5/2초라 느림)을 건너뛰고
#   예약(create/confirm) 구간만 순수하게 폭격하기 위함.
#
#   booking-service의 create()는 redis `queue:entry:<userId>` == request.entryToken 을 검증한다.
#   여기서 loaduser-1..N 의 토큰을 미리 심고, 같은 값을 tokens.json으로 떨궈 k6가 읽게 한다.
#
# 사용법: loadtest/monitor/seed-tokens.sh <N> [EVENT_ID]
#   예:   loadtest/monitor/seed-tokens.sh 500
set -eu

N="${1:?사용법: seed-tokens.sh <유저수> [EVENT_ID]}"
EVENT="${2:-EVT2026-001}"
REDIS_PORT="${REDIS_PORT:-6379}"
OUT="$(dirname "$0")/tokens.json"
TTL=3600   # 1시간 (부하 도는 동안 유지)

echo "[seed] $N 명 entryToken 심는 중 (event=$EVENT, redis:$REDIS_PORT)..."

# redis 파이프라인으로 한 번에 (느린 왕복 방지)
{
  for i in $(seq 1 "$N"); do
    echo "SET queue:entry:loaduser-$i tok-$i EX $TTL"
  done
} | redis-cli -p "$REDIS_PORT" --pipe >/dev/null

# 좌석 재고도 넉넉히(좌석 풀보다 VU가 많을 때 create가 막히지 않도록)
redis-cli -p "$REDIS_PORT" SET "booking:seats:remaining:$EVENT" "$N" >/dev/null

# k6가 읽을 토큰 목록 — userId/token 쌍
{
  echo "["
  for i in $(seq 1 "$N"); do
    sep=","; [ "$i" -eq "$N" ] && sep=""
    echo "  {\"userId\":\"loaduser-$i\",\"token\":\"tok-$i\"}$sep"
  done
  echo "]"
} > "$OUT"

echo "[seed] 완료 → $OUT ($N 쌍). 좌석재고 $N 으로 설정."
echo "[seed] 정리하려면: redis-cli -p $REDIS_PORT --scan --pattern 'queue:entry:loaduser-*' | xargs redis-cli -p $REDIS_PORT DEL"
