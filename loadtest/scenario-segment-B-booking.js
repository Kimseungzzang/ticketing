// 구간 B — 예약(booking) 부하. 대기열을 우회(seed-tokens.sh로 토큰 사전심기)하고
//   create(SETNX 좌석 lock) → confirm(→ MyKafka booking-events 발행) 만 순수하게 폭격한다.
//   confirm이 일어나면 대시보드의 C 발행 / D 동기화 줄(누적·수렴lag)이 올라간다.
//
// 사전 준비(중요):
//   1) loadtest/monitor/seed-tokens.sh 200          # 토큰 200개 심기 + tokens.json 생성
//   2) booking_db / ticket_read_db 좌석을 AVAILABLE로 리셋(재실행 시)
// 실행: k6 run loadtest/scenario-segment-B-booking.js
//   (옆 창: loadtest/monitor/dashboard.sh EVT2026-001 1)
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BOOKING = 'http://localhost:8084';
const EVENT = 'EVT2026-001';

// seed-tokens.sh가 만든 토큰 목록 (없으면 안내 메시지와 함께 실패)
const tokens = new SharedArray('tokens', () => JSON.parse(open('./monitor/tokens.json')));

const createLat  = new Trend('B_create_latency', true);
const confirmLat = new Trend('B_confirm_latency', true);
const conflict   = new Counter('B_seat_conflict');   // SETNX 좌석 경쟁 패배(409)
const confirmed  = new Counter('B_confirmed');        // = 발행된 booking-events 수

const ROWS = ['A', 'B', 'C', 'D', 'E'];   // R섹션 5행 × 24 = 120석
function seatFor(globalIdx) {
  const i = globalIdx % 120;
  return `R-${ROWS[Math.floor(i / 24)]}-${(i % 24) + 1}`;
}

export const options = {
  scenarios: {
    booking: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 120,          // R섹션 120석을 distinct하게 1회씩 (좌석 고갈 = 정상 종료)
      maxDuration: '60s',
    },
  },
};

export default function () {
  const u = tokens[exec.scenario.iterationInTest % tokens.length];
  const seat = seatFor(exec.scenario.iterationInTest);
  const h = { headers: { 'X-User-Id': u.userId, 'Content-Type': 'application/json' } };

  // create — entryToken 검증 + Redis SETNX 좌석 lock + 재고 DECR
  const c = http.post(`${BOOKING}/api/booking`,
    JSON.stringify({ eventId: EVENT, seatId: seat, entryToken: u.token }), h);
  createLat.add(c.timings.duration);
  if (c.status === 409) { conflict.add(1); return; }   // 좌석 경쟁 패배
  if (c.status !== 201) return;

  // confirm — booking_db seat TAKEN + MyKafka 발행
  const bid = JSON.parse(c.body).id;
  const cf = http.post(`${BOOKING}/api/booking/${bid}/confirm`, null,
    { headers: { 'X-User-Id': u.userId } });
  confirmLat.add(cf.timings.duration);
  if (check(cf, { 'confirm 200': r => r.status === 200 })) confirmed.add(1);
}
