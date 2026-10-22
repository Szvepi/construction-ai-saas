"use client";

import Link from "next/link";
import {useCallback, useEffect, useMemo, useRef, useState} from "react";
import type {MouseEvent} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import type {EmailSearchResponse, EmailSummary} from "@/lib/types";

type Props = {
    gmailConnected: boolean;
    refreshKey: number;
    onEmailUpdated?: (id: number) => void;
};

type CategoryFilter = "" | EmailSummary["category"];
type SearchState = {
    q: string;
    category: CategoryFilter;
    draftStatus: "" | "REVIEW";
    page: number;
    size: number;
    direction: "asc" | "desc";
};
type Tab = {
    id: string;
    label: string;
    count: (response: EmailSearchResponse) => number;
    category: CategoryFilter;
    draftStatus: SearchState["draftStatus"];
};

const PAGE_SIZE = 25;
const CATEGORY_MAP: Record<EmailSummary["category"], {label: string; className: string}> = {
    QUOTE_REQUEST: {label: "Ajánlatkérés", className: "border-blue-100 bg-blue-50 text-blue-700"},
    OTHER: {label: "Egyéb", className: "border-slate-200 bg-slate-100 text-slate-700"},
    SPAM: {label: "Spam", className: "border-rose-100 bg-rose-50 text-rose-700"},
};
const DRAFT_STATUS_MAP = {
    NONE: {label: "Nincs ajánlat", className: "border-slate-200 bg-slate-50 text-slate-600"},
    DRAFT: {label: "Piszkozat", className: "border-amber-100 bg-amber-50 text-amber-800"},
    PENDING_REVIEW: {label: "Ellenőrzésre vár", className: "border-amber-100 bg-amber-50 text-amber-800"},
    READY: {label: "Elkészült", className: "border-emerald-100 bg-emerald-50 text-emerald-700"},
    SENT: {label: "Elküldve", className: "border-violet-100 bg-violet-50 text-violet-700"},
} as const;

function readSearchState(): SearchState {
    const params = new URLSearchParams(window.location.search);
    const rawPage = Number(params.get("page"));
    const rawSize = Number(params.get("size"));
    const category = params.get("category");
    const direction = params.get("direction");
    return {
        q: params.get("q") ?? "",
        category: category === "QUOTE_REQUEST" || category === "OTHER" || category === "SPAM" ? category : "",
        draftStatus: params.get("draftStatus") === "REVIEW" ? "REVIEW" : "",
        page: Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0,
        size: Number.isInteger(rawSize) && rawSize >= 1 && rawSize <= 100 ? rawSize : PAGE_SIZE,
        direction: direction === "asc" ? "asc" : "desc",
    };
}

function toQueryString(state: SearchState): string {
    const params = new URLSearchParams();
    if (state.q.trim()) params.set("q", state.q.trim());
    if (state.category) params.set("category", state.category);
    if (state.draftStatus) params.set("draftStatus", state.draftStatus);
    if (state.page > 0) params.set("page", String(state.page));
    if (state.size !== PAGE_SIZE) params.set("size", String(state.size));
    if (state.direction !== "desc") params.set("direction", state.direction);
    return params.toString();
}

