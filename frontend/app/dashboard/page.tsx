"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api";
import { isAuthenticated } from "@/lib/auth";
import type { EmailSummary, GmailStatus } from "@/lib/types";
import { AppHeader } from "@/components/layout/AppHeader";
import { DashboardNav } from "@/components/layout/DashboardNav";
import { EmailList } from "@/components/emails/EmailList";
import { GmailConnectBanner } from "@/components/emails/GmailConnectBanner";
import { Button } from "@/components/ui/Button";

export default function DashboardPage() {
  const router = useRouter();
  const [emails, setEmails] = useState<EmailSummary[]>([]);
  const [gmailStatus, setGmailStatus] = useState<GmailStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasRefreshedOnConnect, setHasRefreshedOnConnect] = useState(false);

  const loadData = useCallback(async () => {
    try {
      const [status, list] = await Promise.all([
        apiFetch<GmailStatus>("/api/gmail/status"),
        apiFetch<EmailSummary[]>("/api/emails"),
      ]);
      setGmailStatus(status);
      setEmails(list);
      return status;
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/auth/login");
        return null;
      }
      setError(err instanceof ApiError ? err.message : "Betöltés sikertelen");
      return null;
    }
  }, [router]);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.push("/auth/login");
      return;
    }
    loadData().finally(() => setLoading(false));
  }, [router, loadData]);

  // Auto-refresh emails when Gmail connects for the first time
  useEffect(() => {
    if (gmailStatus?.connected && emails.length === 0 && !hasRefreshedOnConnect && !loading) {
      setHasRefreshedOnConnect(true);
      handleRefresh();
    }
  }, [gmailStatus?.connected, emails.length, hasRefreshedOnConnect, loading]);

  async function handleConnect() {
    try {
      const { authorizationUrl } = await apiFetch<{ authorizationUrl: string }>(
        "/api/gmail/connect",
      );
      window.location.href = authorizationUrl;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Kapcsolódás sikertelen");
    }
  }

  async function handleRefresh() {
    setRefreshing(true);
    setError(null);
    try {
      await apiFetch("/api/emails/refresh", { method: "POST" });
      await loadData();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Frissítés sikertelen");
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <DashboardNav />
      <div className="mx-auto max-w-3xl px-4 py-6 space-y-4">
        <GmailConnectBanner
          status={gmailStatus}
          onConnect={handleConnect}
        />
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Beérkezettek</h2>
          <Button
            variant="secondary"
            onClick={handleRefresh}
            disabled={refreshing || !gmailStatus?.connected}
          >
            {refreshing ? "Frissítés…" : "E-mailek frissítése"}
          </Button>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
        {loading ? (
          <p className="text-center text-slate-500">Betöltés…</p>
        ) : (
          <EmailList emails={emails} />
        )}
      </div>
    </div>
  );
}
