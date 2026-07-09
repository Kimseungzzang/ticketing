// 시나리오 5 변형: 대기 순번 기반 동적 polling.
//
// 기본 sustained-load와 같은 전체 유저 여정과 arrival profile을 쓰되,
// WAITING 상태에서 순번이 멀면 polling 간격을 늘리고 입장 임박 구간에서는 짧게 줄인다.
// 목적은 status polling 부하와 VU 점유 시간을 고정 3초 polling과 비교하는 것이다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, EVENT_ID } from './lib/config.js';
import { authHeaders } from './lib/gateway-auth.js';

const PRE_VUS = Number(__ENV.PRE_VUS || 3000);
const MAX_VUS = Number(__ENV.MAX_VUS || 20000);
const MAX_POLLS = Number(__ENV.MAX_POLLS || 300);

const START_RATE = Number(__ENV.START_RATE || 10);
const RATE1 = Number(__ENV.RATE1 || 10);
const RATE2 = Number(__ENV.RATE2 || 20);
const RATE3 = Number(__ENV.RATE3 || 35);
const RATE4 = Number(__ENV.RATE4 || 50);
const DUR1 = __ENV.DUR1 || '15s';
const DUR2 = __ENV.DUR2 || '20s';
const DUR3 = __ENV.DUR3 || '20s';
const DUR4 = __ENV.DUR4 || '20s';

export const options = {
  scenarios: {
    sustained_load: {
      executor: 'ramping-arrival-rate',
      startRate: START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: RATE1, duration: DUR1 },
        { target: RATE2, duration: DUR2 },
        { target: RATE3, duration: DUR3 },
        { target: RATE4, duration: DUR4 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const userId = `lt-dynpoll-${EVENT_ID}-${__VU}-${__ITER}`;
  const headers = authHeaders(userId);

  let res = http.post(`${BASE_URL}/api/queue/enter?eventId=${EVENT_ID}`, null, { headers });
  check(res, { 'enter ok': (r) => r.status === 200 });
  let body = safeJson(res);
  if (!body) return;

  let polls = 0;
  while (body.status !== 'READY' && polls < MAX_POLLS) {
    sleep(nextPollInterval(body));
    res = http.get(`${BASE_URL}/api/queue/status?eventId=${EVENT_ID}`, { headers });
    check(res, { 'status ok': (r) => r.status === 200 });
    body = safeJson(res);
    if (!body) return;
    polls++;
  }

  if (body.status !== 'READY') {
    console.error(`user=${userId} never became READY after ${polls} polls`);
    return;
  }

  res = http.get(`${BASE_URL}/api/queue/validate`, {
    headers: Object.assign({}, headers, { 'X-Entry-Token': body.entryToken }),
  });
  check(res, { 'validate ok': (r) => r.status === 200 });

  sleep(2 + Math.random() * 3);

  res = http.post(`${BASE_URL}/api/queue/release?eventId=${EVENT_ID}`, null, { headers });
  check(res, { 'release ok': (r) => r.status === 200 });
}

function nextPollInterval(body) {
  const position = Number(body && body.position ? body.position : 0);
  if (position > 2000) return 10;
  if (position > 1000) return 5;
  if (position > 300) return 3;
  return 1;
}

function safeJson(res) {
  try {
    return JSON.parse(res.body);
  } catch {
    console.error(`invalid JSON response: status=${res.status} body=${res.body}`);
    return null;
  }
}

export function teardown() {
  console.log(`\n=== scenario 5 dynamic polling done. event=${EVENT_ID} ===`);
}
