"use client";

import Image from "next/image";
import { API_BASE_URL } from "@/lib/config";
import { Button } from "@/components/ui/Button";

export default function LoginPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4">
      <Image
        src="/logo.png"
        alt="MIRA logó"
        width={360}
        height={360}
        className="mb-4 h-80 w-80 object-contain"
        priority
      />
      <h1 className="text-3xl font-bold text-brand-900">MIRA</h1>
      <p className="mt-2 text-center text-slate-600">
        Az okos asszisztens az építkezésen
      </p>
      <h2 className="mt-8 text-2xl font-bold">Bejelentkezés</h2>
      <p className="mt-4 text-sm text-slate-600">
        Jelentkezz be Gmail fiókoddal
      </p>
      <div className="mt-6 w-full max-w-sm">
        <Button
          className="mt-2 w-full"
          onClick={() => {
            window.location.href = `${API_BASE_URL}/oauth2/authorization/google`;
          }}
        >
          Bejelentkezés Google‑lal
        </Button>
      </div>
    </main>
  );
}
