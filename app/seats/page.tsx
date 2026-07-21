'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent, SERVICE_FEE } from '@/lib/mock-data';
import { authFetch } from '@/lib/api';
import StepIndicator from '@/components/StepIndicator';

const QUEUE_API   = process.env.NEXT_PUBLIC_QUEUE_BASE_API_URL   ?? 'http://localhost:8090';
const BOOKING_API = process.env.NEXT_PUBLIC_BOOKING_BASE_API_URL ?? 'http://localhost:8090';
const MAX_SEATS = 4;

const SECTION_META: Record<string, { korName: string; color: string }> = {
  S: { korName: '스테이지 플로어', color: '#D4A83A' },
  R: { korName: '레귤러',         color: '#7C9EF0' },
  A: { korName: '어퍼 발코니',    color: '#A47FD4' },
};

interface ApiSeat {
  seatId: string;
  row: string;
  number: number;
  status: 'available' | 'taken';
}

interface ApiSection {
  sectionId: string;
  sectionName: string;
  price: number;
  seats: ApiSeat[];
}

interface SeatRow {
  id: string;
  seats: ApiSeat[];
}

interface SeatSection extends ApiSection {
  korName: string;
  color: string;
  rows: SeatRow[];
}

interface SeatInfo {
  id: string;
  row: string;
  number: number;
  price: number;
  sectionName: string;
  sectionColor: string;
}

