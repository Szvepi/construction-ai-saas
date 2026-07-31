"use client";

import { API_BASE_URL } from "@/lib/config";
import { Button } from "@/components/ui/Button";

export default function HomePage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4">
      <h1 className="text-3xl font-bold text-brand-900">BuildAssist Email</h1>
      <p className="mt-2 max-w-md text-center text-slate-600">
        AI-alapú e-mail asszisztens kis építőipari vállalkozások számára.
      </p>
      <div className="mt-8">
        <Button
          onClick={() => {
            window.location.href = `${API_BASE_URL}/oauth2/authorization/google`;
          }}
          className="rounded-lg bg-brand-600 px-5 py-2.5 text-white hover:bg-brand-700"
        >
          Bejelentkezés Google‑lal
        </Button>
      </div>
    </main>
  );
}
