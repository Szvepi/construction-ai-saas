"use client";

import Link from "next/link";
import {useMemo, useState} from "react";
import type {EmailSummary} from "@/lib/types";
import {apiFetch} from "@/lib/api";

type Props = { emails: EmailSummary[]; onEmailUpdated?: (id: number) => void };

type CategoryFilter = "ALL" | "QUOTE_REQUEST" | "SPAM" | "OTHER";

function getCategoryBadge(category: EmailSummary["category"]) {
    const badges = {
        QUOTE_REQUEST: {label: "Árajánlat kérés", color: "bg-blue-100 text-blue-800"},
        SPAM: {label: "Spam", color: "bg-red-100 text-red-800"},
        OTHER: {label: "Egyéb", color: "bg-gray-100 text-gray-800"},
    };
    const badge = badges[category];
    return {label: badge.label, color: badge.color};
}

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

    function getAvailableCategories(currentCategory: EmailSummary["category"]) {
        // Only allow changing from QUOTE_REQUEST or SPAM
        if (currentCategory === "OTHER") return ["SPAM", "QUOTE_REQUEST"];
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
        <div className="space-y-4">
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

            {filteredEmails.length === 0 ? (
                <p className="py-8 text-center text-slate-500">Nincsenek e-mailek ebben a kategóriában.</p>
            ) : (
                <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white">
                    {filteredEmails.map((email) => {
                        const badge = getCategoryBadge(email.category);
                        const availableCategories = getAvailableCategories(email.category);
                        const hasOptions = availableCategories.length > 0;

                        return (
                            <li key={email.id}>
                                <div className="relative">
                                    <Link
                                        href={`/emails/${email.id}`}
                                        className="block px-4 py-3 hover:bg-slate-50"
                                    >
                                        <div className="flex items-start justify-between gap-2">
                                            <div className="min-w-0 flex-1">
                                                <div className="flex items-center justify-between gap-2">
                                                    <span className="font-medium text-slate-900 line-clamp-1">
                                                        {email.subject || "(nincs tárgy)"}
                                                    </span>
                                                    <span className="shrink-0 text-xs text-slate-500">
                                                        {formatDate(email.receivedAt)}
                                                    </span>
                                                </div>
                                                <div className="mt-1 flex flex-wrap gap-2 items-center">
                                                    <span
                                                        className={`inline-block rounded px-2 py-0.5 text-xs ${badge.color}`}>
                                                        {badge.label}
                                                    </span>
                                                    {email.replied && (
                                                        <span
                                                            className="inline-block rounded bg-green-100 px-2 py-0.5 text-xs text-green-800">
                                                            Válaszolt
                                                        </span>
                                                    )}
                                                    {hasOptions && (
                                                        <button
                                                            onClick={(e) => {
                                                                e.preventDefault();
                                                                e.stopPropagation();
                                                                setOpenDropdown(openDropdown === email.id ? null : email.id);
                                                            }}
                                                            className="ml-auto text-xs text-slate-500 hover:text-slate-700 p-1"
                                                            title="Átsorolás"
                                                        >
                                                            ⋯
                                                        </button>
                                                    )}
                                                </div>
                                            </div>
                                        </div>
                                        <p className="mt-1 text-sm text-slate-600">{email.fromAddress}</p>
                                    </Link>

                                    {hasOptions && openDropdown === email.id && (
                                        <div
                                            className="absolute right-4 top-12 z-10 bg-white border border-slate-200 rounded-lg shadow-md"
                                            onClick={(e) => e.preventDefault()}
                                        >
                                            <div className="py-1">
                                                {availableCategories.map((cat) => {
                                                    const catBadge =
                                                        cat === "QUOTE_REQUEST"
                                                            ? {label: "Árajánlat kérés", emoji: "→"}
                                                            : {label: "Spam", emoji: "→"};
                                                    return (
                                                        <button
                                                            key={cat}
                                                            onClick={(e) => handleCategoryChange(email.id, cat, e)}
                                                            className="block w-full text-left px-4 py-2 text-sm text-slate-700 hover:bg-slate-100"
                                                        >
                                                            {catBadge.emoji} {catBadge.label}
                                                        </button>
                                                    );
                                                })}
                                            </div>
                                        </div>
                                    )}
                                </div>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}
