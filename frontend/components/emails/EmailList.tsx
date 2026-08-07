"use client";

import Link from "next/link";
import {useMemo, useState} from "react";
import type {EmailSummary} from "@/lib/types";
import {apiFetch} from "@/lib/api";

type Props = { emails: EmailSummary[]; onEmailUpdated?: (id: number) => void };

type CategoryFilter = "ALL" | "QUOTE_REQUEST" | "SPAM" | "OTHER";

const CATEGORY_MAP = {
    QUOTE_REQUEST: {label: "Árajánlat kérés", color: "bg-blue-100 text-blue-800 border-blue-200"},
    OTHER: {label: "Egyéb", color: "bg-gray-100 text-gray-800 border-gray-200"},
    SPAM: {label: "Spam", color: "bg-red-100 text-red-800 border-red-200"},
};

function formatDate(dateString: string): string {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return "most";
    if (diffMins < 60) return `${diffMins} perce`;
    if (diffHours < 24) return `${diffHours} órája`;
    if (diffDays < 7) return `${diffDays} napja`;

    return date.toLocaleDateString("hu-HU", {month: "short", day: "numeric"});
}

export function EmailList({emails, onEmailUpdated}: Props) {
    const [filter, setFilter] = useState<CategoryFilter>("ALL");
    const [openDropdown, setOpenDropdown] = useState<number | null>(null);

    const filteredEmails = useMemo(() => {
        if (filter === "ALL") return emails;
        return emails.filter((e) => e.category === filter);
    }, [emails, filter]);

    async function handleCategoryChange(emailId: number, newCategory: string, event: React.MouseEvent) {
        event.preventDefault();
        event.stopPropagation();
        setOpenDropdown(null);

        try {
            await apiFetch(`/api/emails/${emailId}/category`, {
                method: "PATCH",
                body: {category: newCategory},
            });
            onEmailUpdated?.(emailId);
        } catch (err) {
            console.error("Failed to update category:", err);
        }
    }

    function getAvailableCategories(currentCategory: EmailSummary["category"]): (keyof typeof CATEGORY_MAP)[] {
        if (currentCategory === "OTHER") return ["QUOTE_REQUEST", "SPAM"];
        if (currentCategory === "QUOTE_REQUEST") return ["SPAM"];
        if (currentCategory === "SPAM") return ["QUOTE_REQUEST"];
        return [];
    }

    if (emails.length === 0) {
        return (
            <p className="py-12 text-center text-slate-500">
                Nincsenek e-mailek. Kapcsold össze Gmail fiókodat, majd frissítsd.
            </p>
        );
    }

    return (
        <div className="space-y-4 relative">
            {/* Láthatatlan háttér (Backdrop) - ha nyitva van egy dropdown, erre kattintva bezáródik */}
            {openDropdown !== null && (
                <div
                    className="fixed inset-0 z-10 bg-transparent"
                    onClick={() => setOpenDropdown(null)}
                />
            )}

            {/* Szűrő gombok / Tabok */}
            <div className="flex flex-wrap gap-2">
                <button
                    onClick={() => setFilter("ALL")}
                    className={`px-3 py-1 rounded-full text-sm font-medium transition-colors ${
                        filter === "ALL"
                            ? "bg-brand-600 text-white"
                            : "bg-slate-200 text-slate-700 hover:bg-slate-300"
                    }`}
                >
                    Összes ({emails.length})
                </button>
                <button
                    onClick={() => setFilter("QUOTE_REQUEST")}
                    className={`px-3 py-1 rounded-full text-sm font-medium transition-colors ${
                        filter === "QUOTE_REQUEST"
                            ? "bg-blue-600 text-white"
                            : "bg-blue-100 text-blue-700 hover:bg-blue-200"
                    }`}
                >
                    Árajánlat kérések ({emails.filter((e) => e.category === "QUOTE_REQUEST").length})
                </button>
                <button
                    onClick={() => setFilter("OTHER")}
                    className={`px-3 py-1 rounded-full text-sm font-medium transition-colors ${
                        filter === "OTHER"
                            ? "bg-gray-600 text-white"
                            : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                    }`}
                >
                    Egyéb ({emails.filter((e) => e.category === "OTHER").length})
                </button>
                <button
                    onClick={() => setFilter("SPAM")}
                    className={`px-3 py-1 rounded-full text-sm font-medium transition-colors ${
                        filter === "SPAM"
                            ? "bg-red-600 text-white"
                            : "bg-red-100 text-red-700 hover:bg-red-200"
                    }`}
                >
                    Spam ({emails.filter((e) => e.category === "SPAM").length})
                </button>
            </div>

            {/* Email Lista */}
            {filteredEmails.length === 0 ? (
                <p className="py-8 text-center text-slate-500">Nincsenek e-mailek ebben a kategóriában.</p>
            ) : (
                <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white">
                    {filteredEmails.map((email) => {
                        const badge = CATEGORY_MAP[email.category] || CATEGORY_MAP.OTHER;
                        const availableCategories = getAvailableCategories(email.category);
                        const hasOptions = availableCategories.length > 0;
                        const isOpen = openDropdown === email.id;

                        return (
                            <li key={email.id} className="relative">
                                <Link
                                    href={`/emails/${email.id}`}
                                    className="block px-4 py-3 hover:bg-slate-50 transition-colors"
                                >
                                    <div className="flex items-start justify-between gap-2">
                                        <div className="min-w-0 flex-1">
                                            {/* Fejléc: Tárgy + Dátum */}
                                            <div className="flex items-center justify-between gap-2">
                                                <span className="font-medium text-slate-900 line-clamp-1">
                                                    {email.subject || "(nincs tárgy)"}
                                                </span>
                                                <span className="shrink-0 text-xs text-slate-500">
                                                    {formatDate(email.receivedAt)}
                                                </span>
                                            </div>

                                            {/* Feladó */}
                                            <p className="mt-0.5 text-xs text-slate-500">{email.fromAddress}</p>

                                            {/* Badgek & Átsorolás Akció */}
                                            <div className="mt-2 flex flex-wrap items-center gap-2">
                                                <span
                                                    className={`inline-block rounded px-2 py-0.5 text-xs font-medium border ${badge.color}`}>
                                                    {badge.label}
                                                </span>

                                                {email.replied && (
                                                    <span
                                                        className="inline-block rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800 border border-green-200">
                                                        Válaszolt
                                                    </span>
                                                )}

                                                {/* Átsorolás Gomb */}
                                                {hasOptions && (
                                                    <div className="ml-auto relative z-20">
                                                        <button
                                                            onClick={(e) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                                setOpenDropdown(isOpen ? null : email.id);
                                                            }}
                                                            className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 shadow-xs hover:bg-slate-50 hover:text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500/20"
                                                            title="Kategória módosítása"
                                                        >
                                                            <span>Átsorolás</span>
                                                            <svg
                                                                className={`h-3 w-3 text-slate-500 transition-transform ${isOpen ? "rotate-180" : ""}`}
                                                                fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                <path strokeLinecap="round" strokeLinejoin="round"
                                                                      strokeWidth={2} d="M19 9l-7 7-7-7"/>
                                                            </svg>
                                                        </button>

                                                        {/* Dropdown menü */}
                                                        {isOpen && (
                                                            <div
                                                                className="absolute right-0 mt-1 w-44 rounded-lg border border-slate-200 bg-white py-1 shadow-lg ring-1 ring-black/5 z-30"
                                                                onClick={(e) => {
                                                                    e.preventDefault();
                                                                    e.stopPropagation();
                                                                }}
                                                            >
                                                                <div
                                                                    className="px-3 py-1 text-[10px] font-semibold tracking-wider text-slate-400 uppercase">
                                                                    Átsorolás ide:
                                                                </div>
                                                                {availableCategories.map((catKey) => {
                                                                    const targetCat = CATEGORY_MAP[catKey];
                                                                    return (
                                                                        <button
                                                                            key={catKey}
                                                                            onClick={(e) => handleCategoryChange(email.id, catKey, e)}
                                                                            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs text-slate-700 hover:bg-slate-100 transition-colors"
                                                                        >
                                                                            <span
                                                                                className={`h-2 w-2 rounded-full ${targetCat.color.split(" ")[0]}`}/>
                                                                            {targetCat.label}
                                                                        </button>
                                                                    );
                                                                })}
                                                            </div>
                                                        )}
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}