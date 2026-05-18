'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent, SERVICE_FEE } from '@/lib/mock-data';
import StepIndicator from '@/components/StepIndicator';

interface SeatInfo {
  id: string;
  row: string;
  number: number;
  price: number;
  sectionName: string;
  sectionColor: string;
}

export default function ConfirmationPage() {
  const router = useRouter();
  const [bookingId, setBookingId] = useState('');
  const [seats, setSeats] = useState<SeatInfo[]>([]);
  const [total, setTotal] = useState(0);
  const [show, setShow] = useState(false);

  useEffect(() => {
    const id = sessionStorage.getItem('bookingId') ||
      'TKT' + Date.now().toString(36).toUpperCase().slice(-8);
    setBookingId(id);

    const raw = sessionStorage.getItem('selectedSeats');
    if (raw) {
      const d = JSON.parse(raw);
      setSeats(d.seats);
      setTotal(d.total);
    } else {
      setSeats([{ id: 'S-A-5', row: 'A', number: 5, price: 176000, sectionName: 'S석', sectionColor: '#D4A83A' }]);
      setTotal(176000 + SERVICE_FEE);
    }

    const t = setTimeout(() => setShow(true), 80);
    return () => clearTimeout(t);
  }, []);

  const transBase = 'transition-all duration-700';

  return (
    <div className="min-h-screen bg-[#04040A] flex flex-col">
      {/* Header */}
      <header className="border-b border-white/6 bg-[#07070F]">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <p className="text-[#D4A83A] text-base tracking-[0.3em] uppercase"
             style={{ fontFamily: 'var(--font-cormorant)' }}>
            STAGE TICKET
          </p>
          <StepIndicator current={4} />
        </div>
      </header>

      <main className="flex-1 flex flex-col items-center justify-center px-6 py-10">

        {/* Success icon */}
        <div className={`text-center mb-10 ${transBase} ${show ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-6'}`}>
          <div className="relative inline-flex items-center justify-center mb-5">
            <div className="w-20 h-20 rounded-full border flex items-center justify-center"
                 style={{ backgroundColor: 'rgba(52,211,153,0.1)', borderColor: 'rgba(52,211,153,0.3)' }}>
              <svg className="w-9 h-9 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <div className="absolute inset-0 rounded-full blur-2xl -z-10 scale-150"
                 style={{ backgroundColor: 'rgba(52,211,153,0.08)' }} />
          </div>
          <h1 className="text-[#F0EBE0] text-2xl font-bold mb-1">예매가 완료되었습니다!</h1>
          <p className="text-[#F0EBE0] text-sm opacity-40">예매 확인 이메일이 발송되었습니다</p>
        </div>

        {/* Ticket */}
        <div className={`w-full max-w-md ${transBase} delay-200 ${show ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'}`}>
          <div className="relative rounded-2xl overflow-hidden border"
               style={{ backgroundColor: '#0D0D1A', borderColor: 'rgba(212,168,58,0.22)' }}>

            {/* Top section */}
            <div className="p-7 relative">
              <div className="absolute top-0 inset-x-0 h-36 pointer-events-none"
                   style={{ background: 'radial-gradient(ellipse at 50% -10%, rgba(212,168,58,0.13) 0%, transparent 68%)' }} />

              <div className="relative flex justify-between items-start gap-4">
                <div className="flex-1 min-w-0">
                  <p className="text-[#D4A83A] text-[10px] tracking-[0.45em] uppercase font-medium mb-3 opacity-85">
                    STAGE TICKET
                  </p>
                  <h2 className="text-[#F0EBE0] font-light leading-none mb-1 truncate"
                      style={{ fontFamily: 'var(--font-cormorant)', fontSize: '2.6rem' }}>
                    {mockEvent.title}
                  </h2>
                  <p className="text-[#D4A83A] text-sm tracking-[0.2em] uppercase opacity-80">
                    {mockEvent.artist}
                  </p>
                </div>

                {/* Decorative QR */}
                <div className="w-16 h-16 flex-shrink-0 opacity-40 grid grid-cols-5 grid-rows-5 gap-[2px]">
                  {Array.from({ length: 25 }, (_, i) => (
                    <div key={i} className="rounded-[1px]"
                         style={{ backgroundColor: (i * 7 + 3) % 4 > 1 ? '#D4A83A' : 'transparent' }} />
                  ))}
                </div>
              </div>

              {/* Details grid */}
              <div className="grid grid-cols-3 gap-3 mt-6">
                {[
                  { label: '날짜', value: '2026.07.05 토' },
                  { label: '시간', value: mockEvent.time },
                  { label: '장소', value: '잠실 올림픽경기장' },
                ].map(({ label, value }) => (
                  <div key={label}>
                    <p className="text-[#F0EBE0] text-[9px] uppercase tracking-wider opacity-30 mb-1">{label}</p>
                    <p className="text-[#F0EBE0] text-xs font-medium leading-snug opacity-90">{value}</p>
                  </div>
                ))}
              </div>

              {/* Seat tags */}
              <div className="mt-5">
                <p className="text-[#F0EBE0] text-[9px] uppercase tracking-wider opacity-30 mb-2">좌석</p>
                <div className="flex flex-wrap gap-1.5">
                  {seats.map(seat => (
                    <span key={seat.id}
                          className="px-2.5 py-1 rounded-md text-xs font-medium"
                          style={{
                            backgroundColor: seat.sectionColor + '1A',
                            color: seat.sectionColor,
                            border: `1px solid ${seat.sectionColor}40`,
                          }}>
                      {seat.sectionName} {seat.row}열 {seat.number}번
                    </span>
                  ))}
                </div>
              </div>
            </div>

            {/* Perforated divider */}
            <div className="relative h-0 mx-5">
              <div className="border-t border-dashed" style={{ borderColor: 'rgba(212,168,58,0.18)' }} />
              <div className="absolute top-1/2 -translate-y-1/2 -left-9 w-5 h-5 rounded-full bg-[#04040A]" />
              <div className="absolute top-1/2 -translate-y-1/2 -right-9 w-5 h-5 rounded-full bg-[#04040A]" />
            </div>

            {/* Stub */}
            <div className="px-7 py-4" style={{ backgroundColor: 'rgba(212,168,58,0.04)' }}>
              <div className="flex justify-between items-center">
                <div>
                  <p className="text-[#F0EBE0] text-[9px] uppercase tracking-wider opacity-30">예매번호</p>
                  <p className="text-[#D4A83A] font-mono text-sm font-bold mt-0.5 tracking-wider">
                    {bookingId}
                  </p>
                </div>
                <p className="text-[#F0EBE0] text-[10px] opacity-25 text-right leading-relaxed">
                  입장 시 QR코드를<br />제시해 주세요
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Paid amount */}
        <div className={`mt-5 text-center ${transBase} delay-300 ${show ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'}`}>
          <p className="text-[#F0EBE0] text-xs opacity-35 mb-1">결제 금액</p>
          <p className="text-[#D4A83A] font-light"
             style={{ fontFamily: 'var(--font-cormorant)', fontSize: '1.8rem' }}>
            {total.toLocaleString()}원
          </p>
        </div>

        {/* Actions */}
        <div className={`flex gap-3 mt-7 ${transBase} delay-400 ${show ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'}`}>
          <button
            onClick={() => router.push('/')}
            className="px-6 py-3 rounded-xl text-sm font-medium transition-all border"
            style={{ backgroundColor: '#0E0E1A', color: 'rgba(240,235,224,0.55)', borderColor: 'rgba(255,255,255,0.07)' }}
            onMouseEnter={e => { (e.target as HTMLButtonElement).style.borderColor = 'rgba(255,255,255,0.18)'; (e.target as HTMLButtonElement).style.color = '#F0EBE0'; }}
            onMouseLeave={e => { (e.target as HTMLButtonElement).style.borderColor = 'rgba(255,255,255,0.07)'; (e.target as HTMLButtonElement).style.color = 'rgba(240,235,224,0.55)'; }}
          >
            홈으로
          </button>
          <button
            className="px-6 py-3 rounded-xl text-sm font-bold transition-all bg-[#D4A83A] hover:bg-[#E8BE50] text-[#04040A]"
          >
            티켓 확인하기
          </button>
        </div>
      </main>
    </div>
  );
}
