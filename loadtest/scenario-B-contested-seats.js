// 시나리오 B — 100 VU × 같은 10개 좌석 동시 POST
//
// 목적
//   비관락(@Lock(PESSIMISTIC_WRITE)) + service-side status 검증이 진짜로
//   "정확히 10명만 성공"을 보장하는지 검증.
//   그리고 락이 실제로 잡힐 때 latency 가 §1(baseline) 대비 얼마나 늘어나는지 측정.
//
// 좌석 매핑
//   A 섹션의 A-A-1 ~ A-A-10, 10개만 사용.
//   VU 매핑: __VU % 10 → 좌석당 정확히 10 VU 가 동시 시도.
//
// 기대 결과
//   - 201: 정확히 10건 (좌석당 1명만)
//   - 409: 정확히 90건 (SEAT_NOT_AVAILABLE)
//   - 4xx/5xx 외 다른 코드: 0건
//   - DB: A-A-1 ~ A-A-10 모두 RESERVED, 그 외 좌석은 영향 없음
//
// 시나리오 A 와의 비교 포인트
//   - latency 분포 변화 (락 대기 발생)
//   - p95 변화 (§1.6 anchors 와 비교)
//
// 실행 전 전제
//   1. ticket-command @ 8082
//   2. mock-data.sql 시드 reset (A-A-1~A-A-10 이 AVAILABLE 이어야 정확한 비교)
//
// 실행
//   psql -U hbrc -d ticket_db -f ticket-command-service/mock-data.sql
//   k6 run loadtest/scenario-B-contested-seats.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const successCount  = new Counter('reservations_success');
const conflictCount = new Counter('reservations_conflict');
const otherCount    = new Counter('reservations_other');

export const options = {
  vus: 100,
  iterations: 100,
  thresholds: {
    // 201 과 409 둘 다 의도된 응답 → http_req_failed 에서 제외
    'http_req_failed':       ['rate<0.01'],
    'http_req_duration':     ['p(95)<2000'],     // 락 대기 가능성 고려해 baseline 보다 느슨
    'checks':                ['rate>0.99'],
    'reservations_success':  ['count==10'],      // 정확성 검증의 핵심
    'reservations_conflict': ['count==90'],
    'reservations_other':    ['count==0'],
  },
};

const CONTESTED_SEATS = 10;
const SECTION = 'A';
const ROW     = 'A';

export default function () {
  const seatNum = ((__VU - 1) % CONTESTED_SEATS) + 1;
  const seatId  = `${SECTION}-${ROW}-${seatNum}`;
  const payload = JSON.stringify({
    userId: `loadtest-B-u-${__VU}`,
    seatIds: [seatId],
  });
  const res = http.post('http://localhost:8082/reservations', payload, {
    headers: { 'Content-Type': 'application/json' },
    // k6 가 4xx 를 자동으로 fail 처리하지 않도록 201/409 를 expected 로 등록
    responseCallback: http.expectedStatuses(201, 409),
    tags: { endpoint: 'POST /reservations (contested)' },
  });

  if (res.status === 201)      successCount.add(1);
  else if (res.status === 409) conflictCount.add(1);
  else                         otherCount.add(1);

  check(res, {
    'status is 201 or 409':           (r) => r.status === 201 || r.status === 409,
    'success has reservation id':     (r) => {
      if (r.status !== 201) return true;
      try { return JSON.parse(r.body)[0]?.id != null; } catch (e) { return false; }
    },
    'conflict has SEAT_NOT_AVAILABLE': (r) => {
      if (r.status !== 409) return true;
      try { return JSON.parse(r.body)?.error === 'SEAT_NOT_AVAILABLE'; } catch (e) { return false; }
    },
  });
}
