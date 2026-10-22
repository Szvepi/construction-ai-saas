"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {GmailStatus} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {EmailList} from "@/components/emails/EmailList";
import {GmailConnectBanner} from "@/components/emails/GmailConnectBanner";
import {Button} from "@/components/ui/Button";

export default function DashboardPage() {
    const router = useRouter();
    const [gmailStatus, setGmailStatus] = useState<GmailStatus | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [emailRefreshKey, setEmailRefreshKey] = useState(0);

    const loadData = useCallback(async () => {
        try {
            const status = await apiFetch<GmailStatus>("/api/gmail/status");
            setGmailStatus(status);
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
            setEmailRefreshKey((current) => current + 1);
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
            <main className="mx-auto max-w-[1320px] space-y-6 px-4 py-6 sm:px-6 lg:px-8">
                <GmailConnectBanner
                    status={gmailStatus}
                    onConnect={handleConnect}
                />
                <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Beérkezett levelek</h1>
                        <p className="mt-1 text-sm text-slate-500">Keresd és kezeld a beérkező ajánlatkéréseket.</p>
                    </div>
                    <Button
                        variant="secondary"
                        onClick={handleRefresh}
                        disabled={refreshing || !gmailStatus?.connected}
                        className="w-full sm:w-auto"
                    >
                        {refreshing ? "Frissítés…" : "E-mailek frissítése"}
                    </Button>
                </div>
                {error && <p className="text-sm text-red-600">{error}</p>}
                {loading ? (
                    <div className="h-36 animate-pulse rounded-2xl border border-slate-200 bg-white"/>
                ) : (
                    <EmailList
                        gmailConnected={Boolean(gmailStatus?.connected)}
                        refreshKey={emailRefreshKey}
                        onEmailUpdated={() => setEmailRefreshKey((current) => current + 1)}
                    />
                )}
            </main>
        </DashboardLayout>
    );
}
