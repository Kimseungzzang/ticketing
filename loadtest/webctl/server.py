#!/usr/bin/env python3
"""부하테스트 컨트롤 웹 서버 — 버튼으로 k6 시나리오 실행 + 실시간 진행 + 리포트 + run 비교.

  의존 0 (Python 표준 라이브러리 + k6/redis-cli 바이너리). 기존 Prometheus(:9090)를 데이터 소스로 씀.
  실행: python3 loadtest/webctl/server.py   →  http://localhost:8090
"""
import json
import os
import re
import subprocess
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

TICKETING = "/Users/hbrc/workspace/ticketing"
PROM = "http://localhost:9090"
PORT = int(os.environ.get("WEBCTL_PORT", "8090"))
HERE = os.path.dirname(os.path.abspath(__file__))

# 실행 가능한 부하 시나리오 (단계별)
SCENARIOS = {
    "queue-100k":        {"file": "scenario-queue-100k.js",       "title": "① 대기열 폭주",   "svc": "queue",         "desc": "enter만 폭격 → burst 흡수 테스트(큐 폭증)"},
    "queue-realistic":   {"file": "scenario-queue-realistic.js",  "title": "⑥ 대기열 순환",   "svc": "queue",         "desc": "enter→입장→예약→release 순환 (대기시간·처리량 SLA)"},
    "segment-B-booking": {"file": "scenario-segment-B-booking.js","title": "② 예약 e2e",      "svc": "booking",       "desc": "create+confirm → 카프카 → ticket-query 수렴"},
    "staged-ramp":       {"file": "scenario-staged-ramp.js",      "title": "③ 병목 사냥",     "svc": "ticket-command","desc": "1000→6000 RPS 램프 (CPU 포화 찾기)"},
    "load-100k":         {"file": "scenario-load-100k.js",        "title": "④ 정량 10만",     "svc": "ticket-command","desc": "정확히 10만 iteration"},
    "gateway-mesh":      {"file": "scenario-gateway-mesh.js",     "title": "⑤ 게이트웨이 mesh","svc": "gateway",       "desc": "전 서비스 관통(서비스맵용)"},
}

state = {"testid": None, "scenario": None, "start": 0.0, "proc": None, "log": None}
history = []   # 완료된 run 요약 리스트


def promq(q):
    try:
        url = f"{PROM}/api/v1/query?query={urllib.parse.quote(q)}"
        d = json.load(urllib.request.urlopen(url, timeout=2))["data"]["result"]
        return float(d[0]["value"][1]) if d else 0.0
    except Exception:
        return 0.0


def redis_zcard(key):
    try:
        out = subprocess.run(["redis-cli", "-p", "6379", "ZCARD", key], capture_output=True, text=True, timeout=2).stdout.strip()
        return int(out or 0)
    except Exception:
        return 0


def loadavg():
    try:
        return float(subprocess.run(["sysctl", "-n", "vm.loadavg"], capture_output=True, text=True, timeout=2).stdout.split()[1])
    except Exception:
        return 0.0


def ncpu():
    try:
        return int(subprocess.run(["sysctl", "-n", "hw.ncpu"], capture_output=True, text=True, timeout=2).stdout.strip())
    except Exception:
        return 10


def reset_for(scenario):
    PG = "/opt/homebrew/opt/postgresql@16/bin/psql"
    def sh(cmd): subprocess.run(cmd, shell=True, capture_output=True, timeout=20)
    if scenario in ("queue-100k", "queue-realistic"):
        sh("redis-cli -p 6379 --scan --pattern 'queue:*' | xargs -r redis-cli -p 6379 DEL")
    elif scenario == "segment-B-booking" or scenario == "gateway-mesh":
        sh(f"{PG} -U hbrc -d booking_db -c \"UPDATE seats SET status='AVAILABLE' WHERE status<>'AVAILABLE'\"")
        sh(f"{PG} -U hbrc -d ticket_read_db -c \"UPDATE seats SET status='AVAILABLE' WHERE status<>'AVAILABLE'; TRUNCATE reservations\"")
        sh("redis-cli -p 6379 --scan --pattern 'booking:lock:*' | xargs -r redis-cli -p 6379 DEL")
        subprocess.run([f"{HERE}/../monitor/seed-tokens.sh", "200"], capture_output=True, timeout=30)
    elif scenario in ("staged-ramp", "load-100k"):
        sh(f"{PG} -U hbrc -d ticket_db -f {TICKETING}/ticket-command-service/mock-data.sql")
        sh(f"{PG} -U hbrc -d ticket_db -c 'TRUNCATE outbox RESTART IDENTITY'")


