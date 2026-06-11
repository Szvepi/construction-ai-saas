"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api";
import { isAuthenticated } from "@/lib/auth";
import type { EmailSummary, GmailStatus } from "@/lib/types";
import { AppHeader } from "@/components/layout/AppHeader";
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

  const loadData = useCallback(async () => {
    try {
      const [status, list] = await Promise.all([
        apiFetch<GmailStatus>("/api/gmail/status"),
        apiFetch<EmailSummary[]>("/api/emails"),
      ]);
      setGmailStatus(status);
      setEmails(list);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/auth/login");
        return;
      }
      setError(err instanceof ApiError ? err.message : "Failed to load");
    }
  }, [router]);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.push("/auth/login");
      return;
    }
    loadData().finally(() => setLoading(false));
  }, [router, loadData]);

  async function handleConnect() {
    try {
      const { authorizationUrl } = await apiFetch<{ authorizationUrl: string }>(
        "/api/gmail/connect",
      );
      window.location.href = authorizationUrl;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Connect failed");
    }
  }

  async function handleRefresh() {
    setRefreshing(true);
    setError(null);
    try {
      await apiFetch("/api/emails/refresh", { method: "POST" });
      await loadData();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Refresh failed");
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <div className="mx-auto max-w-3xl px-4 py-6 space-y-4">
        <GmailConnectBanner
          status={gmailStatus}
          onConnect={handleConnect}
        />
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Inbox</h2>
          <Button
            variant="secondary"
            onClick={handleRefresh}
            disabled={refreshing || !gmailStatus?.connected}
          >
            {refreshing ? "Refreshing…" : "Refresh emails"}
          </Button>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
        {loading ? (
          <p className="text-center text-slate-500">Loading…</p>
        ) : (
          <EmailList emails={emails} />
        )}
      </div>
    </div>
  );
}
