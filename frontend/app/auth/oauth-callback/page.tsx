"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { setToken } from "@/lib/auth";

export default function OAuthCallbackPage() {
  const router = useRouter();

  useEffect(() => {
    // Parse fragment like: #token=<jwt>&email=...
    const hash = typeof window !== "undefined" ? window.location.hash : "";
    console.debug("OAuth callback - location.hash:", hash);

    // If there's no fragment, but a token already exists in storage/cookie, go to dashboard.
    // This prevents a race where the component mounts twice (dev StrictMode) and redirects to login.
    const existingToken = typeof window !== "undefined" ? localStorage.getItem("buildassist_token") : null;
    if (!hash) {
      if (existingToken) {
        console.debug("OAuth callback - no hash but token exists, redirecting to /dashboard");
        router.replace("/dashboard");
        return;
      }
      console.debug("OAuth callback - no hash and no token, redirecting to /auth/login");
      router.replace("/auth/login");
      return;
    }

    const params = new URLSearchParams(hash.replace(/^#/, ""));
    const token = params.get("token");
    const email = params.get("email");

    console.debug("OAuth callback - parsed token/email:", token, email);

    if (token) {
      setToken(token);
      // debug: show localStorage and cookies
      try {
        console.debug("OAuth callback - localStorage token:", localStorage.getItem("buildassist_token"));
        console.debug("OAuth callback - document.cookie:", document.cookie);
      } catch (e) {
        console.warn("OAuth callback - debug read failed", e);
      }

      // Remove fragment from history for cleanliness
      window.history.replaceState({}, document.title, window.location.pathname);
      // Redirect to dashboard or desired page
      // use a microtask to avoid racing another mount in StrictMode
      setTimeout(() => router.replace("/dashboard"), 0);
    } else {
      console.debug("OAuth callback - no token found in fragment, redirecting to /auth/login");
      router.replace("/auth/login");
    }
  }, [router]);

  return (
    <main className="flex min-h-screen items-center justify-center">
      <p>Bejelentkeztetés…</p>
    </main>
  );
}

