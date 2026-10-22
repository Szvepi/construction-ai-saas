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
    const [refreshMessage, setRefreshMessage] = useState<string | null>(null);
    const [refreshError, setRefreshError] = useState<string | null>(null);
    const [refreshNeedsReconnect, setRefreshNeedsReconnect] = useState(false);
    const [emailRefreshKey, setEmailRefreshKey] = useState(0);
    const [backfilling, setBackfilling] = useState(false);
    const [backfillComplete, setBackfillComplete] = useState(false);
    const [backfillMessage, setBackfillMessage] = useState<string | null>(null);
    const [backfillError, setBackfillError] = useState<string | null>(null);
    const [backfillNeedsReconnect, setBackfillNeedsReconnect] = useState(false);

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
        setRefreshError(null);
        setRefreshMessage(null);
        setRefreshNeedsReconnect(false);
        let fetchedCount = 0;
        try {
            while (true) {
                const result = await apiFetch<{
                    fetchedCount: number;
                    processedCount: number;
                    hasMore: boolean;
                    complete: boolean;
                    inProgress: boolean;
                    phase: string;
                    message: string;
                }>("/api/emails/refresh", {method: "POST"});
                fetchedCount += result.fetchedCount;
                setRefreshMessage(`${result.message} ${fetchedCount} új levél importálva.`);
                if (result.complete || result.inProgress || !result.hasMore) {
                    break;
                }
            }
            await loadData();
            setEmailRefreshKey((current) => current + 1);
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            if (err instanceof ApiError && err.status === 424) {
                setRefreshNeedsReconnect(true);
            }
            setRefreshError(err instanceof ApiError ? err.message : "Frissítés sikertelen");
            if (fetchedCount > 0) {
                setEmailRefreshKey((current) => current + 1);
            }
        } finally {
            setRefreshing(false);
        }
    }, [loadData, router]);

    const handleBackfill = useCallback(async () => {
        setBackfilling(true);
        setBackfillError(null);
        setBackfillNeedsReconnect(false);
        setBackfillMessage(null);
        try {
            const result = await apiFetch<{
                importedCount: number;
                processedCount: number;
                hasMore: boolean;
                complete: boolean;
                inProgress: boolean;
                message: string;
            }>("/api/emails/backfill", {method: "POST"});
            setBackfillComplete(result.complete);
            setBackfillMessage(result.inProgress
                ? result.message
                : `${result.message} ${result.importedCount} új levél importálva.`);
            if (!result.inProgress) {
                setEmailRefreshKey((current) => current + 1);
            }
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            if (err instanceof ApiError && err.status === 424) {
                setBackfillNeedsReconnect(true);
            }
            setBackfillError(err instanceof ApiError ? err.message : "A régebbi levelek betöltése nem sikerült.");
        } finally {
            setBackfilling(false);
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
                    <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
                        <Button
                            variant="secondary"
                            onClick={handleRefresh}
                            disabled={refreshing || !gmailStatus?.connected}
                            className="w-full sm:w-auto"
                        >
                            {refreshing ? "Frissítés…" : "E-mailek frissítése"}
                        </Button>
                        <Button
                            variant="secondary"
                            onClick={handleBackfill}
                            disabled={backfilling || backfillComplete || !gmailStatus?.connected}
                            className="w-full sm:w-auto"
                        >
                            {backfilling ? "Régebbi levelek betöltése…" : "Régebbi levelek betöltése"}
                        </Button>
                    </div>
                </div>
                {error && <p className="text-sm text-red-600">{error}</p>}
                {refreshMessage && (
                    <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
                        {refreshMessage}
                    </p>
                )}
                {refreshError && (
                    <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
                        <span>{refreshError}</span>
                        <button
                            type="button"
                            onClick={handleRefresh}
                            disabled={refreshing}
                            className="font-semibold underline underline-offset-2 disabled:opacity-50"
                        >
                            {refreshing ? "Újrapróbálás…" : "Újrapróbálás"}
                        </button>
                        {refreshNeedsReconnect && (
                            <button
                                type="button"
                                onClick={handleConnect}
                                className="font-semibold underline underline-offset-2"
                            >
                                Gmail újracsatlakoztatása
                            </button>
                        )}
                    </div>
                )}
                {backfillMessage && (
                    <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
                        {backfillMessage}
                    </p>
                )}
                {backfillError && (
                    <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
                        <span>{backfillError}</span>
                        <button
                            type="button"
                            onClick={handleBackfill}
                            disabled={backfilling}
                            className="font-semibold underline underline-offset-2 disabled:opacity-50"
                        >
                            {backfilling ? "Újrapróbálás…" : "Újrapróbálás"}
                        </button>
                        {backfillNeedsReconnect && (
                            <button
                                type="button"
                                onClick={handleConnect}
                                className="font-semibold underline underline-offset-2"
                            >
                                Gmail újracsatlakoztatása
                            </button>
                        )}
                    </div>
                )}
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
