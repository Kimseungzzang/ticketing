// 대기열 대규모 — enter 도착률을 점차 올려 대기열을 100,000명 규모까지 쌓는다.
//   입장(admit)은 slot 5 / 2초 ≈ 초당 2.5명뿐이라, 유입이 그보다 빠르면 큐가 계속 폭증한다.
//   Grafana ticketing-segments의 "큐 깊이" 패널이 점점 가파르게 우상향한다.
// 실행: k6 run loadtest/scenario-queue-100k.js
import http from 'k6/http';

const QUEUE = 'http://localhost:8085';
const EVENT = 'EVT2026-001';

export const options = {
  scenarios: {
    flood: {
      executor: 'ramping-arrival-rate',
      startRate: 500, timeUnit: '1s',
      preAllocatedVUs: 800, maxVUs: 10000,
      stages: [
        { target: 2000, duration: '10s' },  // 점차 가속
        { target: 4000, duration: '15s' },
        { target: 6000, duration: '15s' },  // 누적 ~13만 유입 → 큐 10만+ 도달
      ],
    },
  },
};

export default function () {
  const userId = `q-${__VU}-${__ITER}`;
  http.post(`${QUEUE}/api/queue/enter?eventId=${EVENT}`, null, { headers: { 'X-User-Id': userId } });
}
