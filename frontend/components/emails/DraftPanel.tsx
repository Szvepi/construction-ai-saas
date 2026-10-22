"use client";

import {useEffect, useMemo, useState} from "react";
import {ApiError, apiFetch, apiFetchBlob} from "@/lib/api";
import type {AnalysisResponse, DraftLineItem, EmailDetail, GenerateDraftResponse} from "@/lib/types";
import {DraftActions, type DraftAction} from "@/components/emails/DraftActions";
import {DraftAnalysisPanel} from "@/components/emails/DraftAnalysisPanel";
import {QuoteLineItemsEditor} from "@/components/emails/QuoteLineItemsEditor";

type Props = {
    emailId: number;
    email?: EmailDetail | null;
    onSent: () => void;
    onDirtyChange?: (dirty: boolean) => void;
};

type DraftFields = {
    clientName: string;
    additionalNotes: string;
    includeAdditionalNotes: boolean;
    lineItems: DraftLineItem[];
};

function makeRow(name = "", unit = "m2", quantity = 1, unitPrice = 0): DraftLineItem {
    return {
        name,
        unit,
        quantity,
        unitPrice,
        subtotal: Math.round(quantity * unitPrice),
    };
}

function snapshot(fields: DraftFields): string {
    return JSON.stringify(fields);
}

function normalizeLineItems(items: DraftLineItem[] | null | undefined): DraftLineItem[] {
    return (items ?? []).map((item) => ({
        ...item,
        subtotal: Math.round(Number(item.quantity ?? 0) * Number(item.unitPrice ?? 0)),
    }));
}

