"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CatalogItem, CompanyProfile, EmailSummary, GmailStatus} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {Button} from "@/components/ui/Button";

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
    const attentionEmails = emails
        .filter((email) =>
            email.category === "QUOTE_REQUEST" &&
            (email.draftStatus === null ||
                email.draftStatus === "DRAFT" ||
                email.draftStatus === "PENDING_REVIEW"),
        )
        .sort((left, right) => Date.parse(right.receivedAt) - Date.parse(left.receivedAt))
        .slice(0, 5);
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
            <main className="mx-auto w-full min-w-0 max-w-[1320px] space-y-6 px-4 py-6 sm:px-6 lg:space-y-7 lg:px-8">
                <section className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                    <div>
                        <p className="text-sm font-semibold text-brand-700">Munkaterület</p>
                        <h1 className="mt-1 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
                            Áttekintés
                        </h1>
                        <p className="mt-1.5 max-w-2xl text-sm leading-6 text-slate-600">
                            Kövesd az ajánlatkéréseket, és folytasd a függőben lévő ajánlatok előkészítését.
                        </p>
                    </div>
                    <Link
                        href="/dashboard/emails"
                        className="inline-flex min-h-10 shrink-0 items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-brand-900/10 transition hover:bg-brand-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
                    >
                        <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4">
                            <path d="M3.5 5.5h13v9h-13zM4 6l6 4.75L16 6" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
                        </svg>
                        Ajánlatkérések megnyitása
                    </Link>
                </section>

                <section
                    aria-label="Gmail-kapcsolat"
                    className={`flex min-w-0 flex-col gap-2 rounded-xl border px-4 py-3 sm:flex-row sm:items-center sm:justify-between ${
                        gmailStatus?.connected
                            ? "border-emerald-200 bg-emerald-50/70"
                            : "border-amber-200 bg-amber-50/80"
                    }`}
                >
                    <div className="flex min-w-0 items-center gap-3">
                        <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
                            gmailStatus?.connected ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"
                        }`}>
                            <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4">
                                <path d="M3 5.5h14v9H3zM3.5 6 10 11l6.5-5" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
                            </svg>
                        </span>
                        <div className="min-w-0">
                            <p className={`text-sm font-semibold ${
                                gmailStatus?.connected ? "text-emerald-900" : "text-amber-900"
                            }`}>
                                {gmailStatus?.connected ? "Gmail csatlakoztatva" : "A Gmail még nincs csatlakoztatva"}
                            </p>
                            <p className={`truncate text-xs ${
                                gmailStatus?.connected ? "text-emerald-800" : "text-amber-800"
                            }`}>
                                {gmailStatus?.connected
                                    ? gmailStatus.gmailAddress
                                    : "Csatlakoztasd a postafiókot az ajánlatkérések szinkronizálásához."}
                            </p>
                        </div>
                    </div>
                    {gmailStatus?.connected ? (
                        <p className="shrink-0 text-xs font-medium text-emerald-800" role="status">
                            {refreshing ? "Levelek frissítése…" : "Kapcsolat aktív"}
                        </p>
                    ) : (
                        <Button onClick={handleConnect} variant="secondary" className="self-start !px-3 !py-1.5 text-xs sm:self-auto">
                            Gmail csatlakoztatása
                        </Button>
                    )}
                </section>

                {refreshing && (
                    <p className="sr-only" role="status">Ajánlatkérések frissítése a Gmailből…</p>
                )}
                {error && (
                    <div className="flex flex-col gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800 sm:flex-row sm:items-center sm:justify-between" role="alert">
                        <p>{error}</p>
                        <Button variant="secondary" onClick={() => void loadDashboard()} className="self-start !border-red-200 !text-red-800 hover:!bg-red-100 sm:self-auto">
                            Újrapróbálás
                        </Button>
                    </div>
                )}

                {loading ? (
                    <section aria-label="Áttekintő betöltése" aria-busy="true" className="space-y-6">
                        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                            {[0, 1, 2].map((item) => (
                                <div key={item} className="h-28 animate-pulse rounded-xl border border-slate-200 bg-white"/>
                            ))}
                        </div>
                        <div className="h-64 animate-pulse rounded-xl border border-slate-200 bg-white"/>
                        <p className="sr-only">Áttekintő betöltése…</p>
                    </section>
                ) : (
                    <>
                        <section className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-3" aria-label="Ajánlatkészítési statisztikák">
                            <StatCard
                                label="Ajánlatkérés"
                                detail="Szinkronizált levelek"
                                value={quoteRequests}
                                href="/dashboard/emails?category=QUOTE_REQUEST"
                                icon="M3 5.5h14v9H3zM3.5 6 10 11l6.5-5"
                                tone="blue"
                            />
                            <StatCard
                                label="Ellenőrzésre váró piszkozat"
                                detail="Piszkozat vagy ellenőrzési státusz"
                                value={needsReview}
                                href="/dashboard/emails?draftStatus=REVIEW"
                                icon="M10 3.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13ZM10 7v3.5l2.5 1.5M15 15l3 3"
                                tone="amber"
                            />
                            <StatCard
                                label="Kiküldésre kész"
                                detail="Előkészített ajánlatok"
                                value={readyDrafts}
                                href="/dashboard/emails?draftStatus=READY"
                                icon="m5 10 3.2 3.2L15.5 6M17 10a7 7 0 1 1-2-4.9"
                                tone="green"
                            />
                        </section>

                        <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-3">
                            <section aria-labelledby="attention-heading" className="min-w-0 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm shadow-slate-900/[0.03] xl:col-span-2">
                                <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
                                    <div>
                                        <h2 id="attention-heading" className="font-semibold text-slate-900">
                                            Feldolgozást igényel
                                        </h2>
                                        <p className="mt-0.5 text-xs text-slate-500">
                                            Ajánlatkérések piszkozat vagy ellenőrzési státuszban
                                        </p>
                                    </div>
                                    <Link href="/dashboard/emails" className="shrink-0 text-sm font-semibold text-brand-700 hover:text-brand-900 hover:underline">
                                        Összes ajánlatkérés
                                    </Link>
                                </div>
                                {attentionEmails.length === 0 ? (
                                    <div className="flex min-h-44 flex-col items-center justify-center px-5 py-8 text-center">
                                        <span className="flex h-11 w-11 items-center justify-center rounded-full bg-slate-100 text-slate-500">
                                            <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-5 w-5">
                                                <path d="m4 10 4 4 8-8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/>
                                            </svg>
                                        </span>
                                        <p className="mt-3 text-sm font-semibold text-slate-800">
                                            {emails.length === 0
                                                ? gmailStatus?.connected
                                                    ? "Még nincs szinkronizált ajánlatkérés."
                                                    : "Csatlakoztasd a Gmailt az ajánlatkérések megtekintéséhez."
                                                : "Nincs feldolgozásra váró ajánlatkérés."}
                                        </p>
                                        <p className="mt-1 max-w-sm text-xs leading-5 text-slate-500">
                                            {emails.length === 0
                                                ? "A beérkező ajánlatkérések a Gmail szinkronizálása után jelennek meg itt."
                                                : "Az aktuálisan betöltött ajánlatkérések között nincs piszkozat vagy ellenőrzést igénylő tétel."}
                                        </p>
                                    </div>
                                ) : (
                                    <ul className="divide-y divide-slate-100">
                                        {attentionEmails.map((email) => (
                                            <li key={email.id}>
                                                <Link
                                                    href={`/emails/${email.id}`}
                                                    className="group flex min-w-0 items-center gap-3 px-5 py-3.5 transition-colors hover:bg-slate-50 focus-visible:bg-slate-50"
                                                >
                                                    <span className="hidden h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-50 text-brand-700 sm:flex">
                                                        <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4">
                                                            <path d="M3 5.5h14v9H3zM3.5 6 10 11l6.5-5" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
                                                        </svg>
                                                    </span>
                                                    <div className="min-w-0 flex-1">
                                                        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                                                            <span className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-900 group-hover:text-brand-800">
                                                                {email.subject || "(nincs tárgy)"}
                                                            </span>
                                                            <StatusBadge status={email.draftStatus}/>
                                                        </div>
                                                        <p className="mt-1 truncate text-xs text-slate-500">{email.fromAddress}</p>
                                                    </div>
                                                    <time className="hidden shrink-0 text-right text-xs text-slate-500 sm:block">
                                                        {formatDate(email.receivedAt)}
                                                    </time>
                                                    <span aria-hidden="true" className="shrink-0 text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-brand-600">
                                                        →
                                                    </span>
                                                </Link>
                                            </li>
                                        ))}
                                    </ul>
                                )}
                            </section>

                            <section className="min-w-0 space-y-4">
                                <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm shadow-slate-900/[0.03]">
                                    <div className="border-b border-slate-100 px-5 py-4">
                                        <h2 className="font-semibold text-slate-900">Ajánlatkészítés beállítása</h2>
                                        <p className="mt-0.5 text-xs text-slate-500">Az ajánlatok előkészítéséhez szükséges elemek</p>
                                    </div>
                                    <div className="space-y-1 p-2">
                                        <SetupStatus
                                            label="Céges adatok"
                                            ready={hasCompanyProfile}
                                            detail={hasCompanyProfile ? companyProfile?.companyName ?? "" : "Hiányos beállítás"}
                                            href="/dashboard/settings/company"
                                        />
                                        <SetupStatus
                                            label="Árjegyzék"
                                            ready={catalogItems.length > 0}
                                            detail={catalogItems.length > 0 ? `${catalogItems.length} tétel` : "Még nincs felvett tétel"}
                                            href="/dashboard/settings/catalog"
                                        />
                                    </div>
                                </div>
                                <Link
                                    href="/dashboard/emails"
                                    className="flex w-full min-w-0 items-center justify-between gap-3 rounded-xl border border-brand-100 bg-brand-50 px-4 py-3.5 text-sm font-semibold text-brand-800 transition hover:border-brand-200 hover:bg-brand-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
                                >
                                    <span>Ajánlatkérések megtekintése</span>
                                    <span aria-hidden="true">→</span>
                                </Link>
                            </section>
                        </div>
                    </>
                )}
            </main>
        </DashboardLayout>
    );
}

function StatCard({
    label,
    detail,
    value,
    href,
    icon,
    tone,
}: {
    label: string;
    detail: string;
    value: number;
    href: string;
    icon: string;
    tone: "blue" | "amber" | "green";
}) {
    const tones = {
        blue: "bg-brand-50 text-brand-700",
        amber: "bg-amber-50 text-amber-700",
        green: "bg-emerald-50 text-emerald-700",
    };

    return (
        <Link
            href={href}
            className="group flex min-w-0 items-start justify-between gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm shadow-slate-900/[0.03] transition hover:-translate-y-0.5 hover:border-brand-200 hover:shadow-md hover:shadow-brand-900/[0.06] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 sm:p-5"
        >
            <div className="min-w-0">
                <p className="text-sm font-medium text-slate-600">{label}</p>
                <p className="mt-2 text-3xl font-bold tracking-tight text-slate-900">{value}</p>
                <p className="mt-1 text-xs text-slate-500">{detail}</p>
            </div>
            <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${tones[tone]}`}>
                <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-5 w-5">
                    <path d={icon} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
            </span>
        </Link>
    );
}

