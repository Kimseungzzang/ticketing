// 단계부하 램프 — 병목 사냥용. 100 → 500 → 1000 RPS로 도착률을 올리며 write 경로를 압박.
//   각 iteration: 자기 좌석(R섹션) reserve → cancel (churn, 좌석 고갈 없이 지속 쓰기 부하).
//   목표 RPS를 못 따라가기 시작(dropped_iterations) / 지연 폭발 / 에러 발생 = 그 지점이 병목.
//
// 실행: k6 run loadtest/scenario-staged-ramp.js
import http from 'k6/http';
import { check } from 'k6';

const ROWS = ['A', 'B', 'C', 'D', 'E'];
const PER_ROW = 24;            // R섹션 120석
const CMD = 'http://localhost:8082';

function seatForVU(vu) {
  const i = (vu - 1) % 120;
  return `R-${ROWS[Math.floor(i / PER_ROW)]}-${(i % PER_ROW) + 1}`;
}

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 1000,
      timeUnit: '1s',
      preAllocatedVUs: 600,
      maxVUs: 5000,
      stages: [
        { target: 1000, duration: '8s'  },  // baseline (이미 여유)
        { target: 3000, duration: '12s' },  // 압박
        { target: 6000, duration: '12s' },  // 한계까지
        { target: 6000, duration: '8s'  },  // 유지
      ],
    },
  },
};

export default function () {
  const seatId = seatForVU(__VU);
  const res = http.post(`${CMD}/reservations`,
    JSON.stringify({ userId: `s-${__VU}`, seatIds: [seatId] }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'reserve' } });
  check(res, { 'reserve ok(201) or conflict(409)': r => r.status === 201 || r.status === 409 });
  if (res.status === 201) {
    try { const id = JSON.parse(res.body)[0]?.id; if (id) http.del(`${CMD}/reservations/${id}`); } catch (e) { /* */ }
  }
}
