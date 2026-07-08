// 구간 A — 대기열(queue) 부하.
//   다수 유저가 동시에 enter → status 폴링. 대기열이 쌓이고, 스케줄러(2초/slot 5)가 빼가는 양상을 본다.
//   dashboard.sh의 "A 대기열" 줄(큐깊이/활성slot)이 실시간으로 출렁이는 걸 같이 관찰.
//
// 실행: k6 run loadtest/scenario-segment-A-queue.js
//   (옆 창에서: loadtest/monitor/dashboard.sh EVT2026-001 1)
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const QUEUE = 'http://localhost:8085';
const EVENT = 'EVT2026-001';

// 구간 태깅용 커스텀 메트릭 — k6 요약에 구간 이름으로 latency가 분리돼 찍힌다.
const enterLatency  = new Trend('A_enter_latency', true);
const statusLatency = new Trend('A_status_latency', true);
const readyCount    = new Counter('A_ready_count');   // 폴링 중 READY로 승격된 횟수

export const options = {
  scenarios: {
    queue_flood: {
      executor: 'ramping-arrival-rate',
      startRate: 50, timeUnit: '1s',
      preAllocatedVUs: 200, maxVUs: 2000,
      stages: [
        { target: 200,  duration: '10s' },  // 입장(slot 5/2초≈2.5/s)보다 훨씬 빠르게 유입 → 큐 적체
        { target: 500,  duration: '15s' },
        { target: 500,  duration: '10s' },
      ],
    },
  },
};

export default function () {
  const userId = `q-${__VU}-${__ITER}`;
  const h = { headers: { 'X-User-Id': userId } };

  const e = http.post(`${QUEUE}/api/queue/enter?eventId=${EVENT}`, null, h);
  enterLatency.add(e.timings.duration);
  check(e, { 'enter 200': r => r.status === 200 });

  // 한 번 status 확인(이 유저가 이미 READY로 빠졌는지)
  const s = http.get(`${QUEUE}/api/queue/status?eventId=${EVENT}`, h);
  statusLatency.add(s.timings.duration);
  try { if (JSON.parse(s.body).entryToken) readyCount.add(1); } catch (_) { /* */ }
}
