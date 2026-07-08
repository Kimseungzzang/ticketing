// 서비스맵(Jaeger System Architecture)용 — gateway를 통해 전 서비스를 관통하는 트래픽.
//   iteration을 3갈래로 나눠 gateway → {queue / ticket-command / booking→카프카→ticket-query} 를 고루 호출.
//   → Jaeger 서비스맵에 gateway·queue·booking·ticket-command·ticket-query 노드와 엣지가 그려진다.
// 사전: loadtest/monitor/seed-tokens.sh 200  (booking용 토큰)
// 실행: k6 run loadtest/scenario-gateway-mesh.js
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const GW = 'http://localhost:8080';
const EVENT = 'EVT2026-001';
const tokens = new SharedArray('t', () => JSON.parse(open('./monitor/tokens.json')));
const ROWS = ['A', 'B', 'C', 'D', 'E'];
function seat(i) { const n = i % 120; return `R-${ROWS[Math.floor(n / 24)]}-${(n % 24) + 1}`; }

export const options = {
  scenarios: {
    mesh: {
      executor: 'constant-arrival-rate',
      rate: 150, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 150, maxVUs: 400,
    },
  },
};

export default function () {
  const it = exec.scenario.iterationInTest;
  const kind = it % 3;

  if (kind === 0) {
    // gateway → queue-service (대기열)
    http.post(`${GW}/api/queue/enter?eventId=${EVENT}`, null, { headers: { 'X-User-Id': `mesh-${it}` } });
  } else if (kind === 1) {
    // gateway → ticket-command (CQRS 쓰기)
    http.post(`${GW}/reservations`,
      JSON.stringify({ userId: `mesh-${it}`, seatIds: [seat(it)] }),
      { headers: { 'Content-Type': 'application/json' } });
  } else {
    // gateway → booking → (카프카) → ticket-query  (완전한 e2e)
    const u = tokens[it % tokens.length];
    const c = http.post(`${GW}/api/booking`,
      JSON.stringify({ eventId: EVENT, seatId: seat(it), entryToken: u.token }),
      { headers: { 'X-User-Id': u.userId, 'Content-Type': 'application/json' } });
    if (c.status === 201) {
      try {
        const bid = JSON.parse(c.body).id;
        http.post(`${GW}/api/booking/${bid}/confirm`, null, { headers: { 'X-User-Id': u.userId } });
      } catch (e) { /* */ }
    }
  }
}