export default function SeatsPage() {
  const router = useRouter();
  const [sections, setSections] = useState<SeatSection[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [activeSection, setActiveSection] = useState('S');
  const [validating, setValidating] = useState(true);
  const [loading, setLoading] = useState(true);
  const [soldOut, setSoldOut] = useState(false);
  const [proceeding, setProceeding] = useState(false);
  const [proceedError, setProceedError] = useState<string | null>(null);

  useEffect(() => {
    const accessToken = localStorage.getItem('accessToken');
    const entryToken  = localStorage.getItem('entryToken');
    const eventId     = localStorage.getItem('entryEventId');

    if (!accessToken || !entryToken || !eventId) {
      router.replace('/queue');
      return;
    }

    authFetch(`${QUEUE_API}/api/queue/validate`, {
      headers: { 'X-Entry-Token': entryToken },
    })
      .then(res => {
        if (!res.ok) throw new Error('invalid token');
        setValidating(false);
        // availability 검증: Redis remaining 기반 매진 체크
        return authFetch(`${BOOKING_API}/api/seats/${eventId}/availability`);
      })
      .then(res => {
        if (!res.ok) throw new Error('availability fetch failed');
        return res.json() as Promise<{ total: number; available: number }>;
      })
      .then(avail => {
        if (avail.available <= 0) {
          setSoldOut(true);
          setLoading(false);
          return null;
        }
        return authFetch(`${BOOKING_API}/api/seats/${eventId}`);
      })
      .then(res => {
        if (!res) return null;
        if (!res.ok) throw new Error('seat fetch failed');
        return res.json() as Promise<ApiSection[]>;
      })
      .then(data => {
        if (!data) return;
        const enriched: SeatSection[] = data.map(sec => {
          const meta = SECTION_META[sec.sectionId] ?? { korName: sec.sectionName, color: '#888' };
          const rowMap = new Map<string, ApiSeat[]>();
          sec.seats.forEach(seat => {
            if (!rowMap.has(seat.row)) rowMap.set(seat.row, []);
            rowMap.get(seat.row)!.push(seat);
          });
          const rows: SeatRow[] = Array.from(rowMap.entries())
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([rowId, seats]) => ({ id: rowId, seats: seats.sort((a, b) => a.number - b.number) }));
          return { ...sec, ...meta, rows };
        });
        setSections(enriched);
        setLoading(false);
      })
      .catch(() => {
        authFetch(`${QUEUE_API}/api/queue/release?eventId=${eventId}`, { method: 'POST' }).finally(() => {
          localStorage.removeItem('entryToken');
          localStorage.removeItem('entryEventId');
          router.replace('/queue');
        });
      });
  }, [router]);

  if (validating || loading) {
    return (
      <div className="min-h-screen bg-[#04040A] flex items-center justify-center">
        <p className="text-[#F0EBE0] opacity-40 text-sm">
          {validating ? '입장 확인 중...' : '좌석 정보 불러오는 중...'}
        </p>
      </div>
    );
  }

  if (soldOut) {
    return (
      <div className="min-h-screen bg-[#04040A] flex items-center justify-center flex-col gap-4">
        <p className="text-red-400 text-2xl font-bold">매진되었습니다</p>
        <p className="text-[#F0EBE0] opacity-40 text-sm">잔여 좌석이 없습니다.</p>
        <button onClick={() => router.replace('/')}
          className="mt-4 px-6 py-2 rounded-xl text-sm bg-[#1A1A28] text-[#F0EBE0] border border-white/10">
          홈으로
        </button>
      </div>
    );
  }

  const currentSection = sections.find(s => s.sectionId === activeSection) ?? sections[0];

  const selectedWithInfo: SeatInfo[] = sections.flatMap(sec =>
    sec.seats
      .filter(seat => selectedIds.has(seat.seatId))
      .map(seat => ({
        id: seat.seatId,
        row: seat.row,
        number: seat.number,
        price: sec.price,
        sectionName: sec.sectionName,
        sectionColor: sec.color,
      }))
  );

  const subtotal = selectedWithInfo.reduce((s, x) => s + x.price, 0);
  const fees = SERVICE_FEE * selectedWithInfo.length;
  const total = subtotal + fees;

  const toggleSeat = (seatId: string, taken: boolean) => {
    if (taken) return;
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(seatId)) {
        next.delete(seatId);
      } else {
        if (next.size >= MAX_SEATS) return prev;
        next.add(seatId);
      }
      return next;
    });
  };

  // 좌석 선택 → 결제 페이지로 넘어가는 시점에 좌석마다 PENDING 예약을 미리 만들어둔다.
  // 결제(PG 승인)는 이 예약들을 묶어서 payment 페이지에서 한 번에 확정한다.
  const handleProceed = async () => {
    if (selectedWithInfo.length === 0 || proceeding) return;
    setProceeding(true);
    setProceedError(null);

    const entryToken = localStorage.getItem('entryToken');
    const eventId = localStorage.getItem('entryEventId');
    if (!entryToken || !eventId) { router.replace('/queue'); return; }

    const bookingIds: string[] = [];
    try {
      for (const seat of selectedWithInfo) {
        const res = await authFetch(`${BOOKING_API}/api/booking`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ eventId, seatId: seat.id, entryToken }),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({ message: '좌석 예약에 실패했습니다' })) as { message?: string };
          throw new Error(err.message ?? '좌석 예약에 실패했습니다');
        }
        const data = await res.json() as { id: string };
        bookingIds.push(data.id);
      }

      sessionStorage.setItem('selectedSeats', JSON.stringify({ seats: selectedWithInfo, subtotal, fees, total, bookingIds }));
      router.push('/payment');
    } catch (err) {
      for (const bookingId of bookingIds) {
        await authFetch(`${BOOKING_API}/api/booking/${bookingId}`, { method: 'DELETE' }).catch(() => {});
      }
      setProceedError(err instanceof Error ? err.message : '좌석 예약 중 오류가 발생했습니다');
      setProceeding(false);
    }
  };

  if (!currentSection) return null;

  return (
    <div className="min-h-screen bg-[#04040A] flex flex-col">
      <header className="border-b border-white/6 bg-[#07070F] flex-shrink-0">
        <div className="max-w-full px-6 py-4 flex items-center justify-between">
          <div>
            <p className="text-[#D4A83A] text-base tracking-[0.3em] uppercase"
               style={{ fontFamily: 'var(--font-cormorant)' }}>STAGE TICKET</p>
            <p className="text-[#F0EBE0] text-xs mt-0.5 opacity-40">{mockEvent.title} · {mockEvent.date}</p>
          </div>
          <StepIndicator current={2} />
        </div>
      </header>

      <div className="flex-1 flex min-h-0">
        <main className="flex-1 overflow-y-auto p-6 min-w-0">

          <div className="flex gap-2 mb-6 flex-wrap">
            {sections.map(sec => (
              <button
                key={sec.sectionId}
                onClick={() => setActiveSection(sec.sectionId)}
                className="flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-medium transition-all border"
                style={activeSection === sec.sectionId
                  ? { backgroundColor: sec.color, color: '#04040A', borderColor: 'transparent' }
                  : { backgroundColor: '#0E0E1A', color: 'rgba(240,235,224,0.5)', borderColor: 'rgba(255,255,255,0.07)' }}
              >
                <span className="font-bold">{sec.sectionName}</span>
                <span className="text-xs opacity-70">{sec.price.toLocaleString()}원</span>
              </button>
            ))}
          </div>

          <div className="relative mb-6">
            <div className="h-9 rounded-xl flex items-center justify-center"
                 style={{ background: `linear-gradient(90deg, transparent, ${currentSection.color}28 30%, ${currentSection.color}45 50%, ${currentSection.color}28 70%, transparent)` }}>
              <p className="text-xs tracking-[0.5em] uppercase font-semibold"
                 style={{ color: currentSection.color }}>STAGE</p>
            </div>
            <div className="absolute bottom-0 left-1/4 right-1/4 h-px"
                 style={{ background: `linear-gradient(90deg, transparent, ${currentSection.color}70, transparent)` }} />
          </div>

          <div className="overflow-x-auto pb-4">
            <div className="inline-block">
              <p className="text-[#F0EBE0] text-xs tracking-wider uppercase mb-4 opacity-35">
                {currentSection.korName} — {currentSection.sectionName}
              </p>

              {currentSection.rows.map(row => (
                <div key={row.id} className="flex items-center gap-2 mb-1.5">
                  <span className="text-[#F0EBE0] text-[11px] w-4 text-right font-mono opacity-25 flex-shrink-0">
                    {row.id}
                  </span>
                  <div className="flex gap-[3px]">
                    {row.seats.map(seat => {
                      const isSelected = selectedIds.has(seat.seatId);
                      const isTaken = seat.status === 'taken';
                      return (
                        <button
                          key={seat.seatId}
                          onClick={() => toggleSeat(seat.seatId, isTaken)}
                          disabled={isTaken}
                          title={`${currentSection.sectionName} ${row.id}열 ${seat.number}번${isTaken ? ' (판매완료)' : ''}`}
                          className="w-[17px] h-[17px] rounded-[3px] transition-all duration-100 flex-shrink-0"
                          style={{
                            backgroundColor: isTaken
                              ? 'rgba(255,255,255,0.04)'
                              : isSelected
                                ? currentSection.color
                                : '#1E2035',
                            cursor: isTaken ? 'not-allowed' : 'pointer',
                            transform: isSelected ? 'scale(1.15)' : undefined,
                            boxShadow: isSelected
                              ? `0 0 0 1.5px #04040A, 0 0 0 2.5px ${currentSection.color}`
                              : undefined,
                          }}
                        />
                      );
                    })}
                  </div>
                  <span className="text-[#F0EBE0] text-[11px] w-4 font-mono opacity-25 flex-shrink-0">
                    {row.id}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="flex items-center gap-5 mt-4 pt-4 border-t border-white/5">
            <div className="flex items-center gap-2">
              <div className="w-[14px] h-[14px] rounded-[3px] bg-[#1E2035]" />
              <span className="text-[#F0EBE0] text-xs opacity-40">선택가능</span>
            </div>
            <div className="flex items-center gap-2">
              <div className="w-[14px] h-[14px] rounded-[3px]"
                   style={{ backgroundColor: currentSection.color }} />
              <span className="text-[#F0EBE0] text-xs opacity-40">선택됨</span>
            </div>
            <div className="flex items-center gap-2">
              <div className="w-[14px] h-[14px] rounded-[3px]" style={{ backgroundColor: 'rgba(255,255,255,0.04)' }} />
              <span className="text-[#F0EBE0] text-xs opacity-40">판매완료</span>
            </div>
            <div className="ml-auto">
              <span className="text-[#F0EBE0] text-xs opacity-30">최대 {MAX_SEATS}석</span>
            </div>
          </div>
        </main>

        <aside className="w-72 flex-shrink-0 border-l border-white/6 bg-[#07070F] flex flex-col">
          <div className="p-5 flex-1 flex flex-col min-h-0">
            <h3 className="text-[#F0EBE0] font-semibold text-sm mb-4">선택한 좌석</h3>

            <div className="flex-1 overflow-y-auto min-h-0">
              {selectedWithInfo.length === 0 ? (
                <div className="flex flex-col items-center justify-center h-28 text-center">
                  <div className="text-3xl mb-2.5 opacity-20">🎭</div>
                  <p className="text-[#F0EBE0] text-xs leading-relaxed opacity-30">
                    좌석을 선택해 주세요<br />
                    최대 {MAX_SEATS}석까지 선택 가능
                  </p>
                </div>
              ) : (
                <div className="space-y-1.5">
                  {selectedWithInfo.map(seat => (
                    <div key={seat.id}
                         className="flex items-center justify-between py-2.5 px-3 rounded-lg border border-white/5"
                         style={{ backgroundColor: '#0E0E1A' }}>
                      <div className="flex items-center gap-2 min-w-0">
                        <div className="w-2 h-2 rounded-full flex-shrink-0"
                             style={{ backgroundColor: seat.sectionColor }} />
                        <div className="min-w-0">
                          <p className="text-[#F0EBE0] text-xs font-medium truncate">
                            {seat.sectionName} {seat.row}열 {seat.number}번
                          </p>
                          <p className="text-[#F0EBE0] text-[11px] opacity-40">
                            {seat.price.toLocaleString()}원
                          </p>
                        </div>
                      </div>
                      <button
                        onClick={() => toggleSeat(seat.id, false)}
                        className="text-[#F0EBE0] text-xs opacity-20 hover:opacity-70 hover:text-red-400 transition-all ml-2 flex-shrink-0"
                      >
                        ✕
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {selectedWithInfo.length > 0 && (
              <div className="pt-4 border-t border-white/6 mt-4 space-y-2">
                <div className="flex justify-between text-xs text-[#F0EBE0]/50">
                  <span>티켓 금액</span>
                  <span>{subtotal.toLocaleString()}원</span>
                </div>
                <div className="flex justify-between text-xs text-[#F0EBE0]/50">
                  <span>서비스 수수료</span>
                  <span>{fees.toLocaleString()}원</span>
                </div>
                <div className="flex justify-between font-bold text-sm pt-2 border-t border-white/6">
                  <span className="text-[#F0EBE0]">총 결제금액</span>
                  <span style={{ color: '#D4A83A', fontFamily: 'var(--font-cormorant)', fontSize: '1.1rem' }}>
                    {total.toLocaleString()}원
                  </span>
                </div>
              </div>
            )}

            {proceedError && (
              <p className="mt-3 text-xs text-red-400">{proceedError}</p>
            )}

            <button
              onClick={handleProceed}
              disabled={selectedWithInfo.length === 0 || proceeding}
              className="mt-4 w-full py-3.5 rounded-xl font-bold text-sm transition-all duration-200"
              style={{
                backgroundColor: selectedWithInfo.length > 0 && !proceeding ? '#D4A83A' : '#1A1A28',
                color: selectedWithInfo.length > 0 && !proceeding ? '#04040A' : 'rgba(240,235,224,0.25)',
                cursor: selectedWithInfo.length > 0 && !proceeding ? 'pointer' : 'not-allowed',
              }}
            >
              {proceeding ? '좌석 확보 중...' : `결제하기 (${selectedWithInfo.length}석)`}
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
}