def start_run(scenario):
    if state["proc"] and state["proc"].poll() is None:
        return {"error": "이미 실행 중인 부하가 있습니다"}
    if scenario not in SCENARIOS:
        return {"error": "unknown scenario"}
    reset_for(scenario)
    testid = f"{scenario}-{int(time.time())}"
    log = f"/tmp/webctl-{testid}.txt"
    env = os.environ.copy()
    env["K6_PROMETHEUS_RW_SERVER_URL"] = f"{PROM}/api/v1/write"
    env["K6_PROMETHEUS_RW_TREND_STATS"] = "avg,p(95),p(99)"
    proc = subprocess.Popen(
        ["k6", "run", "--tag", f"testid={testid}", "-o", "experimental-prometheus-rw",
         f"loadtest/{SCENARIOS[scenario]['file']}"],
        stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env, cwd=TICKETING,
    )
    state.update({"testid": testid, "scenario": scenario, "start": time.time(), "proc": proc, "log": log})
    return {"ok": True, "testid": testid}


def parse_summary(log):
    """k6 로그에서 요약 지표 추출."""
    try:
        txt = open(log).read()
    except Exception:
        return {}
    def g(pat, default=None):
        m = re.search(pat, txt)
        return m.group(1) if m else default
    return {
        "http_reqs": g(r"http_reqs[^\n]*?:\s*([\d.]+)"),
        "rps": g(r"http_reqs[^\n]*?([\d.]+)/s"),
        "iterations": g(r"iterations[^\n]*?:\s*([\d.]+)"),
        "p95": g(r"http_req_duration[^\n]*?p\(95\)=([\d.a-zµ]+)"),
        "p99": g(r"http_req_duration[^\n]*?p\(99\)=([\d.a-zµ]+)"),
        "failed": g(r"http_req_failed[^\n]*?:\s*([\d.]+%)"),
        "checks": g(r"checks_succeeded[^\n]*?:\s*([\d.]+%)"),
    }


def status():
    running = bool(state["proc"] and state["proc"].poll() is None)
    tid = state["testid"]
    live = {}
    if tid:
        s = f'{{testid="{tid}"}}'
        live = {
            "rps": round(promq(f"sum(rate(k6_http_reqs_total{s}[15s]))"), 1),
            "vus": int(promq(f"k6_vus{s}")),
            "p95_ms": round(promq(f"k6_http_req_duration_p95{s}"), 1),
            "failed_pct": round(promq(f"k6_http_req_failed_rate{s}") * 100, 1),
        }
    live["queue_depth"] = redis_zcard("queue:sorted:EVT2026-001")
    live["load"] = loadavg()
    live["ncpu"] = ncpu()
    live["outbox_pending"] = int(promq("ticketing_outbox_pending"))
    live["convergence_lag"] = int(promq("ticketing_convergence_lag"))

    # 방금 끝났으면 history에 요약 append
    if tid and not running and state.get("proc") is not None and not any(h["testid"] == tid for h in history):
        summ = parse_summary(state["log"])
        summ.update({"testid": tid, "scenario": state["scenario"],
                     "title": SCENARIOS[state["scenario"]]["title"],
                     "dur": round(time.time() - state["start"], 1)})
        history.insert(0, summ)
        state["proc"] = None

    return {"running": running, "testid": tid,
            "scenario": state["scenario"],
            "elapsed": round(time.time() - state["start"], 1) if (running and tid) else 0,
            "live": live}


