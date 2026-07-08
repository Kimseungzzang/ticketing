-- ticket_db seed data
-- 전제: ticket-command-service가 한 번 bootRun 되어 스키마(events, seat_sections, seats, reservations)가 생성된 상태.
-- 실행: psql -h localhost -U postgres -d ticket_db -f mock-data.sql
--
-- 매핑: frontend/lib/mock-data.ts 의 mockEvent + mockSections 와 1:1.
--   - Event 1개 (EVT2026-001)
--   - SeatSection 3개 (S 60석 / R 120석 / A 112석 = 총 292석)

BEGIN;

-- 기존 데이터 초기화 (멱등 재실행 가능하도록)
TRUNCATE TABLE reservations, seats, seat_sections, events RESTART IDENTITY CASCADE;

-- ── Event ──────────────────────────────────────────────────────────────────
INSERT INTO events (id, title, subtitle, artist, venue, starts_at, doors_open_at) VALUES
  ('EVT2026-001', 'ECLIPSE', 'WORLD TOUR 2026', 'STELLAR',
   '잠실 올림픽 주경기장',
   '2026-07-05 19:00:00+09',
   '2026-07-05 17:30:00+09');

-- ── SeatSection ────────────────────────────────────────────────────────────
INSERT INTO seat_sections (id, event_id, name, kor_name, price, color) VALUES
  ('S', 'EVT2026-001', 'S석', '스테이지 플로어', 176000, '#D4A83A'),
  ('R', 'EVT2026-001', 'R석', '레귤러',          132000, '#7C9EF0'),
  ('A', 'EVT2026-001', 'A석', '어퍼 발코니',      99000, '#A47FD4');

-- ── Seat ───────────────────────────────────────────────────────────────────
-- generate_series 로 row × seat 조합 폭발

-- S석: A/B/C × 1..20 = 60석
INSERT INTO seats (id, section_id, row_label, seat_number, status, version)
SELECT 'S-' || r.row_label || '-' || n, 'S', r.row_label, n, 'AVAILABLE', 0
FROM (VALUES ('A'), ('B'), ('C')) AS r(row_label)
CROSS JOIN generate_series(1, 20) AS n;

-- R석: A/B/C/D/E × 1..24 = 120석
INSERT INTO seats (id, section_id, row_label, seat_number, status, version)
SELECT 'R-' || r.row_label || '-' || n, 'R', r.row_label, n, 'AVAILABLE', 0
FROM (VALUES ('A'), ('B'), ('C'), ('D'), ('E')) AS r(row_label)
CROSS JOIN generate_series(1, 24) AS n;

-- A석: A/B/C/D × 1..28 = 112석
INSERT INTO seats (id, section_id, row_label, seat_number, status, version)
SELECT 'A-' || r.row_label || '-' || n, 'A', r.row_label, n, 'AVAILABLE', 0
FROM (VALUES ('A'), ('B'), ('C'), ('D')) AS r(row_label)
CROSS JOIN generate_series(1, 28) AS n;

COMMIT;

-- ── 검증 쿼리 ─────────────────────────────────────────────────────────────
-- SELECT COUNT(*) FROM events;         -- 1
-- SELECT COUNT(*) FROM seat_sections;  -- 3
-- SELECT section_id, COUNT(*) FROM seats GROUP BY section_id ORDER BY section_id;
-- 결과 기대값:  A=112, R=120, S=60
