// 시나리오 A — 100 VU × 서로 다른 좌석 1회씩 동시 POST
//
// 목적
//   "경쟁이 전혀 없는 깨끗한 케이스"에서 ticket-command의 baseline 처리 능력 측정.
//   비관락이 진짜로 잡혀야 하는 일은 발생하지 않음 (각 VU가 unique seat).
//   여기서 나오는 RPS·latency가 이후 모든 시나리오의 기준선.
//
// 좌석 매핑
//   R 섹션 (120석) 만 사용: R-A-1 ~ R-E-24.
//   VU 1..120 까지 unique 매핑 가능. 본 시나리오는 100 VU 사용.
//
// 실행 전 전제
//   1. ticket-command @ 8082 떠있음
//   2. mock-data.sql 한 번 주입되어 모든 좌석 AVAILABLE 상태
//
// 실행
//   k6 run loadtest/scenario-A-distinct-seats.js
//
// 반복 실행 시
//   좌석이 RESERVED 상태로 남아있음 → 시드 재주입 필요:
//   psql -U hbrc -d ticket_db -f ticket-command-service/mock-data.sql

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 100,
  iterations: 100,        // VU당 정확히 1회 → 총 100 POST
  thresholds: {
    'http_req_failed':   ['rate<0.01'],   // 에러율 1% 미만이어야 정상
    'http_req_duration': ['p(95)<500'],   // p95 latency 500ms 미만이면 합격
    'checks':            ['rate>0.99'],
  },
};

const ROWS = ['A', 'B', 'C', 'D', 'E'];
const PER_ROW = 24;

function seatIdForVU(vu) {
  const idx = vu - 1;                         // 0..99
  const row = ROWS[Math.floor(idx / PER_ROW)];
  const num = (idx % PER_ROW) + 1;
  return `R-${row}-${num}`;
}

export default function () {
  const seatId = seatIdForVU(__VU);
  const payload = JSON.stringify({
    userId: `loadtest-u-${__VU}`,
    seatIds: [seatId],
  });
  const res = http.post('http://localhost:8082/reservations', payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'POST /reservations' },
  });

  check(res, {
    'status is 201': (r) => r.status === 201,
    'response has reservation id': (r) => {
      try { return JSON.parse(r.body)[0]?.id != null; }
      catch (e) { return false; }
    },
  });
}
