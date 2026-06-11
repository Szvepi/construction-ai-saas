import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "BuildAssist Email",
  description: "AI email assistant for construction companies",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="hu">
      <body className="min-h-screen">{children}</body>
    </html>
  );
}
