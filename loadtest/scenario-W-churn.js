// 시나리오 W — 지속 쓰기 부하 (reserve → cancel 반복 churn)
//
// 목적
//   command 쓰기 경로를 일정 시간 계속 바쁘게 만든다. scenario-R(읽기)와 동시에 돌려
//   "쓰기가 도는 동안 읽기 성능" 비교 + (publish on일 때) MyKafka 이벤트 발행 부하 생성.
//
// 설계
//   각 VU가 자기 전용 좌석(R 섹션, VU 기반 매핑)을 reserve → 곧바로 cancel.
//   좌석이 AVAILABLE 로 되돌아오므로 고갈/경쟁 없이 무한 churn 가능.
//   매 반복이 실제 UPDATE(seat)+INSERT(reservation)+UPDATE×2(cancel) 를 일으킨다.
//
// 파라미터 (env): VUS(기본 20, ≤120), DURATION(기본 20s)
//
// 실행
//   VUS=20 DURATION=20s k6 run loadtest/scenario-W-churn.js

import http from 'k6/http';
import { check } from 'k6';

const ROWS = ['A', 'B', 'C', 'D', 'E'];
const PER_ROW = 24;
const CMD = 'http://localhost:8082';

function seatIdForVU(vu) {
  const idx = (vu - 1) % 120;
  const row = ROWS[Math.floor(idx / PER_ROW)];
  const num = (idx % PER_ROW) + 1;
  return `R-${row}-${num}`;
}

export const options = {
  scenarios: {
    write: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.VUS || '20'),
      duration: __ENV.DURATION || '20s',
    },
  },
};

export default function () {
  const seatId = seatIdForVU(__VU);
  const payload = JSON.stringify({ userId: `w-${__VU}`, seatIds: [seatId] });
  const res = http.post(`${CMD}/reservations`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'POST /reservations' },
  });
  check(res, { 'reserve 201': (r) => r.status === 201 });
  if (res.status === 201) {
    try {
      const id = JSON.parse(res.body)[0]?.id;
      if (id) http.del(`${CMD}/reservations/${id}`, null, { tags: { endpoint: 'DELETE /reservations' } });
    } catch (e) { /* ignore */ }
  }
}
