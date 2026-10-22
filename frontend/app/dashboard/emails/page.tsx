"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {EmailSummary, GmailStatus} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {EmailList} from "@/components/emails/EmailList";
import {GmailConnectBanner} from "@/components/emails/GmailConnectBanner";
import {Button} from "@/components/ui/Button";

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

    const handleRefresh = useCallback(async () => {
        setRefreshing(true);
        setError(null);
        try {
            await apiFetch("/api/emails/refresh", {method: "POST"});
            await loadData();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Frissítés sikertelen");
        } finally {
            setRefreshing(false);
        }
    }, [loadData]);

    useEffect(() => {
        if (!isAuthenticated()) {
            router.push("/auth/login");
            return;
        }
        loadData().finally(() => setLoading(false));
    }, [router, loadData]);

    async function handleConnect() {
        try {
            const {authorizationUrl} = await apiFetch<{ authorizationUrl: string }>(
                "/api/gmail/connect",
            );
            window.location.href = authorizationUrl;
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Kapcsolódás sikertelen");
        }
    }

    return (
        <DashboardLayout>
            <main className="mx-auto max-w-5xl space-y-4 px-4 py-6">
                <GmailConnectBanner
                    status={gmailStatus}
                    onConnect={handleConnect}
                />
                <div className="flex items-center justify-between">
                    <h1 className="text-2xl font-semibold">Beérkezett levelek</h1>
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
                    <EmailList emails={emails} onEmailUpdated={() => loadData()}/>
                )}
            </main>
        </DashboardLayout>
    );
}
