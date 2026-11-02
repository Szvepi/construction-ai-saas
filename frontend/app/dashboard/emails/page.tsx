"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {GmailStatus} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {EmailList} from "@/components/emails/EmailList";
import {GmailConnectBanner} from "@/components/emails/GmailConnectBanner";
import {Button} from "@/components/ui/Button";

type RefreshStatus =
    | "IN_PROGRESS"
    | "WAITING"
    | "RECONNECT_REQUIRED"
    | "RESUMABLE_ERROR"
    | "FAILED"
    | "INITIAL_COMPLETE"
    | "NO_NEW_EMAILS"
    | "COMPLETE";

export default function DashboardPage() {
    const router = useRouter();
    const [gmailStatus, setGmailStatus] = useState<GmailStatus | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [refreshMessage, setRefreshMessage] = useState<string | null>(null);
    const [refreshError, setRefreshError] = useState<string | null>(null);
    const [refreshStatus, setRefreshStatus] = useState<RefreshStatus | null>(null);
    const [refreshNeedsReconnect, setRefreshNeedsReconnect] = useState(false);
    const [emailRefreshKey, setEmailRefreshKey] = useState(0);
    const refreshInFlight = useRef(false);

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
        if (refreshInFlight.current) {
            return;
        }
        refreshInFlight.current = true;
        setRefreshing(true);
        setRefreshError(null);
        setRefreshMessage(null);
        setRefreshNeedsReconnect(false);
        setRefreshStatus("IN_PROGRESS");
        try {
            const result = await apiFetch<{
                fetchedCount: number;
                processedCount: number;
                hasMore: boolean;
                complete: boolean;
                inProgress: boolean;
                waiting: boolean;
                retryAt: string | null;
                reconnectRequired: boolean;
                phase: string;
                message: string;
            }>("/api/emails/refresh", {method: "POST"});
            if (result.reconnectRequired) {
                setRefreshNeedsReconnect(true);
                setRefreshStatus("RECONNECT_REQUIRED");
                setRefreshError(result.message);
                setRefreshMessage(null);
            } else if (result.phase === "FAILED") {
                setRefreshStatus("FAILED");
                setRefreshError(result.message);
                setRefreshMessage(null);
            } else if (result.waiting) {
                setRefreshStatus("WAITING");
                setRefreshMessage(
                    `${result.message}${result.retryAt ? ` Következő próbálkozás: ${new Date(result.retryAt).toLocaleTimeString("hu-HU")}.` : ""}`,
                );
            } else if (result.phase === "INITIAL_COMPLETE") {
                setRefreshStatus("INITIAL_COMPLETE");
                setRefreshMessage(`${result.message} ${result.fetchedCount} új levél importálva.`);
            } else if (result.inProgress || result.hasMore) {
                setRefreshStatus("IN_PROGRESS");
                setRefreshMessage(`${result.message} ${result.fetchedCount} új levél importálva ebben a körben.`);
            } else if (result.phase === "HISTORY" && result.fetchedCount === 0) {
                setRefreshStatus("NO_NEW_EMAILS");
                setRefreshMessage(result.message);
            } else {
                setRefreshStatus("COMPLETE");
                setRefreshMessage(`${result.message} ${result.fetchedCount} új levél importálva.`);
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
                setRefreshStatus("RECONNECT_REQUIRED");
            } else if (err instanceof ApiError && err.status >= 500) {
                setRefreshStatus("RESUMABLE_ERROR");
                setRefreshError(`${err.message} A szinkron állapota megőrződött; újrapróbálással folytatható.`);
            } else {
                setRefreshStatus("FAILED");
            }
            if (!(err instanceof ApiError && err.status >= 500)) {
                setRefreshError(err instanceof ApiError ? err.message : "Frissítés sikertelen");
            }
        } finally {
            setRefreshing(false);
            refreshInFlight.current = false;
        }
    }, [loadData, router]);

    useEffect(() => {
        if (!isAuthenticated()) {
            router.push("/auth/login");
            return;
        }
        let pollingTimer: number | undefined;
        let cancelled = false;
        void loadData().then((status) => {
            if (cancelled) {
                return;
            }
            setLoading(false);
            if (!status?.connected) {
                return;
            }
            void handleRefresh();
            pollingTimer = window.setInterval(() => {
                void handleRefresh();
            }, 30_000);
        });
        return () => {
            cancelled = true;
            if (pollingTimer !== undefined) {
                window.clearInterval(pollingTimer);
            }
        };
    }, [router, loadData, handleRefresh]);

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
                    </div>
                </div>
                {error && <p className="text-sm text-red-600">{error}</p>}
                {refreshMessage && (
                    <p role="status" className={`rounded-xl border px-4 py-3 text-sm ${
                        refreshStatus === "WAITING"
                            ? "border-amber-200 bg-amber-50 text-amber-900"
                            : refreshStatus === "IN_PROGRESS"
                                ? "border-sky-200 bg-sky-50 text-sky-900"
                                : "border-emerald-200 bg-emerald-50 text-emerald-800"
                    }`}>
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
