import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Flow Workbench",
  description: "Returney LLM 파이프라인 모니터링 및 프롬프트 워크벤치",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