def topology():
    """실시간 노드/엣지 상태 — 파이프라인 흐름 애니메이션용."""
    def rps(svc):
        return round(promq(f'sum(rate(http_server_requests_seconds_count{{svc="{svc}"}}[20s]))'), 1)
    def p95(svc):
        v = promq(f'histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket{{svc="{svc}"}}[20s])))')
        return round(v * 1000, 1) if v else 0.0
    q_depth = redis_zcard("queue:sorted:EVT2026-001")
    lag = int(promq("ticketing_convergence_lag"))
    # 발행/소비 흐름 = booking confirm 요청 rate (actuator). gauge(TAKEN 수)는 좌석 리셋에 취약해 부정확 → 요청수로.
    confirm_rps = round(promq('sum(rate(http_server_requests_seconds_count{svc="booking",uri=~".*confirm.*"}[20s]))'), 1)
    pub = confirm_rps   # booking → kafka (발행)
    con = confirm_rps   # kafka → ticket-query → read_db (비동기지만 결국 같은 처리량)
    cmd_rps, book_rps = rps("ticket-command"), rps("booking")
    nodes = {
        "gateway":        {"rps": rps("gateway"),        "p95": p95("gateway")},
        "queue":          {"rps": rps("queue"),          "p95": p95("queue"), "queue": q_depth},
        "ticket-command": {"rps": cmd_rps,               "p95": p95("ticket-command")},
        "booking":        {"rps": book_rps,              "p95": p95("booking")},
        "kafka":          {"rps": pub},
        "ticket-query":   {"rps": con,                   "p95": p95("ticket-query"), "lag": lag},
        # RDB 노드 (write 유입 = 각 서비스의 쓰기율)
        "ticket_db":      {"rps": cmd_rps},   # ticket-command 쓰기
        "booking_db":     {"rps": pub},       # booking confirm 쓰기
        "read_db":        {"rps": con},       # ticket-query 소비 반영 쓰기
    }
    edges = [
        {"from": "gateway", "to": "queue",          "rps": rps("queue")},
        {"from": "gateway", "to": "ticket-command", "rps": cmd_rps},
        {"from": "gateway", "to": "booking",        "rps": book_rps},
        {"from": "booking", "to": "kafka",          "rps": pub},
        {"from": "kafka",   "to": "ticket-query",   "rps": con, "lag": lag},
        # 각 서비스 → RDB
        {"from": "ticket-command", "to": "ticket_db",  "rps": cmd_rps},
        {"from": "booking",        "to": "booking_db", "rps": pub},
        {"from": "ticket-query",   "to": "read_db",    "rps": con},
    ]
    return {"nodes": nodes, "edges": edges, "lag": lag, "load": loadavg(), "ncpu": ncpu(),
            "running": bool(state["proc"] and state["proc"].poll() is None), "scenario": state["scenario"]}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        p = urllib.parse.urlparse(self.path).path
        if p == "/" or p == "/index.html":
            self._send(200, open(f"{HERE}/index.html", "rb").read(), "text/html; charset=utf-8")
        elif p == "/api/scenarios":
            self._send(200, json.dumps(SCENARIOS, ensure_ascii=False))
        elif p == "/api/status":
            self._send(200, json.dumps(status(), ensure_ascii=False))
        elif p == "/api/topology":
            self._send(200, json.dumps(topology(), ensure_ascii=False))
        elif p == "/api/runs":
            self._send(200, json.dumps(history, ensure_ascii=False))
        else:
            self._send(404, "{}")

    def do_POST(self):
        p = urllib.parse.urlparse(self.path).path
        if p == "/api/run":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            scenario = q.get("scenario", [""])[0]
            self._send(200, json.dumps(start_run(scenario), ensure_ascii=False))
        elif p == "/api/stop":
            if state["proc"] and state["proc"].poll() is None:
                state["proc"].terminate()
            self._send(200, json.dumps({"ok": True}))
        else:
            self._send(404, "{}")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print(f"부하테스트 컨트롤 → http://localhost:{PORT}")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
