"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { setToken } from "@/lib/auth";

export default function OAuthCallbackPage() {
  const router = useRouter();

  useEffect(() => {
    const hash = typeof window !== "undefined" ? window.location.hash : "";

    const existingToken = typeof window !== "undefined" ? localStorage.getItem("buildassist_token") : null;
    if (!hash) {
      if (existingToken) {
        router.replace("/dashboard");
        return;
      }
      router.replace("/auth/login");
      return;
    }

    const params = new URLSearchParams(hash.replace(/^#/, ""));
    const token = params.get("token");

    if (token) {
      setToken(token);

      window.history.replaceState({}, document.title, window.location.pathname);
      setTimeout(() => router.replace("/dashboard"), 0);
    } else {
      router.replace("/auth/login");
    }
  }, [router]);

  return (
    <main className="flex min-h-screen items-center justify-center">
      <p>Bejelentkeztetés…</p>
    </main>
  );
}
