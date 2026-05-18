'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent, mockPaymentMethods, SERVICE_FEE } from '@/lib/mock-data';
import StepIndicator from '@/components/StepIndicator';

interface SeatInfo {
  id: string;
  row: string;
  number: number;
  price: number;
  sectionName: string;
  sectionColor: string;
}

interface OrderData {
  seats: SeatInfo[];
  subtotal: number;
  fees: number;
  total: number;
}

const FALLBACK: OrderData = {
  seats: [{ id: 'S-A-5', row: 'A', number: 5, price: 176000, sectionName: 'S석', sectionColor: '#D4A83A' }],
  subtotal: 176000,
  fees: SERVICE_FEE,
  total: 176000 + SERVICE_FEE,
};

export default function PaymentPage() {
  const router = useRouter();
  const [order, setOrder] = useState<OrderData>(FALLBACK);
  const [method, setMethod] = useState('card');
  const [cardNum, setCardNum] = useState('');
  const [cardExp, setCardExp] = useState('');
  const [cardCvv, setCardCvv] = useState('');
  const [cardName, setCardName] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const raw = sessionStorage.getItem('selectedSeats');
    if (raw) setOrder(JSON.parse(raw));
  }, []);

  const fmtCard = (v: string) =>
    v.replace(/\D/g, '').slice(0, 16).replace(/(.{4})/g, '$1 ').trim();

  const fmtExp = (v: string) => {
    const d = v.replace(/\D/g, '').slice(0, 4);
    return d.length > 2 ? `${d.slice(0, 2)} / ${d.slice(2)}` : d;
  };

  const cardReady = method !== 'card' ||
    (cardNum.replace(/\s/g, '').length === 16 &&
     cardExp.replace(/\s\/\s/g, '').length === 4 &&
     cardCvv.length === 3 &&
     cardName.trim().length > 0);

  const handlePay = () => {
    if (!agreed || !cardReady) return;
    setLoading(true);
    const id = 'TKT' + Date.now().toString(36).toUpperCase().slice(-8);
    sessionStorage.setItem('bookingId', id);
    setTimeout(() => router.push('/confirmation'), 1500);
  };

  return (
    <div className="min-h-screen bg-[#04040A] flex flex-col">
      {/* Header */}
      <header className="border-b border-white/6 bg-[#07070F]">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <div>
            <p className="text-[#D4A83A] text-base tracking-[0.3em] uppercase"
               style={{ fontFamily: 'var(--font-cormorant)' }}>STAGE TICKET</p>
            <p className="text-[#F0EBE0] text-xs mt-0.5 opacity-40">{mockEvent.title} · {mockEvent.date}</p>
          </div>
          <StepIndicator current={3} />
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full px-6 py-8">
        <div className="flex gap-8 items-start">

          {/* Left: Payment form */}
          <div className="flex-1 space-y-6 animate-slideUp">
            <h2 className="text-[#F0EBE0] text-xl font-semibold">결제 수단</h2>

            {/* Method grid */}
            <div className="grid grid-cols-2 gap-3">
              {mockPaymentMethods.map(pm => (
                <button
                  key={pm.id}
                  onClick={() => setMethod(pm.id)}
                  className="flex items-center gap-3 px-4 py-3.5 rounded-xl border text-left transition-all"
                  style={method === pm.id
                    ? { borderColor: 'rgba(212,168,58,0.45)', backgroundColor: 'rgba(212,168,58,0.07)' }
                    : { borderColor: 'rgba(255,255,255,0.07)', backgroundColor: '#0E0E1A' }}
                >
                  <span className="text-xl leading-none">{pm.icon}</span>
                  <span className="text-sm font-medium"
                        style={{ color: method === pm.id ? '#D4A83A' : 'rgba(240,235,224,0.65)' }}>
                    {pm.label}
                  </span>
                  {method === pm.id && (
                    <div className="ml-auto w-4 h-4 rounded-full bg-[#D4A83A] flex items-center justify-center flex-shrink-0">
                      <div className="w-1.5 h-1.5 rounded-full bg-[#04040A]" />
                    </div>
                  )}
                </button>
              ))}
            </div>

            {/* Card form */}
            {method === 'card' && (
              <div className="bg-[#0E0E1A] border border-white/7 rounded-2xl p-6 space-y-4 animate-slideIn">
                <h3 className="text-[#F0EBE0] font-medium text-sm opacity-80">카드 정보 입력</h3>

                <div>
                  <label className="block text-[#F0EBE0] text-xs uppercase tracking-wider mb-2 opacity-45">카드 번호</label>
                  <input
                    value={cardNum}
                    onChange={e => setCardNum(fmtCard(e.target.value))}
                    placeholder="0000 0000 0000 0000"
                    className="w-full bg-[#07070F] border border-white/7 text-[#F0EBE0] rounded-lg px-4 py-3 text-sm font-mono focus:outline-none focus:border-[#D4A83A]/45 transition-colors"
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-[#F0EBE0] text-xs uppercase tracking-wider mb-2 opacity-45">유효기간</label>
                    <input
                      value={cardExp}
                      onChange={e => setCardExp(fmtExp(e.target.value))}
                      placeholder="MM / YY"
                      className="w-full bg-[#07070F] border border-white/7 text-[#F0EBE0] rounded-lg px-4 py-3 text-sm font-mono focus:outline-none focus:border-[#D4A83A]/45 transition-colors"
                    />
                  </div>
                  <div>
                    <label className="block text-[#F0EBE0] text-xs uppercase tracking-wider mb-2 opacity-45">CVV</label>
                    <input
                      type="password"
                      value={cardCvv}
                      onChange={e => setCardCvv(e.target.value.replace(/\D/g, '').slice(0, 3))}
                      placeholder="•••"
                      className="w-full bg-[#07070F] border border-white/7 text-[#F0EBE0] rounded-lg px-4 py-3 text-sm font-mono focus:outline-none focus:border-[#D4A83A]/45 transition-colors"
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-[#F0EBE0] text-xs uppercase tracking-wider mb-2 opacity-45">카드 소유자 이름</label>
                  <input
                    value={cardName}
                    onChange={e => setCardName(e.target.value)}
                    placeholder="홍길동"
                    className="w-full bg-[#07070F] border border-white/7 text-[#F0EBE0] rounded-lg px-4 py-3 text-sm focus:outline-none focus:border-[#D4A83A]/45 transition-colors"
                  />
                </div>
              </div>
            )}

            {/* Non-card placeholder */}
            {method !== 'card' && (
              <div className="bg-[#0E0E1A] border border-white/7 rounded-2xl p-6 flex items-center justify-center animate-slideIn">
                <p className="text-[#F0EBE0] text-sm opacity-45 text-center">
                  {mockPaymentMethods.find(m => m.id === method)?.icon}&nbsp;&nbsp;
                  {mockPaymentMethods.find(m => m.id === method)?.label} 앱으로 연결됩니다
                </p>
              </div>
            )}

            {/* Agreement */}
            <label className="flex items-start gap-3 cursor-pointer">
              <div
                onClick={() => setAgreed(a => !a)}
                className="w-5 h-5 rounded flex-shrink-0 border flex items-center justify-center transition-all mt-0.5"
                style={{
                  backgroundColor: agreed ? '#D4A83A' : '#0E0E1A',
                  borderColor: agreed ? '#D4A83A' : 'rgba(255,255,255,0.18)',
                }}
              >
                {agreed && <span className="text-[#04040A] text-xs font-bold leading-none">✓</span>}
              </div>
              <p className="text-[#F0EBE0] text-xs leading-relaxed opacity-45 select-none">
                이용약관 및 개인정보처리방침에 동의합니다.
                공연 티켓의 특성상 구매 확정 후 취소 및 환불이 제한될 수 있습니다.
              </p>
            </label>
          </div>

          {/* Right: Order summary */}
          <div className="w-80 flex-shrink-0 animate-slideUp delay-100">
            <div className="bg-[#0E0E1A] border border-white/7 rounded-2xl p-5 sticky top-6">
              <h3 className="text-[#F0EBE0] font-semibold text-sm mb-4">주문 요약</h3>

              <div className="pb-4 border-b border-white/6 mb-4">
                <p className="text-[#D4A83A] text-xs tracking-wider uppercase font-medium mb-1 opacity-80">
                  {mockEvent.artist}
                </p>
                <p className="text-[#F0EBE0] text-xl font-light"
                   style={{ fontFamily: 'var(--font-cormorant)' }}>
                  {mockEvent.title}
                </p>
                <p className="text-[#F0EBE0] text-xs mt-1 opacity-35">
                  {mockEvent.date} · {mockEvent.venue}
                </p>
              </div>

              <div className="space-y-2 pb-4 border-b border-white/6 mb-4">
                {order.seats.map(seat => (
                  <div key={seat.id} className="flex justify-between text-xs">
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 rounded-full flex-shrink-0"
                           style={{ backgroundColor: seat.sectionColor }} />
                      <span className="text-[#F0EBE0] opacity-65">
                        {seat.sectionName} {seat.row}열 {seat.number}번
                      </span>
                    </div>
                    <span className="text-[#F0EBE0] opacity-65">{seat.price.toLocaleString()}원</span>
                  </div>
                ))}
              </div>

              <div className="space-y-2 text-xs pb-4 border-b border-white/6 mb-4">
                <div className="flex justify-between text-[#F0EBE0]/50">
                  <span>티켓 금액</span><span>{order.subtotal.toLocaleString()}원</span>
                </div>
                <div className="flex justify-between text-[#F0EBE0]/50">
                  <span>서비스 수수료</span><span>{order.fees.toLocaleString()}원</span>
                </div>
              </div>

              <div className="flex justify-between items-baseline mb-5">
                <span className="text-[#F0EBE0] text-sm font-bold">총 결제금액</span>
                <span style={{ color: '#D4A83A', fontFamily: 'var(--font-cormorant)', fontSize: '1.4rem', fontWeight: 400 }}>
                  {order.total.toLocaleString()}원
                </span>
              </div>

              <button
                onClick={handlePay}
                disabled={!agreed || !cardReady || loading}
                className="w-full py-4 rounded-xl font-bold text-sm transition-all duration-200"
                style={{
                  backgroundColor: agreed && cardReady && !loading ? '#D4A83A' : '#1A1A28',
                  color: agreed && cardReady && !loading ? '#04040A' : 'rgba(240,235,224,0.25)',
                  cursor: agreed && cardReady && !loading ? 'pointer' : 'not-allowed',
                }}
              >
                {loading ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                    </svg>
                    결제 처리 중...
                  </span>
                ) : `${order.total.toLocaleString()}원 결제하기`}
              </button>
            </div>
          </div>

        </div>
      </main>
    </div>
  );
}
