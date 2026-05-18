const STEPS = ['로그인', '대기열', '좌석 선택', '결제', '예매 완료'];

export default function StepIndicator({ current }: { current: number }) {
  return (
    <nav className="flex items-center gap-0.5">
      {STEPS.map((label, i) => {
        const done   = i < current;
        const active = i === current;
        return (
          <div key={i} className="flex items-center gap-0.5">
            <div className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg transition-all ${active ? 'bg-[rgba(212,168,58,0.1)]' : ''}`}>
              <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold flex-shrink-0 transition-all ${
                done   ? 'bg-[rgba(212,168,58,0.25)] text-[#D4A83A]' :
                active ? 'bg-[#D4A83A] text-[#04040A]' :
                         'bg-white/6 text-white/25'
              }`}>
                {done ? '✓' : i + 1}
              </span>
              <span className={`text-xs font-medium transition-all hidden sm:block ${
                done   ? 'text-[#D4A83A]/55' :
                active ? 'text-[#D4A83A]' :
                         'text-white/25'
              }`}>{label}</span>
            </div>
            {i < STEPS.length - 1 && (
              <div className={`w-3 h-px mx-0.5 flex-shrink-0 ${i < current ? 'bg-[#D4A83A]/35' : 'bg-white/8'}`} />
            )}
          </div>
        );
      })}
    </nav>
  );
}
