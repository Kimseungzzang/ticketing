'use client';

import { useState, useEffect, useRef, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { mockEvent } from '@/lib/mock-data';
import StepIndicator from '@/components/StepIndicator';

const QUEUE_API = process.env.NEXT_PUBLIC_QUEUE_BASE_API_URL ?? 'http://localhost:8082';
const EVENT_ID = 'EVT2026-001';
const POLL_INTERVAL_MS = 3000;

type QueueStatus = {
  status: 'WAITING' | 'READY' | 'NOT_IN_QUEUE';
  position: number | null;
  total: number;
  entryToken: string | null;
};

export default function QueuePage() {
  const router = useRouter();
  const [queueStatus, setQueueStatus] = useState<QueueStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const getToken = () =>
    typeof window !== 'undefined' ? localStorage.getItem('accessToken') : null;

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const fetchStatus = useCallback(async () => {
    const token = getToken();
    if (!token) { router.push('/'); return; }

    try {
      const res = await fetch(`${QUEUE_API}/api/queue/status?eventId=${EVENT_ID}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!res.ok) throw new Error('상태 조회 실패');

      const data = await res.json() as QueueStatus;
      setQueueStatus(data);

      if (data.status === 'READY' && data.entryToken) {
        stopPolling();
        localStorage.setItem('entryToken', data.entryToken);
        localStorage.setItem('entryEventId', EVENT_ID);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '오류가 발생했습니다');
    }
  }, [router]);

  useEffect(() => {
    const token = getToken();
    if (!token) { router.push('/'); return; }

    // 이미 입장 토큰이 있으면 → /seats 로 다시 보내기
    const existingEntryToken = localStorage.getItem('entryToken');
    if (existingEntryToken) {
      router.replace('/seats');
      return;
    }

    // 대기열 진입
    fetch(`${QUEUE_API}/api/queue/enter`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ eventId: EVENT_ID }),
    })
      .then(res => {
        if (!res.ok) throw new Error('대기열 진입 실패');
        return res.json();
      })
      .then((data: QueueStatus) => {
        setQueueStatus(data);
        if (data.status === 'READY' && data.entryToken) {
          localStorage.setItem('entryToken', data.entryToken);
          localStorage.setItem('entryEventId', EVENT_ID);
        } else {
          // WAITING → 폴링 시작
          pollRef.current = setInterval(fetchStatus, POLL_INTERVAL_MS);
        }
      })
      .catch(e => setError(e instanceof Error ? e.message : '대기열 진입에 실패했습니다'));

    return stopPolling;
  }, [fetchStatus, router]);

  const isReady   = queueStatus?.status === 'READY';
  const position  = queueStatus?.position ?? 0;
  const total     = queueStatus?.total ?? 0;
  const progress  = total > 0 ? ((total - position) / total) * 100 : 0;

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

          {/* 에러 */}
          {error && (
            <div className="mb-8 rounded-lg border px-4 py-3 text-sm"
                 style={{ background: 'rgba(160,32,32,0.14)', borderColor: 'rgba(218,82,82,0.35)', color: '#F6C4C4' }}>
              {error}
            </div>
          )}

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

          {/* 순서 숫자 */}
          <div className="mb-8 animate-slideUp">
            <p className="text-[#F0EBE0] text-xs tracking-[0.4em] uppercase mb-4 opacity-40">
              현재 대기 순서
            </p>
            <p className={`font-light leading-none tabular-nums transition-all duration-700 ${
              isReady ? 'text-emerald-400' : 'text-[#D4A83A] animate-pulseGlow'
            }`}
            style={{ fontFamily: 'var(--font-cormorant)', fontSize: '8rem' }}>
              {isReady ? '입장' : (position > 0 ? position.toLocaleString() : '···')}
            </p>
            {!isReady && total > 0 && (
              <p className="text-[#F0EBE0] text-sm mt-2 opacity-30">
                전체 {total.toLocaleString()}명 중
              </p>
            )}
          </div>

          {/* Progress bar */}
          {!isReady && total > 0 && (
            <div className="mb-8 animate-slideUp delay-100">
              <div className="h-1 bg-white/6 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all duration-1000 ease-out"
                  style={{ width: `${progress}%`, background: 'linear-gradient(90deg, #D4A83A, #F0C860)' }}
                />
              </div>
              <div className="flex justify-between mt-2 text-xs opacity-30">
                <span className="text-[#F0EBE0]">진행률 {progress.toFixed(1)}%</span>
                <span className="text-[#F0EBE0]">3초마다 갱신</span>
              </div>
            </div>
          )}

          {/* Info cards */}
          {!isReady && total > 0 && (
            <div className="grid grid-cols-2 gap-3 mb-10 animate-slideUp delay-200">
              <div className="bg-[#0E0E1A] border border-white/6 rounded-2xl p-4 text-left">
                <p className="text-[#F0EBE0] text-xs opacity-40 mb-1.5">전체 대기 인원</p>
                <p className="text-[#F0EBE0] text-xl font-semibold tabular-nums">
                  {total.toLocaleString()}
                  <span className="text-xs font-normal opacity-50 ml-1">명</span>
                </p>
              </div>
              <div className="bg-[#0E0E1A] border border-white/6 rounded-2xl p-4 text-left">
                <p className="text-[#F0EBE0] text-xs opacity-40 mb-1.5">내 순서</p>
                <p className="text-[#F0EBE0] text-xl font-semibold tabular-nums">
                  {position.toLocaleString()}
                  <span className="text-xs font-normal opacity-50 ml-1">번</span>
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
