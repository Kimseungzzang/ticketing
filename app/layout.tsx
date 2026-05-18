import type { Metadata } from 'next';
import { Cormorant_Garamond, Noto_Sans_KR } from 'next/font/google';
import './globals.css';

const cormorant = Cormorant_Garamond({
  subsets: ['latin'],
  weight: ['300', '400', '500', '600', '700'],
  variable: '--font-cormorant',
  display: 'swap',
});

const notoSansKR = Noto_Sans_KR({
  subsets: ['latin'],
  weight: ['300', '400', '500', '600', '700'],
  variable: '--font-noto',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'STAGE TICKET | 공연 예매',
  description: 'ECLIPSE WORLD TOUR 2026 공연 예매',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={`${cormorant.variable} ${notoSansKR.variable}`}>
      <body>{children}</body>
    </html>
  );
}