export function DraftPanel({emailId, email, onSent, onDirtyChange}: Props) {
    const [draftId, setDraftId] = useState<number | null>(null);
    const [clientName, setClientName] = useState("");
    const [additionalNotes, setAdditionalNotes] = useState("");
    const [includeAdditionalNotes, setIncludeAdditionalNotes] = useState(true);
    const [vatRegistered, setVatRegistered] = useState(false);
    const [reviewWarnings, setReviewWarnings] = useState<string[]>([]);
    const [unmapped, setUnmapped] = useState<string[]>([]);
    const [lineItems, setLineItems] = useState<DraftLineItem[]>([]);
    const [body, setBody] = useState("");
    const [pdfUrl, setPdfUrl] = useState<string | null>(null);
    const [busyAction, setBusyAction] = useState<DraftAction>(null);
    const [error, setError] = useState<string | null>(null);
    const [saveSuccess, setSaveSuccess] = useState<string | null>(null);
    const [previewGenerated, setPreviewGenerated] = useState(false);
    const [savedSnapshot, setSavedSnapshot] = useState<string | null>(null);

    const currentSnapshot = useMemo(() => snapshot({
        clientName,
        additionalNotes,
        includeAdditionalNotes,
        lineItems,
    }), [clientName, additionalNotes, includeAdditionalNotes, lineItems]);
    const isDirty = savedSnapshot !== null && currentSnapshot !== savedSnapshot;

    useEffect(() => {
        onDirtyChange?.(isDirty);
    }, [isDirty, onDirtyChange]);

    useEffect(() => {
        let active = true;
        const currentEmail = email;

        if (currentEmail?.draftEmail) {
            const items = normalizeLineItems(currentEmail.draftEmail.lineItems);
            const initialFields: DraftFields = {
                clientName: currentEmail.draftEmail.clientName ?? "",
                additionalNotes: currentEmail.draftEmail.additionalNotes ?? "",
                includeAdditionalNotes: currentEmail.draftEmail.includeAdditionalNotes ?? true,
                lineItems: items,
            };
            setDraftId(currentEmail.draftEmail.draftId);
            setBody(currentEmail.draftEmail.draftBody);
            setPreviewGenerated(false);
            setPdfUrl(null);
            setClientName(initialFields.clientName);
            setAdditionalNotes(initialFields.additionalNotes);
            setIncludeAdditionalNotes(initialFields.includeAdditionalNotes);
            setVatRegistered(currentEmail.draftEmail.vatRegistered ?? false);
            setUnmapped(currentEmail.draftEmail.unmappedRequests ?? []);
            setReviewWarnings(currentEmail.draftEmail.reviewWarnings ?? []);
            setLineItems(items);
            setSavedSnapshot(snapshot(initialFields));
            if (currentEmail.draftEmail.draftBody) {
                apiFetchBlob(`/api/drafts/${currentEmail.draftEmail.draftId}/pdf`)
                    .then((blob) => {
                        if (!active) return;
                        setPdfUrl(URL.createObjectURL(blob));
                        setPreviewGenerated(true);
                    })
                    .catch((err) => {
                        if (!active) return;
                        if (err instanceof ApiError && err.status === 401) {
                            window.location.assign("/auth/login");
                            return;
                        }
                        setError(err instanceof ApiError ? err.message : "A PDF-előnézet betöltése sikertelen.");
                    });
            }
        } else {
            const emptyFields: DraftFields = {
                clientName: "",
                additionalNotes: "",
                includeAdditionalNotes: true,
                lineItems: [],
            };
            setDraftId(null);
            setBody("");
            setPdfUrl(null);
            setPreviewGenerated(false);
            setClientName(emptyFields.clientName);
            setAdditionalNotes(emptyFields.additionalNotes);
            setIncludeAdditionalNotes(emptyFields.includeAdditionalNotes);
            setVatRegistered(false);
            setReviewWarnings([]);
            setUnmapped([]);
            setLineItems([]);
            setSavedSnapshot(snapshot(emptyFields));
        }
        setError(null);
        setSaveSuccess(null);
        return () => {
            active = false;
        };
    }, [email]);

    useEffect(() => () => {
        if (pdfUrl) URL.revokeObjectURL(pdfUrl);
    }, [pdfUrl]);

    const total = useMemo(
        () => lineItems.reduce((sum, item) => sum + item.subtotal, 0),
        [lineItems],
    );
    const vatTotals = useMemo(() => lineItems.reduce((totals, item) => {
        const net = Math.round(item.subtotal / 1.27);
        totals.net += net;
        totals.vat += item.subtotal - net;
        return totals;
    }, {net: 0, vat: 0}), [lineItems]);

    const isQuoteRequest = email?.category === "QUOTE_REQUEST";

    async function analyze() {
        if (draftId && isDirty && !window.confirm("Az új AI-elemzés felülírhatja a jelenlegi módosításokat. Folytatod?")) {
            return;
        }
        setBusyAction("analyze");
        setError(null);
        setSaveSuccess(null);
        try {
            const data = await apiFetch<AnalysisResponse>(`/api/drafts/analyze/${emailId}`, {
                method: "POST",
                body: {additionalNotes},
            });
            const items = normalizeLineItems(data.lineItems);
            const fields: DraftFields = {
                clientName: data.clientName ?? "",
                additionalNotes: data.additionalNotes ?? "",
                includeAdditionalNotes: data.includeAdditionalNotes ?? true,
                lineItems: items,
            };
            setDraftId(data.draftId);
            setClientName(fields.clientName);
            setAdditionalNotes(fields.additionalNotes);
            setVatRegistered(data.vatRegistered ?? false);
            setReviewWarnings(data.reviewWarnings ?? []);
            setUnmapped(data.unmappedRequests ?? []);
            setLineItems(items);
            setBody("");
            setPdfUrl(null);
            setPreviewGenerated(false);
            setSavedSnapshot(snapshot(fields));
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az elemzés sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    function buildPayload() {
        return {
            clientName: clientName || email?.fromAddress || "Érdeklődő",
            additionalNotes,
            includeAdditionalNotes,
            lineItems: lineItems.map((item) => ({
                ...item,
                quantity: Number(item.quantity || 0),
                unitPrice: Number(item.unitPrice || 0),
                subtotal: Math.round(Number(item.quantity || 0) * Number(item.unitPrice || 0)),
            })),
        };
    }

    function applySavedDraft(data: GenerateDraftResponse) {
        const fields: DraftFields = {
            clientName: data.clientName ?? "",
            additionalNotes: data.additionalNotes ?? "",
            includeAdditionalNotes: data.includeAdditionalNotes ?? true,
            lineItems: normalizeLineItems(data.lineItems),
        };
        setClientName(fields.clientName);
        setAdditionalNotes(fields.additionalNotes);
        setIncludeAdditionalNotes(fields.includeAdditionalNotes);
        setVatRegistered(data.vatRegistered ?? false);
        setLineItems(fields.lineItems);
        setSavedSnapshot(snapshot(fields));
    }

    async function save() {
        if (!draftId) return;
        const payload = buildPayload();
        setBusyAction("save");
        setError(null);
        setSaveSuccess(null);

        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/save`, {
                method: "POST",
                body: payload,
            });
            applySavedDraft(data);
            setSaveSuccess("A módosítások mentésre kerültek.");
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A mentés sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    function updateLineItem(index: number, field: keyof DraftLineItem, value: string | number) {
        setPreviewGenerated(false);
        setLineItems((current) => {
            const next = [...current];
            const row = {...next[index]};

            if (field === "quantity" || field === "unitPrice" || field === "subtotal") {
                const numericValue = Number(value) || 0;
                row[field] = numericValue as never;
            } else {
                row[field] = String(value) as never;
            }

            if (field === "quantity" || field === "unitPrice") {
                row.subtotal = Math.round(Number(row.quantity ?? 0) * Number(row.unitPrice ?? 0));
            }

            next[index] = row;
            return next;
        });
    }

    function addCustomRow(name = "Egyedi tétel", unit = "db") {
        setPreviewGenerated(false);
        setLineItems((current) => [...current, makeRow(name, unit, 1, 0)]);
    }

    function removeLineItem(index: number) {
        setPreviewGenerated(false);
        setLineItems((current) => current.filter((_, i) => i !== index));
    }

    async function preview() {
        if (!draftId) return;
        const payload = buildPayload();
        setBusyAction("preview");
        setError(null);
        setSaveSuccess(null);

        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/preview`, {
                method: "POST",
                body: payload,
            });
            setBody(data.draftBody ?? "");
            applySavedDraft(data);
            const pdf = await apiFetchBlob(`/api/drafts/${draftId}/pdf`);
            setPdfUrl(URL.createObjectURL(pdf));
            setPreviewGenerated(true);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az előnézet generálása sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    async function createGmailDraft() {
        if (!draftId) return;
        if (isDirty && !window.confirm("A Gmail-piszkozat készítése elmenti az aktuális ajánlatot. Folytatod?")) {
            return;
        }
        const payload = buildPayload();
        setBusyAction("finalize");
        setError(null);
        setSaveSuccess(null);

        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/finalize`, {
                method: "POST",
                body: payload,
            });
            applySavedDraft(data);
            setSaveSuccess("A Gmail-piszkozat elkészült. Ellenőrizd a Gmailben, mielőtt elküldöd.");
            onSent();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A Gmail-piszkozat létrehozása sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    if (!isQuoteRequest) {
        return (
            <section className="rounded-2xl border border-amber-200 bg-amber-50 p-5">
                <p className="text-sm font-medium text-amber-900">
                    Az AI-ajánlatkészítés csak ajánlatkérés kategóriájú levelekhez érhető el.
                </p>
            </section>
        );
    }

    return (
        <section className="min-w-0 space-y-4">
            {error && (
                <div role="alert" className="flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
                    <span className="mt-0.5 font-bold" aria-hidden="true">!</span>
                    <p className="min-w-0 break-words">{error}</p>
                </div>
            )}

            {busyAction && (
                <p role="status" className="rounded-xl border border-brand-100 bg-brand-50 px-4 py-2.5 text-sm font-medium text-brand-800">
                    {busyAction === "analyze" && "Az AI elemzi a levelet és összeállítja a javasolt tételeket…"}
                    {busyAction === "save" && "Az ajánlat módosításainak mentése folyamatban…"}
                    {busyAction === "preview" && "A PDF-előnézet készítése folyamatban…"}
                    {busyAction === "finalize" && "A Gmail-piszkozat készítése folyamatban…"}
                </p>
            )}

            <DraftAnalysisPanel
                emailId={emailId}
                draftId={draftId}
                clientName={clientName}
                additionalNotes={additionalNotes}
                includeAdditionalNotes={includeAdditionalNotes}
                reviewWarnings={reviewWarnings}
                unmapped={unmapped}
                busy={busyAction !== null}
                onAnalyze={analyze}
                onClientNameChange={(value) => {
                    setPreviewGenerated(false);
                    setClientName(value);
                }}
                onAdditionalNotesChange={(value) => {
                    setPreviewGenerated(false);
                    setAdditionalNotes(value);
                }}
                onIncludeAdditionalNotesChange={(value) => {
                    setPreviewGenerated(false);
                    setIncludeAdditionalNotes(value);
                }}
                onAddUnmapped={(value) => addCustomRow(value, "db")}
            />

            {draftId && (
                <>
                    <QuoteLineItemsEditor
                        lineItems={lineItems}
                        vatRegistered={vatRegistered}
                        busy={busyAction !== null}
                        onUpdate={updateLineItem}
                        onAdd={() => addCustomRow()}
                        onRemove={removeLineItem}
                    />
                    <DraftActions
                        draftId={draftId}
                        lineItemCount={lineItems.length}
                        busyAction={busyAction}
                        isDirty={isDirty}
                        saveSuccess={saveSuccess}
                        total={total}
                        net={vatTotals.net}
                        vat={vatTotals.vat}
                        vatRegistered={vatRegistered}
                        onSave={save}
                        onPreview={preview}
                        onCreateGmailDraft={createGmailDraft}
                    />
                </>
            )}

            {body && previewGenerated && pdfUrl && (
                <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
                    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-3 sm:px-5">
                        <div>
                            <h2 className="text-sm font-semibold text-slate-900">PDF árajánlat előnézete</h2>
                            <p className="mt-0.5 text-xs text-slate-500">A megnyitás és letöltés a jelenlegi PDF alapján történik.</p>
                        </div>
                        <a
                            href={pdfUrl}
                            download={`arajanlat-${draftId}.pdf`}
                            className="inline-flex h-9 items-center justify-center rounded-lg border border-slate-200 px-3 text-sm font-semibold text-slate-700 hover:bg-slate-50"
                        >
                            PDF letöltése
                        </a>
                    </div>
                    <iframe
                        title="Árajánlat PDF-előnézete"
                        src={pdfUrl}
                        className="h-[min(75vh,760px)] min-h-[420px] w-full bg-slate-100"
                    />
                </section>
            )}
        </section>
    );
}
