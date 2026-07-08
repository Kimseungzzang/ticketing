// 대규모 정량 부하 — 정확히 100,000 iteration(reserve→cancel churn)을 ticket-command에 때린다.
//   좌석은 R섹션 120석을 순환 재사용(cancel로 churn)해 고갈 없이 10만 회를 채운다.
//   각 iteration = reserve(POST) + (성공 시) cancel(DEL) → http_reqs ≈ 15~20만.
// 실행: k6 run loadtest/scenario-load-100k.js   (Prometheus로: -o experimental-prometheus-rw)
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const CMD = 'http://localhost:8082';
const ROWS = ['A', 'B', 'C', 'D', 'E'];   // R섹션 5행 × 24 = 120석
function seatFor(i) { const n = i % 120; return `R-${ROWS[Math.floor(n / 24)]}-${(n % 24) + 1}`; }

export const options = {
  scenarios: {
    load100k: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 100000,     // ← 정확히 10만 회
      maxDuration: '5m',
    },
  },
};

export default function () {
  const seat = seatFor(exec.scenario.iterationInTest);
  const res = http.post(`${CMD}/reservations`,
    JSON.stringify({ userId: `u-${__VU}`, seatIds: [seat] }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'reserve ok(201) or conflict(409)': r => r.status === 201 || r.status === 409 });
  if (res.status === 201) {
    try { const id = JSON.parse(res.body)[0]?.id; if (id) http.del(`${CMD}/reservations/${id}`); } catch (e) { /* */ }
  }
}