function StatusBadge({status}: {status: EmailSummary["draftStatus"]}) {
    const appearance = status === "PENDING_REVIEW"
        ? "bg-amber-50 text-amber-800 ring-amber-200"
        : status === "DRAFT"
            ? "bg-slate-100 text-slate-700 ring-slate-200"
            : "bg-blue-50 text-blue-800 ring-blue-200";
    const label = status === "PENDING_REVIEW"
        ? "Ellenőrzésre vár"
        : status === "DRAFT"
            ? "Piszkozat"
            : "Új kérés";

    return (
        <span className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset ${appearance}`}>
            {label}
        </span>
    );
}

function SetupStatus({label, ready, detail, href}: {label: string; ready: boolean; detail: string; href: string}) {
    return (
        <Link
            href={href}
            className="flex min-w-0 items-center gap-3 rounded-lg px-3 py-3 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
        >
            <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${
                ready ? "bg-emerald-50 text-emerald-700" : "bg-amber-50 text-amber-700"
            }`}>
                {ready ? (
                    <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4">
                        <path d="m4.5 10 3.5 3.5 7.5-7.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                ) : (
                    <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4">
                        <path d="M10 6.5v4m0 3h.01M10 2.75l8 14H2l8-14Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                )}
            </span>
            <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-slate-800">{label}</span>
                <span className="mt-0.5 block truncate text-xs text-slate-500">{detail}</span>
            </span>
            <span className={`shrink-0 text-xs font-semibold ${
                ready ? "text-emerald-700" : "text-amber-700"
            }`}>
                {ready ? "Beállítva" : "Beállítás"}
            </span>
            </Link>
    );
}
