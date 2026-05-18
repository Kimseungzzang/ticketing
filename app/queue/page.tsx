'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent, INITIAL_QUEUE_POSITION, TOTAL_IN_QUEUE } from '@/lib/mock-data';
import StepIndicator from '@/components/StepIndicator';

const WAIT_SECONDS = 10;

export default function QueuePage() {
  const router = useRouter();
  const [secondsLeft, setSecondsLeft] = useState(WAIT_SECONDS);
  const [isReady, setIsReady] = useState(false);

  const position = isReady ? 0 : Math.max(0, Math.round(INITIAL_QUEUE_POSITION * (secondsLeft / WAIT_SECONDS)));
  const progress = ((TOTAL_IN_QUEUE - position) / TOTAL_IN_QUEUE) * 100;

  useEffect(() => {
    const id = setInterval(() => {
      setSecondsLeft(prev => {
        if (prev <= 1) {
          clearInterval(id);
          setIsReady(true);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="min-h-screen bg-[#04040A] flex flex-col">
      {/* Header */}
      <header className="border-b border-white/6 bg-[#07070F]">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <div>
            <p className="text-[#D4A83A] text-base tracking-[0.3em] uppercase"
               style={{ fontFamily: 'var(--font-cormorant)' }}>
              STAGE TICKET
            </p>
            <p className="text-[#F0EBE0] text-xs mt-0.5 opacity-40">
              {mockEvent.title} · {mockEvent.date}
            </p>
          </div>
          <StepIndicator current={1} />
        </div>
      </header>

      {/* Main */}
      <main className="flex-1 flex flex-col items-center justify-center px-6 py-16">
        <div className="text-center w-full max-w-md">

          {/* Status badge */}
          <div className={`inline-flex items-center gap-2 px-4 py-1.5 rounded-full text-xs font-medium mb-12 border transition-all duration-700 ${
            isReady
              ? 'text-emerald-400 border-emerald-500/25'
              : 'text-[#D4A83A] border-[#D4A83A]/25'
          }`}
          style={{ background: isReady ? 'rgba(52,211,153,0.08)' : 'rgba(212,168,58,0.08)' }}>
            <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
              isReady ? 'bg-emerald-400' : 'bg-[#D4A83A] animate-[pulse_1.5s_ease-in-out_infinite]'
            }`} />
            {isReady ? '입장 가능합니다!' : '대기열 처리 중...'}
          </div>

          {/* Position number */}
          <div className="mb-8 animate-slideUp">
            <p className="text-[#F0EBE0] text-xs tracking-[0.4em] uppercase mb-4 opacity-40">
              현재 대기 순서
            </p>
            <p className={`font-light leading-none tabular-nums transition-all duration-700 ${
              isReady ? 'text-emerald-400' : 'text-[#D4A83A] animate-pulseGlow'
            }`}
            style={{ fontFamily: 'var(--font-cormorant)', fontSize: '8rem' }}>
              {isReady ? '입장' : position.toLocaleString()}
            </p>
            {!isReady && (
              <p className="text-[#F0EBE0] text-sm mt-2 opacity-30">
                전체 {TOTAL_IN_QUEUE.toLocaleString()}명 중
              </p>
            )}
          </div>

          {/* Progress bar */}
          {!isReady && (
            <div className="mb-8 animate-slideUp delay-100">
              <div className="h-1 bg-white/6 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all duration-1000 ease-out"
                  style={{ width: `${progress}%`, background: 'linear-gradient(90deg, #D4A83A, #F0C860)' }}
                />
              </div>
              <div className="flex justify-between mt-2 text-xs opacity-30">
                <span className="text-[#F0EBE0]">진행률 {progress.toFixed(1)}%</span>
                <span className="text-[#F0EBE0]">예상 {secondsLeft}초</span>
              </div>
            </div>
          )}

          {/* Info cards */}
          {!isReady && (
            <div className="grid grid-cols-2 gap-3 mb-10 animate-slideUp delay-200">
              <div className="bg-[#0E0E1A] border border-white/6 rounded-2xl p-4 text-left">
                <p className="text-[#F0EBE0] text-xs opacity-40 mb-1.5">전체 대기 인원</p>
                <p className="text-[#F0EBE0] text-xl font-semibold tabular-nums">
                  {TOTAL_IN_QUEUE.toLocaleString()}
                  <span className="text-xs font-normal opacity-50 ml-1">명</span>
                </p>
              </div>
              <div className="bg-[#0E0E1A] border border-white/6 rounded-2xl p-4 text-left">
                <p className="text-[#F0EBE0] text-xs opacity-40 mb-1.5">예상 대기 시간</p>
                <p className="text-[#F0EBE0] text-xl font-semibold tabular-nums">
                  {secondsLeft}
                  <span className="text-xs font-normal opacity-50 ml-1">초</span>
                </p>
              </div>
            </div>
          )}

          {/* CTA */}
          {isReady ? (
            <button
              onClick={() => router.push('/seats')}
              className="w-full max-w-xs mx-auto block py-4 rounded-2xl font-bold text-base tracking-wide bg-[#D4A83A] hover:bg-[#E8BE50] text-[#04040A] transition-all duration-200 animate-scaleIn"
            >
              좌석 선택하기 →
            </button>
          ) : (
            <p className="text-[#F0EBE0] text-xs opacity-25 animate-slideUp delay-300">
              대기가 완료되면 입장 버튼이 활성화됩니다
            </p>
          )}
        </div>
      </main>
    </div>
  );
}
