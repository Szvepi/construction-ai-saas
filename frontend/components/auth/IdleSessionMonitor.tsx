"use client";

import { useEffect, useRef } from "react";
import { clearToken, getToken, setToken } from "@/lib/auth";
import { getSessionTimeoutMinutes } from "@/lib/config";

const ACTIVITY_EVENTS = [
  "click",
  "keydown",
  "mousemove",
  "mousedown",
  "touchstart",
  "scroll",
  "wheel",
  "pointerdown",
  "input",
] as const;

export default function IdleSessionMonitor() {
  const timeoutRef = useRef<number | null>(null);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }

    const logout = () => {
      clearToken();
      const pathname = window.location.pathname;
      if (!pathname.startsWith("/auth")) {
        window.location.href = "/auth/login";
      }
    };

    const refreshSession = () => {
      const token = getToken();
      if (!token) {
        return;
      }

      if (timeoutRef.current) {
        window.clearTimeout(timeoutRef.current);
      }

      setToken(token);
      timeoutRef.current = window.setTimeout(
        logout,
        getSessionTimeoutMinutes() * 60 * 1000,
      );
    };

    const token = getToken();
    if (!token) {
      return;
    }

    refreshSession();

    for (const eventName of ACTIVITY_EVENTS) {
      window.addEventListener(eventName, refreshSession, { passive: true });
    }

    return () => {
      if (timeoutRef.current) {
        window.clearTimeout(timeoutRef.current);
      }

      for (const eventName of ACTIVITY_EVENTS) {
        window.removeEventListener(eventName, refreshSession);
      }
    };
  }, []);

  return null;
}
