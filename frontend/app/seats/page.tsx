'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent, mockSections, SERVICE_FEE } from '@/lib/mock-data';
import StepIndicator from '@/components/StepIndicator';

const MAX_SEATS = 4;

export default function SeatsPage() {
  const router = useRouter();
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [activeSection, setActiveSection] = useState('S');

  const currentSection = mockSections.find(s => s.id === activeSection)!;

  const selectedWithInfo = mockSections.flatMap(section =>
    section.rows.flatMap(row =>
      row.seats
        .filter(seat => selectedIds.has(seat.id))
        .map(seat => ({
          ...seat,
          price: section.price,
          sectionName: section.name,
          sectionColor: section.color,
        }))
    )
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

  const handleProceed = () => {
    if (selectedWithInfo.length === 0) return;
    sessionStorage.setItem('selectedSeats', JSON.stringify({ seats: selectedWithInfo, subtotal, fees, total }));
    router.push('/payment');
  };

  return (
    <div className="min-h-screen bg-[#04040A] flex flex-col">
      {/* Header */}
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

      {/* Body */}
      <div className="flex-1 flex min-h-0">
        {/* Seat map */}
        <main className="flex-1 overflow-y-auto p-6 min-w-0">

          {/* Section tabs */}
          <div className="flex gap-2 mb-6 flex-wrap">
            {mockSections.map(sec => (
              <button
                key={sec.id}
                onClick={() => setActiveSection(sec.id)}
                className="flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-medium transition-all border"
                style={activeSection === sec.id
                  ? { backgroundColor: sec.color, color: '#04040A', borderColor: 'transparent' }
                  : { backgroundColor: '#0E0E1A', color: 'rgba(240,235,224,0.5)', borderColor: 'rgba(255,255,255,0.07)' }}
              >
                <span className="font-bold">{sec.name}</span>
                <span className="text-xs opacity-70">{sec.price.toLocaleString()}원</span>
              </button>
            ))}
          </div>

          {/* Stage indicator */}
          <div className="relative mb-6">
            <div className="h-9 rounded-xl flex items-center justify-center"
                 style={{ background: `linear-gradient(90deg, transparent, ${currentSection.color}28 30%, ${currentSection.color}45 50%, ${currentSection.color}28 70%, transparent)` }}>
              <p className="text-xs tracking-[0.5em] uppercase font-semibold"
                 style={{ color: currentSection.color }}>STAGE</p>
            </div>
            <div className="absolute bottom-0 left-1/4 right-1/4 h-px"
                 style={{ background: `linear-gradient(90deg, transparent, ${currentSection.color}70, transparent)` }} />
          </div>

          {/* Seat grid */}
          <div className="overflow-x-auto pb-4">
            <div className="inline-block">
              <p className="text-[#F0EBE0] text-xs tracking-wider uppercase mb-4 opacity-35">
                {currentSection.korName} — {currentSection.name}
              </p>

              {currentSection.rows.map(row => (
                <div key={row.id} className="flex items-center gap-2 mb-1.5">
                  <span className="text-[#F0EBE0] text-[11px] w-4 text-right font-mono opacity-25 flex-shrink-0">
                    {row.id}
                  </span>
                  <div className="flex gap-[3px]">
                    {row.seats.map(seat => {
                      const isSelected = selectedIds.has(seat.id);
                      const isTaken = seat.status === 'taken';
                      return (
                        <button
                          key={seat.id}
                          onClick={() => toggleSeat(seat.id, isTaken)}
                          disabled={isTaken}
                          title={`${currentSection.name} ${row.id}열 ${seat.number}번${isTaken ? ' (판매완료)' : ''}`}
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

          {/* Legend */}
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

        {/* Sidebar */}
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

            <button
              onClick={handleProceed}
              disabled={selectedWithInfo.length === 0}
              className="mt-4 w-full py-3.5 rounded-xl font-bold text-sm transition-all duration-200"
              style={{
                backgroundColor: selectedWithInfo.length > 0 ? '#D4A83A' : '#1A1A28',
                color: selectedWithInfo.length > 0 ? '#04040A' : 'rgba(240,235,224,0.25)',
                cursor: selectedWithInfo.length > 0 ? 'pointer' : 'not-allowed',
              }}
            >
              결제하기 ({selectedWithInfo.length}석)
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
}
