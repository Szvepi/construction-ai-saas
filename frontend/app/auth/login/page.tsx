"use client";

import { API_BASE_URL } from "@/lib/config";
import { Button } from "@/components/ui/Button";

export default function LoginPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4">
      <h1 className="text-2xl font-bold">Bejelentkezés</h1>
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
