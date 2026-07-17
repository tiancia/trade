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
    description: "面向自动化交易系统的实时运行、策略、事件背压与回测驾驶舱。",
    openGraph: {
      type: "website",
      locale: "zh_CN",
      title: "Orbit Trading Cockpit",
      description: "把交易系统的每一个决策、事件和安全门变得可见。",
      images: [{ url: "/og.png", width: 1536, height: 1024, alt: "Orbit Trading Cockpit" }],
    },
    twitter: {
      card: "summary_large_image",
      title: "Orbit Trading Cockpit",
      description: "A command center for explainable automated trading.",
      images: ["/og.png"],
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
