"use client";

import {useEffect, useMemo, useState} from "react";
import {ApiError, apiFetch, apiFetchBlob} from "@/lib/api";
import type {AnalysisResponse, DraftLineItem, EmailDetail, GenerateDraftResponse} from "@/lib/types";
import {Button} from "@/components/ui/Button";

type Props = {
    emailId: number;
    email?: EmailDetail | null;
    onSent: () => void;
};

const currencyFormatter = new Intl.NumberFormat("hu-HU", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
});

function makeRow(name = "", unit = "m2", quantity = 1, unitPrice = 0): DraftLineItem {
    return {
        name,
        unit,
        quantity,
        unitPrice,
        subtotal: Math.round(quantity * unitPrice),
    };
}

function capitalizeFirstLetter(value: string): string {
    return value.charAt(0).toLocaleUpperCase("hu-HU") + value.slice(1);
}

export function DraftPanel({emailId, email, onSent}: Props) {
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
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [saveSuccess, setSaveSuccess] = useState<string | null>(null);
    const [previewGenerated, setPreviewGenerated] = useState(false);

    useEffect(() => {
        let active = true;

        if (email?.draftEmail) {
            setDraftId(email.draftEmail.draftId);
            setBody(email.draftEmail.draftBody);
            setPreviewGenerated(false);
            setPdfUrl(null);
            setClientName(email.draftEmail.clientName ?? "");
            setAdditionalNotes(email.draftEmail.additionalNotes ?? "");
            setIncludeAdditionalNotes(email.draftEmail.includeAdditionalNotes ?? true);
            setVatRegistered(email.draftEmail.vatRegistered ?? false);
            setUnmapped(email.draftEmail.unmappedRequests ?? []);
            setReviewWarnings(email.draftEmail.reviewWarnings ?? []);
            setLineItems((email.draftEmail.lineItems ?? []).map((item) => ({
                ...item,
                subtotal: Math.round(Number(item.quantity ?? 0) * Number(item.unitPrice ?? 0)),
            })));
            if (email.draftEmail.draftBody) {
                apiFetchBlob(`/api/drafts/${email.draftEmail.draftId}/pdf`)
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
            setDraftId(null);
            setBody("");
            setPdfUrl(null);
            setPreviewGenerated(false);
            setClientName("");
            setAdditionalNotes("");
            setIncludeAdditionalNotes(true);
            setVatRegistered(false);
            setReviewWarnings([]);
            setUnmapped([]);
            setLineItems([]);
        }
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
        setLoading(true);
        setError(null);
        setSaveSuccess(null);
        try {
            const data = await apiFetch<AnalysisResponse>(`/api/drafts/analyze/${emailId}`, {
                method: "POST",
                body: {additionalNotes},
            });
            setDraftId(data.draftId);
            setClientName(data.clientName ?? "");
            setAdditionalNotes(data.additionalNotes ?? "");
            setVatRegistered(data.vatRegistered ?? false);
            setReviewWarnings(data.reviewWarnings ?? []);
            setUnmapped(data.unmappedRequests ?? []);
            setLineItems((data.lineItems ?? []).map((item) => ({
                ...item,
                subtotal: Math.round(Number(item.quantity ?? 0) * Number(item.unitPrice ?? 0)),
            })));
            setBody("");
            setPdfUrl(null);
            setPreviewGenerated(false);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az elemzés sikertelen");
        } finally {
            setLoading(false);
        }
    }

    async function save() {
        if (!draftId) return;
        setLoading(true);
        setError(null);
        setSaveSuccess(null);

        try {
            const payload = {
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

            await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/save`, {
                method: "POST",
                body: payload,
            });

            setSaveSuccess("A módosítások mentésre kerültek!");
            setTimeout(() => setSaveSuccess(null), 3000);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A mentés sikertelen");
        } finally {
            setLoading(false);
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

    function addCustomRow() {
        setPreviewGenerated(false);
        setLineItems((current) => [...current, makeRow("Egyedi tétel", "db", 1, 0)]);
    }

    function removeLineItem(index: number) {
        setPreviewGenerated(false);
        setLineItems((current) => current.filter((_, i) => i !== index));
    }

    async function preview() {
        if (!draftId) return;
        setLoading(true);
        setError(null);
        setSaveSuccess(null);

        try {
            const payload = {
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

            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/preview`, {
                method: "POST",
                body: payload,
            });

            setBody(data.draftBody ?? "");
            const pdf = await apiFetchBlob(`/api/drafts/${draftId}/pdf`);
            setPdfUrl(URL.createObjectURL(pdf));
            setPreviewGenerated(true);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az előnézet generálása sikertelen");
        } finally {
            setLoading(false);
        }
    }

    async function finalize() {
        if (!draftId) return;
        setLoading(true);
        setError(null);

        try {
            const payload = {
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

            await apiFetch(`/api/drafts/${draftId}/finalize`, {
                method: "POST",
                body: payload,
            });

            setSaveSuccess("A Gmail-piszkozat elkészült. Ellenőrizd és küldd el Gmailben.");
            onSent();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A küldés sikertelen");
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
            <div className="flex items-center justify-between gap-3">
                <h3 className="font-semibold text-slate-900">Ajánlat előkészítése</h3>
                <Button onClick={analyze} disabled={loading}>
                    {loading ? "Elemzés…" : draftId ? "Újraelemzés a megjegyzésekkel" : "AI elemzés"}
                </Button>
            </div>

            <div className="mt-4">
                <label htmlFor="additional-notes" className="mb-2 block text-sm font-medium text-slate-700">
                    Kiegészítés telefonos vagy helyszíni egyeztetés alapján
                </label>
                <textarea
                    id="additional-notes"
                    rows={4}
                    value={additionalNotes}
                    onChange={(event) => {
                        setPreviewGenerated(false);
                        setAdditionalNotes(event.target.value);
                    }}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-800 focus:border-brand-500 focus:outline-none"
                    placeholder="Pl. telefonon egyeztettük, hogy a régi burkolat bontását is kérik; a terület 35 m²."
                />
                <p className="mt-1 text-xs text-slate-500">
                    Az AI az elemzéskor figyelembe veszi ezt a szöveget.
                </p>
            </div>

            {draftId && (
                <>
                    <div className="mt-3 flex items-start gap-2">
                        <input
                            id="include-additional-notes"
                            type="checkbox"
                            checked={includeAdditionalNotes}
                            onChange={(event) => {
                                setPreviewGenerated(false);
                                setIncludeAdditionalNotes(event.target.checked);
                            }}
                            className="mt-1 h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                        />
                        <label htmlFor="include-additional-notes" className="text-sm text-slate-700">
                            Jelenjen meg ez a kiegészítés az árajánlatban
                        </label>
                    </div>

                    {reviewWarnings.length > 0 && (
                        <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3">
                            <h4 className="text-sm font-semibold text-amber-900">⚠️ AI Figyelmeztetések</h4>
                            <ul className="mt-2 space-y-2 text-sm text-amber-900">
                                {reviewWarnings.map((warning, index) => (
                                    <li key={`${warning}-${index}`} className="rounded bg-amber-100/80 p-2">
                                        {warning}
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}

                    {unmapped.length > 0 && (
                        <div className="mt-4 rounded-lg border border-yellow-200 bg-yellow-50 p-3">
                            <h4 className="text-sm font-semibold text-yellow-900">Nincs a katalógusban</h4>
                            <ul className="mt-2 space-y-2 text-sm text-yellow-900">
                                {unmapped.map((item, index) => (
                                    <li key={`${item}-${index}`} className="flex items-center justify-between gap-2">
                                        <span>{capitalizeFirstLetter(item)}</span>
                                        <Button
                                            variant="secondary"
                                            className="!px-3 !py-1.5"
                                            onClick={() => {
                                                setPreviewGenerated(false);
                                                setLineItems((current) => [
                                                    ...current,
                                                    makeRow(capitalizeFirstLetter(item), "db", 1, 0),
                                                ]);
                                            }}
                                        >
                                            Hozzáadás
                                        </Button>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}

                    <div className="mt-4">
                        <div className="mb-2 flex items-center justify-between gap-3">
                            <label className="text-sm font-medium text-slate-700">Ügyfél neve</label>
                        </div>
                        <input
                            value={clientName}
                            onChange={(event) => {
                                setPreviewGenerated(false);
                                setClientName(event.target.value);
                            }}
                            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-800 focus:border-brand-500 focus:outline-none"
                            placeholder="Ügyfél neve"
                        />
                    </div>

                    <div className="mt-4 overflow-x-auto">
                        <table className="min-w-full border-separate border-spacing-y-2 text-sm">
                            <thead>
                            <tr className="text-left text-slate-600">
                                <th className="px-2 py-1">Név</th>
                                <th className="px-2 py-1">Mennyiség</th>
                                <th className="px-2 py-1">Egység</th>
                                <th className="px-2 py-1">{vatRegistered ? "Bruttó egységár" : "Egységár"}</th>
                                <th className="px-2 py-1">{vatRegistered ? "Bruttó összeg" : "Fizetendő"}</th>
                                <th className="px-2 py-1"/>
                            </tr>
                            </thead>
                            <tbody>
                            {lineItems.length === 0 ? (
                                <tr>
                                    <td colSpan={6} className="px-2 py-4 text-center text-slate-500">
                                        Nincs tétel az elemzéshez.
                                    </td>
                                </tr>
                            ) : (
                                lineItems.map((item, index) => (
                                    <tr key={index} className="rounded-lg bg-slate-50 align-top">
                                        <td className="rounded-l-lg px-2 py-2">
                                            <input
                                                value={item.name}
                                                onChange={(event) => updateLineItem(index, "name", event.target.value)}
                                                className="w-full rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                                            />
                                        </td>
                                        <td className="px-2 py-2">
                                            <input
                                                type="number"
                                                min="0"
                                                step="0.01"
                                                value={item.quantity}
                                                onChange={(event) => updateLineItem(index, "quantity", event.target.value)}
                                                className="w-24 rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                                            />
                                        </td>
                                        <td className="px-2 py-2">
                                            <input
                                                value={item.unit}
                                                onChange={(event) => updateLineItem(index, "unit", event.target.value)}
                                                className="w-20 rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                                            />
                                        </td>
                                        <td className="px-2 py-2">
                                            <input
                                                type="number"
                                                min="0"
                                                step="100"
                                                value={item.unitPrice}
                                                onChange={(event) => updateLineItem(index, "unitPrice", event.target.value)}
                                                className="w-28 rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                                            />
                                        </td>
                                        <td className="px-2 py-2 text-right font-medium text-slate-700">
                                            {currencyFormatter.format(item.subtotal)} Ft
                                        </td>
                                        <td className="rounded-r-lg px-2 py-2 text-right">
                                            <button
                                                type="button"
                                                className="text-sm text-red-600 hover:text-red-700"
                                                onClick={() => removeLineItem(index)}
                                            >
                                                Töröl
                                            </button>
                                        </td>
                                    </tr>
                                ))
                            )}
                            </tbody>
                        </table>
                    </div>

                    <div className="mt-4 flex items-center justify-between rounded-lg bg-slate-100 px-3 py-2">
                        {vatRegistered ? (
                            <div className="ml-auto text-right text-sm text-slate-600">
                                <p>Nettó összesen: {currencyFormatter.format(vatTotals.net)} Ft</p>
                                <p>ÁFA (27%): {currencyFormatter.format(vatTotals.vat)} Ft</p>
                                <p className="text-lg font-semibold text-slate-900">
                                    Bruttó fizetendő: {currencyFormatter.format(total)} Ft
                                </p>
                            </div>
                        ) : (
                            <>
                                <span className="text-sm text-slate-600">Fizetendő összesen</span>
                                <span className="text-lg font-semibold text-slate-900">
                                    {currencyFormatter.format(total)} Ft
                                </span>
                            </>
                        )}
                    </div>

                    <div className="mt-5 flex gap-2">
                        <Button variant="secondary" onClick={addCustomRow}>
                            + Új tétel
                        </Button>
                        <Button onClick={save} disabled={loading} variant="secondary">
                            {loading ? "Mentés…" : "Mentés"}
                        </Button>
                        {!previewGenerated ? (
                            <Button onClick={preview} disabled={loading || lineItems.length === 0}>
                                {loading ? "Előnézet generálása…" : "Előnézet generálása"}
                            </Button>
                        ) : (
                            <Button onClick={preview} disabled={loading} variant="secondary">
                                {loading ? "Újra generálása…" : "Újra generálása"}
                            </Button>
                        )}
                    </div>
                </>
            )}

            {body && previewGenerated && pdfUrl && (
                <div className="mt-6">
                    <div className="mb-3 flex items-center justify-between gap-3">
                        <h4 className="text-sm font-semibold text-slate-900">PDF árajánlat előnézete</h4>
                        <a
                            href={pdfUrl}
                            download={`arajanlat-${draftId}.pdf`}
                            className="text-sm font-medium text-blue-700 hover:text-blue-900"
                        >
                            PDF letöltése
                        </a>
                    </div>
                    <iframe
                        title="Árajánlat PDF-előnézet"
                        src={pdfUrl}
                        className="h-[700px] w-full rounded-lg border border-slate-200 bg-slate-100"
                    />

                    <div className="mt-4 flex gap-2">
                        <Button onClick={finalize} disabled={loading}>
                            {loading ? "Gmail-piszkozat készítése…" : "Gmail-piszkozat készítése PDF-melléklettel"}
                        </Button>
                    </div>
                    <p className="mt-2 text-xs text-slate-500">
                        A Gmailben egy rövid kísérőszövegű piszkozat nyílik meg a PDF-melléklettel; elküldés előtt még
                        ellenőrizheted.
                    </p>
                </div>
            )}

            {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
            {saveSuccess && <p className="mt-3 text-sm text-green-600">✓ {saveSuccess}</p>}
        </section>
    );
}
