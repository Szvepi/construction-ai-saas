"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api";
import { isAuthenticated } from "@/lib/auth";
import type { EmailDetail } from "@/lib/types";
import { AppHeader } from "@/components/layout/AppHeader";
import { DraftPanel } from "@/components/emails/DraftPanel";

export default function EmailDetailPage() {
  const params = useParams();
  const router = useRouter();
  const emailId = Number(params.id);
  const [email, setEmail] = useState<EmailDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadEmail = useCallback(async () => {
    try {
      const data = await apiFetch<EmailDetail>(`/api/emails/${emailId}`);
      setEmail(data);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/auth/login");
        return;
      }
      setError(err instanceof ApiError ? err.message : "Failed to load");
    }
  }, [emailId, router]);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.push("/auth/login");
      return;
    }
    if (Number.isNaN(emailId)) {
      setError("Invalid email id");
      setLoading(false);
      return;
    }
    loadEmail().finally(() => setLoading(false));
  }, [emailId, router, loadEmail]);

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <div className="mx-auto max-w-3xl px-4 py-6">
        <Link href="/dashboard" className="text-sm text-brand-600 hover:underline">
          ← Back to inbox
        </Link>
        {loading && <p className="mt-4 text-slate-500">Loading…</p>}
        {error && <p className="mt-4 text-red-600">{error}</p>}
        {email && (
          <article className="mt-4 rounded-lg border border-slate-200 bg-white p-4">
            <h1 className="text-xl font-semibold">
              {email.subject || "(no subject)"}
            </h1>
            <p className="mt-1 text-sm text-slate-600">From: {email.fromAddress}</p>
            <pre className="mt-4 whitespace-pre-wrap text-sm text-slate-800">
              {email.bodyText}
            </pre>
            {!email.replied && (
              <DraftPanel emailId={email.id} onSent={() => loadEmail()} />
            )}
            {email.replied && (
              <p className="mt-4 text-sm text-green-700">This email has been replied to.</p>
            )}
          </article>
        )}
      </div>
    </div>
  );
}
