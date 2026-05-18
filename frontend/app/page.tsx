'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent } from '@/lib/mock-data';

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setTimeout(() => router.push('/queue'), 1000);
  };

  return (
    <div className="min-h-screen bg-[#04040A] flex">
      {/* Left: Event poster */}
      <div className="hidden lg:flex flex-1 relative overflow-hidden flex-col justify-end p-16">
        <div className="absolute inset-0"
             style={{ background: 'linear-gradient(145deg, #0D0420 0%, #060D30 45%, #020A1A 100%)' }} />
        <div className="absolute inset-0"
             style={{ backgroundImage: 'radial-gradient(ellipse 60% 55% at 30% 65%, rgba(212,168,58,0.2) 0%, transparent 68%)' }} />
        {[...Array(6)].map((_, i) => (
          <div key={i} className="absolute bg-[#D4A83A]"
               style={{ width: '1px', height: '200%', top: '-50%', left: `${10 + i * 15}%`,
                        transform: 'rotate(20deg)', opacity: 0.055 - i * 0.007 }} />
        ))}

        <div className="relative z-10 animate-slideUp">
          <p className="text-[#D4A83A] text-xs tracking-[0.5em] uppercase mb-5 font-medium opacity-80">
            {mockEvent.date} &nbsp;·&nbsp; {mockEvent.time}
          </p>
          <h1 className="text-[#F0EBE0] font-light leading-none mb-2 animate-pulseGlow"
              style={{ fontFamily: 'var(--font-cormorant)', fontSize: 'clamp(3.5rem, 7vw, 5.5rem)' }}>
            {mockEvent.title}
          </h1>
          <p className="text-[#D4A83A] text-xl tracking-[0.18em] uppercase font-light"
             style={{ fontFamily: 'var(--font-cormorant)' }}>
            {mockEvent.subtitle}
          </p>
          <p className="text-[#F0EBE0] text-sm tracking-[0.35em] uppercase mt-8 opacity-35">
            {mockEvent.artist}
          </p>
          <div className="flex items-center gap-3 mt-3">
            <div className="w-8 h-px bg-[#D4A83A] opacity-40" />
            <p className="text-[#F0EBE0] text-sm opacity-35">📍 {mockEvent.venue}</p>
          </div>
        </div>
      </div>

      {/* Right: Login form */}
      <div className="flex flex-1 lg:max-w-[460px] items-center justify-center p-8"
           style={{ background: 'linear-gradient(180deg, #04040A 0%, #07070F 100%)' }}>
        <div className="w-full max-w-sm animate-fadeIn">
          <div className="mb-10">
            <p className="text-[#D4A83A] text-lg tracking-[0.35em] uppercase"
               style={{ fontFamily: 'var(--font-cormorant)' }}>
              STAGE TICKET
            </p>
          </div>

          <h2 className="text-[#F0EBE0] text-2xl font-semibold mb-1">로그인</h2>
          <p className="text-[#F0EBE0] text-sm mb-8 opacity-40">공연 예매를 위해 로그인해 주세요</p>

          <form onSubmit={handleLogin} className="space-y-4">
            <div>
              <label className="block text-[#F0EBE0] text-xs font-medium mb-2 uppercase tracking-wider opacity-55">
                이메일
              </label>
              <input
                type="email"
                value={email}
                onChange={e => setEmail(e.target.value)}
                placeholder="example@email.com"
                className="w-full bg-[#0E0E1A] border border-white/8 text-[#F0EBE0] rounded-xl px-4 py-3.5 text-sm focus:outline-none focus:border-[#D4A83A]/50 transition-colors"
              />
            </div>
            <div>
              <label className="block text-[#F0EBE0] text-xs font-medium mb-2 uppercase tracking-wider opacity-55">
                비밀번호
              </label>
              <input
                type="password"
                value={password}
                onChange={e => setPassword(e.target.value)}
                placeholder="••••••••"
                className="w-full bg-[#0E0E1A] border border-white/8 text-[#F0EBE0] rounded-xl px-4 py-3.5 text-sm focus:outline-none focus:border-[#D4A83A]/50 transition-colors"
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full py-3.5 rounded-xl font-semibold text-sm tracking-wide transition-all duration-200 mt-2 bg-[#D4A83A] hover:bg-[#E8BE50] text-[#04040A] disabled:opacity-60"
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  로그인 중...
                </span>
              ) : '로그인 · 예매 시작'}
            </button>
          </form>

          <div className="mt-6 flex items-center justify-center gap-4">
            <button className="text-xs text-[#F0EBE0]/30 hover:text-[#D4A83A] transition-colors">회원가입</button>
            <span className="text-[#F0EBE0]/15 text-xs">·</span>
            <button className="text-xs text-[#F0EBE0]/30 hover:text-[#D4A83A] transition-colors">비밀번호 찾기</button>
          </div>

          <div className="mt-8 p-3 rounded-lg border" style={{ background: 'rgba(212,168,58,0.07)', borderColor: 'rgba(212,168,58,0.18)' }}>
            <p className="text-[#D4A83A] text-xs text-center opacity-70">
              데모: 아무 값이나 입력 후 로그인
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