function formatDate(dateString: string): string {
    const date = new Date(dateString);
    if (Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat("hu-HU", {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
    }).format(date);
}

function totalCategoryCount(response: EmailSearchResponse): number {
    return Object.values(response.categoryCounts ?? {}).reduce((sum, count) => sum + count, 0);
}

export function EmailList({gmailConnected, refreshKey, onEmailUpdated}: Props) {
    const router = useRouter();
    const [searchState, setSearchState] = useState<SearchState>({
        q: "", category: "", draftStatus: "", page: 0, size: PAGE_SIZE, direction: "desc",
    });
    const [searchInput, setSearchInput] = useState("");
    const [initialized, setInitialized] = useState(false);
    const [result, setResult] = useState<EmailSearchResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [openDropdown, setOpenDropdown] = useState<number | null>(null);
    const [updatingCategoryId, setUpdatingCategoryId] = useState<number | null>(null);
    const requestId = useRef(0);

    useEffect(() => {
        const initial = readSearchState();
        setSearchState(initial);
        setSearchInput(initial.q);
        setInitialized(true);

        function restoreFromUrl() {
            const next = readSearchState();
            setSearchState(next);
            setSearchInput(next.q);
        }
        window.addEventListener("popstate", restoreFromUrl);
        return () => window.removeEventListener("popstate", restoreFromUrl);
    }, []);

    const updateSearchState = useCallback((next: SearchState, replace = false) => {
        setSearchState(next);
        const query = toQueryString(next);
        const nextUrl = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
        if (replace) window.history.replaceState({}, "", nextUrl);
        else window.history.pushState({}, "", nextUrl);
    }, []);

    useEffect(() => {
        if (!initialized) return;
        const timeout = window.setTimeout(() => {
            if (searchInput.trim() === searchState.q.trim()) return;
            updateSearchState({...searchState, q: searchInput, page: 0}, true);
        }, 350);
        return () => window.clearTimeout(timeout);
    }, [initialized, searchInput, searchState, updateSearchState]);

    useEffect(() => {
        if (!initialized) return;
        const controller = new AbortController();
        const currentRequestId = ++requestId.current;
        const query = toQueryString(searchState);
        const apiParams = new URLSearchParams(query);
        setLoading(true);
        setError(null);

        apiFetch<EmailSearchResponse>(`/api/emails/search${apiParams.toString() ? `?${apiParams}` : ""}`, {
            signal: controller.signal,
        }).then((response) => {
            if (requestId.current === currentRequestId) setResult(response);
        }).catch((err: unknown) => {
            if (controller.signal.aborted || requestId.current !== currentRequestId) return;
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            setError(err instanceof ApiError ? err.message : "A levelek betöltése nem sikerült.");
        }).finally(() => {
            if (!controller.signal.aborted && requestId.current === currentRequestId) setLoading(false);
        });

        return () => controller.abort();
    }, [initialized, refreshKey, router, searchState]);

    const tabs = useMemo<Tab[]>(() => [
        {id: "all", label: "Összes", count: totalCategoryCount, category: "", draftStatus: ""},
        {id: "quotes", label: "Ajánlatkérések", count: (data) => data.categoryCounts.QUOTE_REQUEST ?? 0, category: "QUOTE_REQUEST", draftStatus: ""},
        {id: "review", label: "Ellenőrzésre vár", count: (data) => data.reviewCount ?? 0, category: "", draftStatus: "REVIEW"},
        {id: "other", label: "Egyéb", count: (data) => data.categoryCounts.OTHER ?? 0, category: "OTHER", draftStatus: ""},
        {id: "spam", label: "Spam", count: (data) => data.categoryCounts.SPAM ?? 0, category: "SPAM", draftStatus: ""},
    ], []);
    const activeTab = searchState.draftStatus === "REVIEW"
        ? "review"
        : searchState.category === "QUOTE_REQUEST"
            ? "quotes"
            : searchState.category === "OTHER"
                ? "other"
                : searchState.category === "SPAM"
                    ? "spam"
                    : "all";

    function chooseTab(tab: Tab) {
        updateSearchState({...searchState, category: tab.category, draftStatus: tab.draftStatus, page: 0});
    }

    function changePage(page: number) {
        updateSearchState({...searchState, page});
    }

    async function handleCategoryChange(emailId: number, newCategory: EmailSummary["category"], event: MouseEvent) {
        event.preventDefault();
        event.stopPropagation();
        setOpenDropdown(null);
        setUpdatingCategoryId(emailId);
        setError(null);
        try {
            await apiFetch(`/api/emails/${emailId}/category`, {
                method: "PATCH",
                body: {category: newCategory},
            });
            onEmailUpdated?.(emailId);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A kategória módosítása nem sikerült.");
        } finally {
            setUpdatingCategoryId(null);
        }
    }

    function getAvailableCategories(category: EmailSummary["category"]): EmailSummary["category"][] {
        if (category === "OTHER") return ["QUOTE_REQUEST", "SPAM"];
        if (category === "QUOTE_REQUEST") return ["SPAM"];
        return ["QUOTE_REQUEST"];
    }

    const currentPage = result?.page ?? searchState.page;
    const totalPages = result?.totalPages ?? 0;
    const firstItem = result && result.totalElements > 0 ? currentPage * result.size + 1 : 0;
    const lastItem = result ? Math.min((currentPage + 1) * result.size, result.totalElements) : 0;

    return (
        <section className="space-y-5">
            <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm shadow-slate-900/[0.02]">
                <div className="border-b border-slate-100 px-4 pt-4 sm:px-6">
                    <div className="flex flex-wrap gap-x-1" role="tablist" aria-label="Levelek kategóriái">
                        {tabs.map((tab) => {
                            const active = activeTab === tab.id;
                            return (
                                <button
                                    key={tab.id}
                                    type="button"
                                    role="tab"
                                    aria-selected={active}
                                    onClick={() => chooseTab(tab)}
                                    className={`-mb-px inline-flex shrink-0 items-center gap-2 border-b-2 px-3 py-3 text-sm font-semibold transition-colors sm:px-4 ${
                                        active
                                            ? "border-brand-600 text-brand-700"
                                            : "border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-800"
                                    }`}
                                >
                                    {tab.label}
                                    <span className={`rounded-full px-2 py-0.5 text-xs ${active ? "bg-brand-50 text-brand-700" : "bg-slate-100 text-slate-500"}`}>
                                        {result ? tab.count(result) : "—"}
                                    </span>
                                </button>
                            );
                        })}
                    </div>
                </div>

                <div className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                    <label className="relative block w-full sm:max-w-md">
                        <svg className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
                            <circle cx="11" cy="11" r="7" strokeWidth="1.8"/>
                            <path d="m16 16 4 4" strokeWidth="1.8" strokeLinecap="round"/>
                        </svg>
                        <input
                            value={searchInput}
                            onChange={(event) => setSearchInput(event.target.value)}
                            placeholder="Keresés feladó vagy tárgy alapján…"
                            aria-label="Keresés feladó vagy tárgy alapján"
                            className="w-full rounded-xl border border-slate-200 bg-white py-2.5 pl-10 pr-3 text-sm text-slate-800 outline-none transition placeholder:text-slate-400 focus:border-brand-300 focus:ring-4 focus:ring-brand-500/10"
                        />
                    </label>
                    <button
                        type="button"
                        onClick={() => updateSearchState({...searchState, direction: searchState.direction === "desc" ? "asc" : "desc", page: 0})}
                        className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-3 text-sm font-medium text-slate-600 transition hover:bg-slate-50 hover:text-slate-900"
                    >
                        <svg className="h-4 w-4 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
                            <path d="M8 6v12m0 0-3-3m3 3 3-3M16 18V6m0 0-3 3m3-3 3 3" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        {searchState.direction === "desc" ? "Legújabb elöl" : "Legrégebbi elöl"}
                    </button>
                </div>

                {!gmailConnected && (
                    <div className="mx-4 mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900 sm:mx-6">
                        Kapcsold össze a Gmail-fiókodat a levelek megjelenítéséhez.
                    </div>
                )}
                {error && (
                    <div role="alert" className="mx-4 mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800 sm:mx-6">
                        <span>{error}</span>
                        <button type="button" className="font-semibold underline underline-offset-2" onClick={() => onEmailUpdated?.(0)}>Újrapróbálás</button>
                    </div>
                )}

                <div className="min-h-64 border-t border-slate-100">
                    {loading ? (
                        <div className="space-y-3 p-4 sm:p-6" aria-label="Levelek betöltése">
                            {Array.from({length: 5}, (_, index) => (
                                <div key={index} className="h-[76px] animate-pulse rounded-xl bg-slate-100"/>
                            ))}
                        </div>
                    ) : !error && result?.content.length === 0 ? (
                        <div className="flex min-h-64 flex-col items-center justify-center px-6 py-12 text-center">
                            <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-100 text-slate-500">
                                <svg className="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
                                    <path d="M4 6.5h16v11H4z" strokeWidth="1.6" strokeLinejoin="round"/>
                                    <path d="m5 7 7 6 7-6" strokeWidth="1.6" strokeLinejoin="round"/>
                                </svg>
                            </div>
                            <h2 className="text-sm font-semibold text-slate-900">
                                {!gmailConnected ? "Nincs csatlakoztatott Gmail-fiók" : searchState.q ? "Nincs találat" : "Itt még nincsenek levelek"}
                            </h2>
                            <p className="mt-1 max-w-sm text-sm text-slate-500">
                                {!gmailConnected
                                    ? "A Gmail-kapcsolat létrehozása után itt jelennek meg az ajánlatkérések."
                                    : searchState.q
                                        ? "Próbálj más keresőkifejezést vagy válassz egy másik kategóriát."
                                        : "Frissítsd a leveleket a Gmailből, vagy válassz egy másik kategóriát."}
                            </p>
                        </div>
                    ) : !error && result ? (
                        <ul className="divide-y divide-slate-100">
                            {result.content.map((email) => {
                                const categoryBadge = CATEGORY_MAP[email.category];
                                const statusBadge = DRAFT_STATUS_MAP[email.draftStatus ?? "NONE"];
                                const categories = getAvailableCategories(email.category);
                                const isOpen = openDropdown === email.id;
                                return (
                                    <li key={email.id} className="relative">
                                        <div className="flex min-w-0 items-center gap-3 px-4 py-4 transition hover:bg-slate-50/80 sm:gap-4 sm:px-6">
                                            <div className="hidden h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-50 text-xs font-bold text-brand-700 sm:flex">
                                                {(email.fromAddress || "?").trim().charAt(0).toUpperCase()}
                                            </div>
                                            <Link href={`/emails/${email.id}`} className="min-w-0 flex-1">
                                                <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
                                                    <span className="truncate text-sm font-semibold text-slate-900">
                                                        {email.fromAddress || "Ismeretlen feladó"}
                                                    </span>
                                                    <time className="shrink-0 text-xs text-slate-400" dateTime={email.receivedAt}>
                                                        {formatDate(email.receivedAt)}
                                                    </time>
                                                </div>
                                                <p className="mt-1 truncate text-sm text-slate-600">{email.subject || "(nincs tárgy)"}</p>
                                                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                                                    <span className={`rounded-full border px-2.5 py-1 text-[11px] font-semibold ${categoryBadge.className}`}>
                                                        {categoryBadge.label}
                                                    </span>
                                                    {email.category === "QUOTE_REQUEST" && (
                                                        <span className={`rounded-full border px-2.5 py-1 text-[11px] font-semibold ${statusBadge.className}`}>
                                                            {statusBadge.label}
                                                        </span>
                                                    )}
                                                    {email.replied && (
                                                        <span className="rounded-full border border-emerald-100 bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700">Válaszolt</span>
                                                    )}
                                                </div>
                                            </Link>
                                            {categories.length > 0 && (
                                                <div className="relative shrink-0">
                                                    <button
                                                        type="button"
                                                        aria-label={`Átsorolás: ${email.subject || "levél"}`}
                                                        aria-expanded={isOpen}
                                                        disabled={updatingCategoryId === email.id}
                                                        onClick={() => setOpenDropdown(isOpen ? null : email.id)}
                                                        className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-600 shadow-sm transition hover:bg-slate-50 disabled:opacity-50"
                                                    >
                                                        {updatingCategoryId === email.id ? "Mentés…" : "Átsorolás"}
                                                    </button>
                                                    {isOpen && (
                                                        <div className="absolute right-0 z-20 mt-2 w-44 rounded-xl border border-slate-200 bg-white p-1.5 shadow-xl shadow-slate-900/10">
                                                            {categories.map((category) => (
                                                                <button
                                                                    type="button"
                                                                    key={category}
                                                                    onClick={(event) => handleCategoryChange(email.id, category, event)}
                                                                    className="block w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-50"
                                                                >
                                                                    Átsorolás: {CATEGORY_MAP[category].label}
                                                                </button>
                                                            ))}
                                                        </div>
                                                    )}
                                                </div>
                                            )}
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    ) : null}
                </div>

                {result && !loading && !error && (
                    <footer className="flex flex-col gap-3 border-t border-slate-100 px-4 py-4 text-sm text-slate-500 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                        <p>
                            {result.totalElements > 0
                                ? `${firstItem}–${lastItem} megjelenítése a ${result.totalElements} levélből`
                                : "0 levél"}
                        </p>
                        <div className="flex items-center justify-between gap-3 sm:justify-end">
                            <button
                                type="button"
                                onClick={() => changePage(currentPage - 1)}
                                disabled={currentPage <= 0}
                                className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
                            >
                                Előző
                            </button>
                            <span className="min-w-20 text-center text-xs text-slate-500">
                                {totalPages > 0 ? `${currentPage + 1} / ${totalPages}` : "0 / 0"}
                            </span>
                            <button
                                type="button"
                                onClick={() => changePage(currentPage + 1)}
                                disabled={currentPage + 1 >= totalPages}
                                className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
                            >
                                Következő
                            </button>
                        </div>
                    </footer>
                )}
            </div>
        </section>
    );
}
