'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent } from '@/lib/mock-data';
import { authFetch } from '@/lib/api';

const BOOKING_API = process.env.NEXT_PUBLIC_BOOKING_BASE_API_URL ?? 'http://localhost:8090';

interface MyBooking {
  id: string;
  eventId: string;
  seatId: string;
  sectionName: string;
  row: string;
  number: number;
  price: number;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED';
  createdAt: string;
}

const STATUS_LABEL: Record<MyBooking['status'], string> = {
  PENDING: '결제 대기중',
  CONFIRMED: '예매 완료',
  CANCELLED: '취소됨',
};

const STATUS_COLOR: Record<MyBooking['status'], string> = {
  PENDING: '#D4A83A',
  CONFIRMED: '#34D399',
  CANCELLED: 'rgba(240,235,224,0.35)',
};

export default function ReservationsPage() {
  const router = useRouter();
  const [bookings, setBookings] = useState<MyBooking[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const accessToken = localStorage.getItem('accessToken');
    if (!accessToken) { router.replace('/'); return; }

    authFetch(`${BOOKING_API}/api/booking/my`)
      .then(res => {
        if (!res.ok) throw new Error('예약 목록을 불러오지 못했습니다');
        return res.json() as Promise<MyBooking[]>;
      })
      .then(setBookings)
      .catch(e => setError(e instanceof Error ? e.message : '오류가 발생했습니다'))
      .finally(() => setLoading(false));
  }, [router]);

  return (
    <div className="min-h-screen bg-[#04040A] flex flex-col">
      <header className="border-b border-white/6 bg-[#07070F]">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <p className="text-[#D4A83A] text-base tracking-[0.3em] uppercase"
             style={{ fontFamily: 'var(--font-cormorant)' }}>
            STAGE TICKET
          </p>
          <button
            onClick={() => router.push('/')}
            className="text-xs text-[#F0EBE0] opacity-40 hover:opacity-70 transition-opacity"
          >
            홈으로
          </button>
        </div>
      </header>

      <main className="flex-1 max-w-2xl w-full mx-auto px-6 py-10">
        <div className="flex items-start justify-between gap-4 mb-8">
          <div>
            <h1 className="text-[#F0EBE0] text-xl font-bold mb-1">내 예약 목록</h1>
            <p className="text-[#F0EBE0] text-sm opacity-40">지금까지 예약한 좌석을 확인하세요</p>
          </div>
          <button
            onClick={() => router.push('/queue')}
            className="px-5 py-3 rounded-xl text-sm font-bold whitespace-nowrap transition-all bg-[#D4A83A] hover:bg-[#E8BE50] text-[#04040A]"
          >
            대기열 입장하기
          </button>
        </div>

        {loading && (
          <p className="text-[#F0EBE0] opacity-40 text-sm">불러오는 중...</p>
        )}

        {!loading && error && (
          <p className="text-red-400 text-sm">{error}</p>
        )}

        {!loading && !error && bookings.length === 0 && (
          <div className="text-center py-16">
            <p className="text-[#F0EBE0] opacity-30 text-sm">아직 예약한 좌석이 없습니다</p>
          </div>
        )}

        <div className="space-y-3">
          {bookings.map(b => (
            <div key={b.id}
                 className="rounded-xl border p-5 flex items-center justify-between gap-4"
                 style={{ backgroundColor: '#0D0D1A', borderColor: 'rgba(212,168,58,0.15)' }}>
              <div className="min-w-0">
                <p className="text-[#F0EBE0] font-medium mb-1 truncate"
                   style={{ fontFamily: 'var(--font-cormorant)', fontSize: '1.3rem' }}>
                  {mockEvent.title}
                </p>
                <p className="text-[#F0EBE0] text-sm opacity-60">
                  {b.sectionName} {b.row}열 {b.number}번 · {b.price.toLocaleString()}원
                </p>
                <p className="text-[#F0EBE0] text-xs opacity-30 mt-1">
                  예약번호 {b.id.slice(0, 8)} · {new Date(b.createdAt).toLocaleString('ko-KR')}
                </p>
              </div>
              <span className="px-3 py-1.5 rounded-full text-xs font-medium whitespace-nowrap flex-shrink-0"
                    style={{
                      color: STATUS_COLOR[b.status],
                      backgroundColor: STATUS_COLOR[b.status] + '1A',
                      border: `1px solid ${STATUS_COLOR[b.status]}40`,
                    }}>
                {STATUS_LABEL[b.status]}
              </span>
            </div>
          ))}
        </div>
      </main>
    </div>
  );
}
