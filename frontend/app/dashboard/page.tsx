"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CatalogItem, CompanyProfile, EmailSummary, GmailStatus} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {GmailConnectBanner} from "@/components/emails/GmailConnectBanner";

function formatDate(dateString: string): string {
    return new Date(dateString).toLocaleString("hu-HU", {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
    });
}

export default function DashboardPage() {
    const router = useRouter();
    const started = useRef(false);
    const [emails, setEmails] = useState<EmailSummary[]>([]);
    const [gmailStatus, setGmailStatus] = useState<GmailStatus | null>(null);
    const [companyProfile, setCompanyProfile] = useState<CompanyProfile | null>(null);
    const [catalogItems, setCatalogItems] = useState<CatalogItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const loadDashboard = useCallback(async () => {
        setError(null);
        try {
            const [status, profile, catalog, syncedEmails] = await Promise.all([
                apiFetch<GmailStatus>("/api/gmail/status"),
                apiFetch<CompanyProfile>("/api/company-settings"),
                apiFetch<CatalogItem[]>("/api/catalog"),
                apiFetch<EmailSummary[]>("/api/emails"),
            ]);
            setGmailStatus(status);
            setCompanyProfile(profile);
            setCatalogItems(catalog);
            setEmails(syncedEmails);
            setLoading(false);

            if (status.connected) {
                setRefreshing(true);
                try {
                    await apiFetch("/api/emails/refresh", {method: "POST"});
                } catch (err) {
                    if (err instanceof ApiError && err.status === 401) {
                        router.push("/auth/login");
                        return;
                    }
                    setError(err instanceof ApiError ? err.message : "Az e-mailek frissítése sikertelen.");
                } finally {
                    setRefreshing(false);
                }
            }

            if (status.connected) {
                setEmails(await apiFetch<EmailSummary[]>("/api/emails"));
            }
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            setError(err instanceof ApiError ? err.message : "Az áttekintő betöltése sikertelen.");
        } finally {
            setLoading(false);
        }
    }, [router]);

    useEffect(() => {
        if (!isAuthenticated()) {
            router.push("/auth/login");
            return;
        }
        if (started.current) return;
        started.current = true;
        void loadDashboard();
    }, [router, loadDashboard]);

    async function handleConnect() {
        try {
            const {authorizationUrl} = await apiFetch<{ authorizationUrl: string }>("/api/gmail/connect");
            window.location.href = authorizationUrl;
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A Gmail-kapcsolódás indítása sikertelen.");
        }
    }

    const quoteRequests = emails.filter((email) => email.category === "QUOTE_REQUEST").length;
    const needsReview = emails.filter(
        (email) => email.draftStatus === "DRAFT" || email.draftStatus === "PENDING_REVIEW",
    ).length;
    const readyDrafts = emails.filter((email) => email.draftStatus === "READY").length;
    const hasCompanyProfile = Boolean(
        companyProfile?.companyName &&
        companyProfile.legalForm &&
        companyProfile.taxNumber &&
        companyProfile.postalCode &&
        companyProfile.city &&
        companyProfile.streetName &&
        companyProfile.streetType &&
        companyProfile.houseNumber,
    );

    return (
        <DashboardLayout>
            <main className="mx-auto w-full min-w-0 max-w-5xl space-y-6 px-4 py-6">
                <div>
                    <h1 className="text-3xl font-bold text-slate-900">Áttekintés</h1>
                    <p className="mt-1 text-sm text-slate-600">
                        A számok a szinkronizált levelekre vonatkoznak.
                    </p>
                </div>

                <GmailConnectBanner status={gmailStatus} onConnect={handleConnect}/>
                {refreshing && (
                    <p className="rounded-md bg-blue-50 px-4 py-3 text-sm text-blue-800" role="status">
                        Levelek frissítése a Gmailből…
                    </p>
                )}
                {error && (
                    <p className="rounded-md bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">
                        {error}
                    </p>
                )}

                {loading ? (
                    <p className="py-8 text-center text-slate-500">Áttekintő betöltése…</p>
                ) : (
                    <>
                        <section className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-3" aria-label="Levelezési statisztikák">
                            <StatCard label="Árajánlatkérések" value={quoteRequests}/>
                            <StatCard label="Ellenőrzésre váró piszkozatok" value={needsReview}/>
                            <StatCard label="Kiküldésre kész ajánlatok" value={readyDrafts}/>
                        </section>

                        <div className="grid min-w-0 grid-cols-1 gap-6 lg:grid-cols-3">
                            <section className="min-w-0 rounded-lg border border-slate-200 bg-white lg:col-span-2">
                                <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-b border-slate-200 px-4 py-3">
                                    <h2 className="min-w-0 font-semibold text-slate-900">Legutóbbi levelek</h2>
                                    <Link href="/dashboard/emails" className="shrink-0 text-sm font-medium text-blue-700 hover:underline">
                                        Összes levél
                                    </Link>
                                </div>
                                {emails.length === 0 ? (
                                    <p className="px-4 py-8 text-center text-sm text-slate-500">
                                        {gmailStatus?.connected
                                            ? "Még nincs szinkronizált levél."
                                            : "Csatlakoztasd a Gmail-fiókodat a levelek megjelenítéséhez."}
                                    </p>
                                ) : (
                                    <ul className="divide-y divide-slate-200">
                                        {emails.slice(0, 5).map((email) => (
                                            <li key={email.id}>
                                                <Link
                                                    href={`/emails/${email.id}`}
                                                    className="block min-w-0 px-4 py-3 hover:bg-slate-50"
                                                >
                                                    <div className="flex min-w-0 items-start justify-between gap-3 sm:items-center">
                                                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-slate-900">
                                                            {email.subject || "(nincs tárgy)"}
                                                        </span>
                                                        <time className="shrink-0 text-right text-xs text-slate-500">
                                                            {formatDate(email.receivedAt)}
                                                        </time>
                                                    </div>
                                                    <p className="mt-1 min-w-0 truncate text-xs text-slate-600">{email.fromAddress}</p>
                                                    <span className="mt-2 inline-block rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                                                        {email.category === "QUOTE_REQUEST"
                                                            ? "Árajánlatkérés"
                                                            : email.category === "SPAM" ? "Spam" : "Egyéb"}
                                                    </span>
                                                </Link>
                                            </li>
                                        ))}
                                    </ul>
                                )}
                            </section>

                            <section className="min-w-0 space-y-4">
                                <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-4">
                                    <h2 className="font-semibold text-slate-900">Ajánlatkészítés</h2>
                                    <div className="mt-3 min-w-0 space-y-2 text-sm">
                                        <SetupStatus
                                            label="Céges adatok"
                                            ready={hasCompanyProfile}
                                            href="/dashboard/settings/company"
                                        />
                                        <SetupStatus
                                            label={`Árjegyzék (${catalogItems.length} tétel)`}
                                            ready={catalogItems.length > 0}
                                            href="/dashboard/settings/catalog"
                                        />
                                    </div>
                                </div>
                                <Link
                                    href="/dashboard/emails"
                                    className="block w-full min-w-0 rounded-lg bg-blue-700 px-4 py-3 text-center text-sm font-semibold text-white hover:bg-blue-800"
                                >
                                    Ugrás a beérkezett levelekhez
                                </Link>
                            </section>
                        </div>
                    </>
                )}
            </main>
        </DashboardLayout>
    );
}

function StatCard({label, value}: {label: string; value: number}) {
    return (
        <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-5">
            <p className="break-words text-sm font-medium text-slate-600">{label}</p>
            <p className="mt-2 text-3xl font-bold text-slate-900">{value}</p>
        </div>
    );
}

function SetupStatus({label, ready, href}: {label: string; ready: boolean; href: string}) {
    return (
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
            <span className="min-w-0 flex-1 break-words text-slate-700">{label}</span>
            <Link
                href={href}
                className={`shrink-0 ${ready ? "text-emerald-700 hover:underline" : "text-amber-700 hover:underline"}`}
            >
                {ready ? "Beállítva" : "Beállítás"}
            </Link>
        </div>
    );
}
