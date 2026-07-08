#!/usr/bin/env bash
# CQRS e2e 실증 — booking(write) → MyKafka[booking-events] → query(read projection).
#   booking에서 좌석 확정(confirm) → booking-events 발행 → query consumer가 컨슘 → read DB에 projection.
#   "쓰는 쪽(booking)과 읽는 쪽(query)이 서로 모른 채 MyKafka로만 이어진다"를 눈으로 확인한다.
# 전제: booking(:8084), query(:8083), MyKafka(:9092), postgres(booking_db/ticket_read_db) 실행 중.
# 사용: loadtest/verify-cqrs.sh
set -u
BOOKING=${BOOKING:-http://localhost:8084}
QUERY=${QUERY:-http://localhost:8083}
EVENT=EVT2026-001
USER="cqrs-test-$$"
export PGPASSWORD=""
PG="psql -h localhost -p 5432 -U hbrc -tAc"

echo "① booking_db에서 AVAILABLE 좌석 하나 고르기"
SEAT=$($PG "select seat_id from seats where event_id='$EVENT' and status='AVAILABLE' order by random() limit 1;" -d booking_db)
[ -z "$SEAT" ] && { echo "❌ AVAILABLE 좌석 없음"; exit 1; }
echo "   seat=$SEAT  user=$USER"

echo "②a 입장토큰 우회 — queue admit이 발급할 값을 Redis에 직접 심는다(queue-service 없이 booking 검증 통과)"
TOKEN="cqrs-token-$$"
redis-cli -p 6379 SET "queue:entry:$USER" "$TOKEN" >/dev/null
echo "   SET queue:entry:$USER = $TOKEN"

echo "②b POST /api/booking (write 모델에 예약 생성)"
CREATE=$(curl -s -X POST "$BOOKING/api/booking" -H "X-User-Id: $USER" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVENT\",\"seatId\":\"$SEAT\",\"entryToken\":\"$TOKEN\"}")
echo "   → $CREATE"
BID=$(printf '%s' "$CREATE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
[ -z "$BID" ] && { echo "❌ bookingId 파싱 실패 (booking 응답 확인)"; exit 1; }
echo "   bookingId=$BID"

echo "③ POST /api/booking/$BID/confirm  → 여기서 booking-events 발행됨"
CONFIRM=$(curl -s -X POST "$BOOKING/api/booking/$BID/confirm" -H "X-User-Id: $USER")
echo "   → $CONFIRM"

echo "④ eventual consistency 대기 (consumer 폴링+반영, 최대 5초 폴링)"
FOUND=""
for i in $(seq 1 10); do
  sleep 0.5
  ROW=$($PG "select id,status from reservations where id='$BID';" -d ticket_read_db 2>/dev/null)
  if [ -n "$ROW" ]; then FOUND="$ROW"; echo "   [${i}] read_db 반영됨: $ROW"; break; fi
  echo "   [${i}] 아직 없음..."
done

echo "⑤ query API로도 조회 (read 서비스가 projection 제공하는지)"
echo "   GET $QUERY/reservations?userId=$USER"
curl -s "$QUERY/reservations?userId=$USER" | python3 -m json.tool 2>/dev/null || curl -s "$QUERY/reservations?userId=$USER"
echo ""
if [ -n "$FOUND" ]; then
  echo "✅ CQRS 관통 — booking write → MyKafka booking-events → query read projection 반영 확인."
else
  echo "❌ 5초 내 read_db에 반영 안 됨 — consumer/토픽/broker 로그 확인 필요."
fi
