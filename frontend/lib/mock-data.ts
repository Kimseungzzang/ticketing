export const mockEvent = {
  id: 'EVT2026-001',
  title: 'ECLIPSE',
  subtitle: 'WORLD TOUR 2026',
  artist: 'STELLAR',
  venue: '잠실 올림픽 주경기장',
  date: '2026년 7월 5일 토요일',
  time: '오후 7:00',
  doors: '오후 5:30',
};

export const INITIAL_QUEUE_POSITION = 3421;
export const TOTAL_IN_QUEUE = 47382;
export const SERVICE_FEE = 3000;

export type SeatStatus = 'available' | 'taken';

export interface Seat {
  id: string;
  row: string;
  number: number;
  status: SeatStatus;
}

export interface SeatRow {
  id: string;
  seats: Seat[];
}

export interface SeatSection {
  id: string;
  name: string;
  korName: string;
  price: number;
  color: string;
  rows: SeatRow[];
}

function determineTaken(si: number, ri: number, seatIdx: number): boolean {
  const rates = [55, 40, 30];
  const rate = rates[si] ?? 40;
  const n = (si * 7 + ri * 11 + seatIdx * 13) + seatIdx;
  return (n % 20) / 20 < rate / 100;
}

function buildRow(sectionIdx: number, sectionId: string, rowId: string, count: number, rowIdx: number): SeatRow {
  return {
    id: rowId,
    seats: Array.from({ length: count }, (_, i) => ({
      id: `${sectionId}-${rowId}-${i + 1}`,
      row: rowId,
      number: i + 1,
      status: determineTaken(sectionIdx, rowIdx, i) ? 'taken' : 'available',
    })),
  };
}

export const mockSections: SeatSection[] = [
  {
    id: 'S',
    name: 'S석',
    korName: '스테이지 플로어',
    price: 176000,
    color: '#D4A83A',
    rows: ['A', 'B', 'C'].map((r, ri) => buildRow(0, 'S', r, 20, ri)),
  },
  {
    id: 'R',
    name: 'R석',
    korName: '레귤러',
    price: 132000,
    color: '#7C9EF0',
    rows: ['A', 'B', 'C', 'D', 'E'].map((r, ri) => buildRow(1, 'R', r, 24, ri)),
  },
  {
    id: 'A',
    name: 'A석',
    korName: '어퍼 발코니',
    price: 99000,
    color: '#A47FD4',
    rows: ['A', 'B', 'C', 'D'].map((r, ri) => buildRow(2, 'A', r, 28, ri)),
  },
];

export const mockPaymentMethods = [
  { id: 'card', label: '신용/체크카드', icon: '💳' },
  { id: 'kakao', label: '카카오페이', icon: '💛' },
  { id: 'naver', label: '네이버페이', icon: '🟢' },
  { id: 'toss', label: '토스페이', icon: '💙' },
];
