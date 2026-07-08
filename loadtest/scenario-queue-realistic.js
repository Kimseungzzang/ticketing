// 현실적 대기열 — enter → 입장 대기 → (예약하는 척) → release(나감) → 다음 사람 입장, 의 전체 순환.
//   queue-100k(enter만 폭격)와 달리 소비(admit+release)가 있어 "진짜 대기열 처리"를 측정한다.
//
//   측정: · queue_wait_seconds = 줄 서서 입장까지 대기시간(핵심 SLA)
//         · admitted           = 입장 처리 수(처리량 = admitted/s)
//         · gave_up            = 대기 포기(타임아웃)
//   큐가 무한 적체하지 않고, 유입 vs 처리(slot·release)에 따라 평형/증가하는 게 보인다.
//
// 실행: k6 run loadtest/scenario-queue-realistic.js
//   (slot 조절: queue를 QUEUE_MAX_ACTIVE_CAP=40 등으로 재기동)
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const QUEUE = 'http://localhost:8085';
const EVENT = 'EVT2026-001';

const waitSec  = new Trend('queue_wait_seconds', true);  // enter → 입장까지 대기
const admitted = new Counter('admitted');                 // 입장 성공(처리량)
const gaveUp   = new Counter('gave_up');                  // 대기 포기(타임아웃)

export const options = {
  scenarios: {
    realistic: {
      executor: 'constant-arrival-rate',
      rate: __ENV.RATE ? parseInt(__ENV.RATE) : 20,   // 초당 유입 인원 (기본 20)
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 600, maxVUs: 4000,
    },
  },
};

export default function () {
  // 실행 간/내부 완전 유니크 — 이전 실행의 잔여 토큰을 주워 대기시간이 가짜로 짧아지는 것 방지
  const userId = `u-${Date.now()}-${__VU}-${__ITER}`;
  const h = { headers: { 'X-User-Id': userId } };
  const t0 = Date.now();

  // 1) 대기열 진입
  http.post(`${QUEUE}/api/queue/enter?eventId=${EVENT}`, null, h);

  // 2) 입장 토큰 받을 때까지 status 폴링 (최대 ~40초)
  let token = null;
  for (let i = 0; i < 80; i++) {
    const s = http.get(`${QUEUE}/api/queue/status?eventId=${EVENT}`, h);
    try { token = JSON.parse(s.body).entryToken; } catch (e) { /* */ }
    if (token) break;
    sleep(0.5);
  }
  if (!token) { gaveUp.add(1); return; }         // 오래 기다려도 입장 못 함
  waitSec.add((Date.now() - t0) / 1000);
  admitted.add(1);

  // 3) 입장 성공 → 좌석 고르고 예약하는 시간 (1~2초 체류)
  sleep(1 + Math.random());

  // 4) 나감(release) → slot 비움 → 대기열 다음 사람이 입장 가능
  http.post(`${QUEUE}/api/queue/release?eventId=${EVENT}`, null, h);
}
