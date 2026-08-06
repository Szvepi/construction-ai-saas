"use client";

import {useState, useEffect} from "react";
import {ApiError, apiFetch} from "@/lib/api";
import type {EmailDetail, GenerateDraftResponse} from "@/lib/types";
import {Button} from "@/components/ui/Button";

type Props = {
    emailId: number;
    email?: EmailDetail | null;
    onSent: () => void;
};

export function DraftPanel({emailId, email, onSent}: Props) {
    const [draftId, setDraftId] = useState<number | null>(null);
    const [body, setBody] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [activeTab, setActiveTab] = useState<"preview" | "edit">("preview");

    // Ha a szerveroldalon már létezik draft (korábban generált), jelenítsük meg
    useEffect(() => {
        if (email?.draftEmail) {
            setDraftId(email.draftEmail.draftId);
            setBody(email.draftEmail.draftBody);
            setActiveTab("preview");
        }
    }, [email]);

    const isQuoteRequest = email?.category === "QUOTE_REQUEST";

    async function generate() {
        setLoading(true);
        setError(null);
        try {
            const data = await apiFetch<GenerateDraftResponse>(
                `/api/emails/${emailId}/drafts/generate`,
                {method: "POST"},
            );
            setDraftId(data.draftId);
            setBody(data.draftBody);
            setActiveTab("preview"); // Generálás után egyből az előnézetet mutatjuk
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A generálás sikertelen");
        } finally {
            setLoading(false);
        }
    }

    async function send() {
        if (!draftId) return;
        setLoading(true);
        setError(null);
        try {
            await apiFetch(`/api/emails/${emailId}/drafts/${draftId}/send`, {
                method: "POST",
                body: {draftBody: body},
            });
            onSent();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Küldés sikertelen");
        } finally {
            setLoading(false);
        }
    }

    if (!isQuoteRequest) {
        return (
            <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-4">
                <p className="text-sm text-amber-800">
                    Az AI válasz generálása csak árajánlat kérésekhez érhető el.
                </p>
            </section>
        );
    }

    return (
        <section className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
            <h3 className="font-semibold text-slate-900">Válaszvázlat</h3>

            {!draftId ? (
                <Button className="mt-3" onClick={generate} disabled={loading}>
                    {loading ? "Generálás…" : "Válasz generálása"}
                </Button>
            ) : (
                <>
                    {/* Tab Váltó (Előnézet / Szerkesztés) */}
                    <div className="mt-3 flex border-b border-slate-200 text-xs font-medium">
                        <button
                            type="button"
                            onClick={() => setActiveTab("preview")}
                            className={`px-3 py-2 border-b-2 transition-colors ${
                                activeTab === "preview"
                                    ? "border-blue-600 text-blue-600"
                                    : "border-transparent text-slate-500 hover:text-slate-700"
                            }`}
                        >
                            Előnézet (Formázott)
                        </button>
                        <button
                            type="button"
                            onClick={() => setActiveTab("edit")}
                            className={`px-3 py-2 border-b-2 transition-colors ${
                                activeTab === "edit"
                                    ? "border-blue-600 text-blue-600"
                                    : "border-transparent text-slate-500 hover:text-slate-700"
                            }`}
                        >
                            HTML szerkesztése
                        </button>
                    </div>

                    {/* Tartalom megjelenítése a kiválasztott Tab alapján */}
                    {activeTab === "preview" ? (
                        <div
                            className="mt-3 min-h-[160px] w-full rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-800 prose prose-sm max-w-none"
                            dangerouslySetInnerHTML={{__html: body || "<p className='text-slate-400'>Üres tartalom...</p>"}}
                        />
                    ) : (
                        <textarea
                            className="mt-3 min-h-[160px] w-full rounded-lg border border-slate-300 p-3 text-sm font-mono text-slate-800 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                            value={body}
                            onChange={(e) => setBody(e.target.value)}
                            placeholder="HTML válasz törzse..."
                        />
                    )}

                    <div className="mt-4 flex gap-2">
                        <Button onClick={send} disabled={loading || !body.trim()}>
                            {loading ? "Küldés…" : "Válasz elküldése"}
                        </Button>
                    </div>
                </>
            )}

            {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
        </section>
    );
}