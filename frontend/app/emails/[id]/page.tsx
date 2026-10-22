"use client";

import {useCallback, useEffect, useState} from "react";
import Link from "next/link";
import {useParams, useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {EmailDetail} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {DraftPanel} from "@/components/emails/DraftPanel";
import {EmailSourcePanel} from "@/components/emails/EmailSourcePanel";

export default function EmailDetailPage() {
    const params = useParams();
    const router = useRouter();
    const emailId = Number(params.id);
    const [email, setEmail] = useState<EmailDetail | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

    const loadEmail = useCallback(async () => {
        try {
            const data = await apiFetch<EmailDetail>(`/api/emails/${emailId}`);
            setEmail(data);
            setError(null);
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            setError(err instanceof ApiError ? err.message : "Az e-mail betöltése sikertelen.");
        }
    }, [emailId, router]);

    useEffect(() => {
        if (!isAuthenticated()) {
            router.push("/auth/login");
            return;
        }
        if (!Number.isInteger(emailId) || emailId <= 0) {
            setError("Érvénytelen e-mail azonosító.");
            setLoading(false);
            return;
        }
        loadEmail().finally(() => setLoading(false));
    }, [emailId, router, loadEmail]);

    useEffect(() => {
        if (!hasUnsavedChanges) return;

        function preventAccidentalUnload(event: BeforeUnloadEvent) {
            event.preventDefault();
            event.returnValue = "";
        }

        function confirmInternalNavigation(event: MouseEvent) {
            if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
                return;
            }
            if (!(event.target instanceof Element)) return;
            const anchor = event.target.closest("a[href]");
            if (!anchor) return;

            const destination = new URL(anchor.getAttribute("href") ?? "", window.location.href);
            if (destination.origin !== window.location.origin
                || (destination.pathname === window.location.pathname && destination.search === window.location.search)) {
                return;
            }
            if (window.confirm("Nem mentett módosításaid vannak. Biztosan elhagyod ezt az oldalt?")) return;

            event.preventDefault();
            event.stopPropagation();
            event.stopImmediatePropagation();
        }

        window.addEventListener("beforeunload", preventAccidentalUnload);
        document.addEventListener("click", confirmInternalNavigation, true);
        return () => {
            window.removeEventListener("beforeunload", preventAccidentalUnload);
            document.removeEventListener("click", confirmInternalNavigation, true);
        };
    }, [hasUnsavedChanges]);

    return (
        <DashboardLayout>
            <main className="mx-auto max-w-[1600px] px-4 py-5 sm:px-6 sm:py-7 xl:px-8">
                <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
                    <Link
                        href="/dashboard/emails"
                        className="inline-flex items-center gap-2 rounded-lg py-1 text-sm font-medium text-brand-700 hover:text-brand-800"
                    >
                        <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
                            <path d="m15 18-6-6 6-6M9 12h11" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        Vissza a beérkezett levelekhez
                    </Link>
                    {hasUnsavedChanges && (
                        <span className="rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-800">
                            Nem mentett módosítások
                        </span>
                    )}
                </div>

                {loading && (
                    <div className="grid gap-5 xl:grid-cols-[minmax(320px,0.85fr)_minmax(0,1.65fr)]">
                        <div className="h-[420px] animate-pulse rounded-2xl border border-slate-200 bg-white"/>
                        <div className="h-[620px] animate-pulse rounded-2xl border border-slate-200 bg-white"/>
                    </div>
                )}
                {error && (
                    <div role="alert" className="rounded-2xl border border-rose-200 bg-rose-50 px-5 py-4 text-sm text-rose-800">
                        {error}
                    </div>
                )}
                {!loading && !error && email && (
                    <>
                        <div className="mb-5">
                            <p className="text-xs font-semibold uppercase tracking-wide text-brand-700">MIRA · Ajánlatkészítő</p>
                            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900 sm:text-3xl">
                                Levél részlete és ajánlat
                            </h1>
                            <p className="mt-1 text-sm text-slate-500">
                                Ellenőrizd az eredeti kérést, majd készítsd elő az ajánlatot.
                            </p>
                        </div>

                        <div className="grid min-w-0 items-start gap-5 xl:grid-cols-[minmax(320px,0.85fr)_minmax(0,1.65fr)]">
                            <EmailSourcePanel email={email}/>
                            <div className="min-w-0 space-y-4">
                                {email.replied ? (
                                    <section className="rounded-2xl border border-emerald-200 bg-emerald-50 p-5">
                                        <h2 className="font-semibold text-emerald-900">A levél megválaszolva</h2>
                                        <p className="mt-1 text-sm text-emerald-800">
                                            Ehhez a megkereséshez már készült válasz.
                                        </p>
                                    </section>
                                ) : (
                                    <DraftPanel
                                        emailId={email.id}
                                        email={email}
                                        onSent={() => loadEmail()}
                                        onDirtyChange={setHasUnsavedChanges}
                                    />
                                )}
                            </div>
                        </div>
                    </>
                )}
            </main>
        </DashboardLayout>
    );
}
