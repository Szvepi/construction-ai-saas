"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { setToken } from "@/lib/auth";

export default function OAuthCallbackPage() {
  const router = useRouter();

  useEffect(() => {
    // Parse fragment like: #token=<jwt>&email=...
    const hash = typeof window !== "undefined" ? window.location.hash : "";
    if (!hash) {
      // No token — redirect to login
      router.replace("/auth/login");
      return;
    }

    const params = new URLSearchParams(hash.replace(/^#/, ""));
    const token = params.get("token");
    // optional email param
    // const email = params.get("email");

    if (token) {
      setToken(token);
      // Remove fragment from history for cleanliness
      window.history.replaceState({}, document.title, window.location.pathname);
      // Redirect to dashboard or desired page
      router.replace("/dashboard");
    } else {
      router.replace("/auth/login");
    }
  }, [router]);

  return (
    <main className="flex min-h-screen items-center justify-center">
      <p>Signing you in…</p>
    </main>
  );
}

