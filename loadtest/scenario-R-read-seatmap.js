// 시나리오 R — 읽기 부하 (GET 좌석맵)
//
// 목적
//   ticket-query 의 읽기 처리량/지연 측정. 쓰기 부하(scenario-W)와 동시에 돌려
//   "분리(ticket_read_db) vs 비분리(ticket_db 직접)" 읽기 성능을 비교하는 데 쓴다.
//
// 파라미터 (env)
//   VUS       동시 사용자 수      (기본 20)
//   DURATION  지속 시간          (기본 20s)
//   READ_URL  대상 URL           (기본 query 좌석맵)
//
// 실행
//   VUS=20 DURATION=20s k6 run loadtest/scenario-R-read-seatmap.js

import http from 'k6/http';
import { check } from 'k6';

const READ_URL = __ENV.READ_URL || 'http://localhost:8083/events/EVT2026-001/seats';

export const options = {
  scenarios: {
    read: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.VUS || '20'),
      duration: __ENV.DURATION || '20s',
    },
  },
};

export default function () {
  const res = http.get(READ_URL, { tags: { endpoint: 'GET /seats' } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
