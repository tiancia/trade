import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { headers } from "next/headers";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host") ?? "localhost:3000";
  const protocol = requestHeaders.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  const metadataBase = new URL(`${protocol}://${host}`);

  return {
    metadataBase,
    title: "Orbit Trading Cockpit",
    description: "面向自动化交易系统的实时 K 线、策略切换、事件背压与回测驾驶舱。",
    openGraph: {
      type: "website",
      locale: "zh_CN",
      title: "Orbit Trading Cockpit",
      description: "实时观察 K 线，在后端安全门内切换当前策略。",
      images: [{ url: "/og-live-strategy.png", width: 1536, height: 1024, alt: "Orbit Trading Cockpit 实时 K 线与策略切换" }],
    },
    twitter: {
      card: "summary_large_image",
      title: "Orbit Trading Cockpit",
      description: "Live candlesticks and controlled strategy switching for automated trading.",
      images: ["/og-live-strategy.png"],
    },
  };
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body className={`${geistSans.variable} ${geistMono.variable}`}>{children}</body>
    </html>
  );
}
