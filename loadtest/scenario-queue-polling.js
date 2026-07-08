// 폴링 부하 증명용 — "내 순번 몇 번?"(status=ZRANK) 요청을 폭격한다.
//   실제 티켓팅 트래픽의 대부분이 이 폴링이라는 가설을 검증:
//   status RPS를 수천까지 올렸을 때 Redis CPU vs queue-service CPU 중 뭐가 먼저 오르나?
//   (Redis ZRANK는 마이크로초 연산 → Redis는 놀고, HTTP 처리하는 queue-service가 부하 받을 것)
// 실행: k6 run loadtest/scenario-queue-polling.js
//   (큐에 유저가 차 있을수록 ZRANK가 실제 순위 계산을 하므로, 사전에 enter로 큐를 채워두면 더 현실적)
import http from 'k6/http';

const QUEUE = __ENV.QUEUE || 'http://localhost:8085';   // gateway 경유: QUEUE=http://localhost:8080
const EVENT = 'EVT2026-001';

export const options = {
  scenarios: {
    poll: {
      executor: 'ramping-arrival-rate',
      startRate: 500, timeUnit: '1s',
      preAllocatedVUs: 600, maxVUs: 5000,
      stages: [
        { target: 2000, duration: '10s' },  // 순번 확인 폭격
        { target: 4000, duration: '15s' },
        { target: 4000, duration: '10s' },
      ],
    },
  },
};

export default function () {
  // 순번 확인 = queue-service가 Redis ZRANK 1번 치고 JSON 응답
  http.get(`${QUEUE}/api/queue/status?eventId=${EVENT}`, { headers: { 'X-User-Id': `poll-${__VU}-${__ITER}` } });
}
