import type { Metadata } from "next";
import IdleSessionMonitor from "@/components/auth/IdleSessionMonitor";
import "./globals.css";

export const metadata: Metadata = {
  title: "MIRA – Az okos asszisztens az építkezésen",
  description: "AI-alapú okos asszisztens építőipari vállalkozások számára.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="hu">
      <body className="min-h-screen">
        <IdleSessionMonitor />
        {children}
      </body>
    </html>
  );
}
