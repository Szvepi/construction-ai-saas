import {useEffect, useState} from "react";
import type {CoverageStatus, CoverageWarning, CoverageWorkItem, DraftLineItem} from "@/lib/types";
import {Button} from "@/components/ui/Button";

type Props = {
    emailId: number;
    draftId: number | null;
    clientName: string;
    additionalNotes: string;
    includeAdditionalNotes: boolean;
    reviewWarnings: string[];
    coverageItems: CoverageWorkItem[];
    coverageWarnings: CoverageWarning[];
    lineItems: DraftLineItem[];
    busy: boolean;
    analysisFresh: boolean;
    onAnalyze: () => void;
    onClientNameChange: (value: string) => void;
    onAdditionalNotesChange: (value: string) => void;
    onIncludeAdditionalNotesChange: (value: boolean) => void;
    onAddWork: (work: CoverageWorkItem) => void;
    onSetWorkDecision: (workId: string, status: CoverageStatus, lineItemId?: string) => void;
    onAcknowledgeWarning: (warningId: string, acknowledged: boolean) => void;
};

function capitalizeFirstLetter(value: string): string {
    return value.charAt(0).toLocaleUpperCase("hu-HU") + value.slice(1);
}

export function DraftAnalysisPanel({
    emailId,
    draftId,
    clientName,
    additionalNotes,
    includeAdditionalNotes,
    reviewWarnings,
    coverageItems,
    coverageWarnings,
    lineItems,
    busy,
    analysisFresh,
    onAnalyze,
    onClientNameChange,
    onAdditionalNotesChange,
    onIncludeAdditionalNotesChange,
    onAddWork,
    onSetWorkDecision,
    onAcknowledgeWarning,
}: Props) {
    const [selectedLineItem, setSelectedLineItem] = useState<Record<string, string>>({});
    const notesId = `additional-notes-${emailId}`;
    const includeNotesId = `include-additional-notes-${emailId}`;
    const clientNameId = `client-name-${emailId}`;

    useEffect(() => {
        setSelectedLineItem({});
    }, [draftId]);

    return (
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/[0.02] sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-brand-700">AI-elemzés</p>
                    <h2 className="mt-1 text-lg font-semibold text-slate-900">
                        {draftId ? "Ajánlat előkészítése" : "Ajánlatkérés elemzése"}
                    </h2>
                    <p className="mt-1 text-sm text-slate-500">
                        Az AI a levél és az árjegyzék alapján javasol tételeket.
                    </p>
                </div>
                <Button onClick={onAnalyze} disabled={busy}>
                    {busy ? "Elemzés folyamatban…" : draftId ? "Újraelemzés" : "AI-elemzés indítása"}
                </Button>
            </div>

            <div className="mt-5 grid gap-4 sm:grid-cols-2">
                <div className="sm:col-span-2">
                    <label htmlFor={notesId} className="mb-2 block text-sm font-medium text-slate-700">
                        Kiegészítés telefonos vagy helyszíni egyeztetés alapján
                    </label>
                    <textarea
                        id={notesId}
                        rows={3}
                        value={additionalNotes}
                        onChange={(event) => onAdditionalNotesChange(event.target.value)}
                        disabled={busy}
                        className="w-full resize-y rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 outline-none transition placeholder:text-slate-400 focus:border-brand-300 focus:ring-4 focus:ring-brand-500/10 disabled:bg-slate-50"
                        placeholder="Például: telefonon egyeztettük a bontást is, a terület 35 m²."
                    />
                    <p className="mt-1.5 text-xs text-slate-500">
                        Az AI az elemzéskor figyelembe veszi ezt a szöveget.
                    </p>
                </div>

                {draftId && (
                    <>
                        <div>
                            <label htmlFor={clientNameId} className="mb-2 block text-sm font-medium text-slate-700">
                                Ügyfél neve
                            </label>
                            <input
                                id={clientNameId}
                                value={clientName}
                                onChange={(event) => onClientNameChange(event.target.value)}
                                disabled={busy}
                                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 outline-none transition focus:border-brand-300 focus:ring-4 focus:ring-brand-500/10 disabled:bg-slate-50"
                                placeholder="Ügyfél neve"
                            />
                        </div>
                        <div className="flex items-end">
                            <label htmlFor={includeNotesId} className="flex items-start gap-2.5 text-sm text-slate-700">
                                <input
                                    id={includeNotesId}
                                    type="checkbox"
                                    checked={includeAdditionalNotes}
                                    onChange={(event) => onIncludeAdditionalNotesChange(event.target.checked)}
                                    disabled={busy}
                                    className="mt-0.5 h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                                />
                                <span>Jelenjen meg a kiegészítés az ajánlatban</span>
                            </label>
                        </div>
                    </>
                )}
            </div>

            {draftId && (!analysisFresh || coverageItems.length > 0 || coverageWarnings.length > 0 || reviewWarnings.length > 0) && (
                <section id="quote-coverage" className="mt-5 scroll-mt-24 rounded-xl border border-amber-200 bg-amber-50/70 p-4">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                        <div>
                            <h3 className="text-sm font-semibold text-amber-950">Ajánlat teljességének ellenőrzése</h3>
                            <p className="mt-1 text-xs text-amber-900">
                                Az AI javaslatokat ad; a lefedettség végső megítélése a tiéd.
                            </p>
                        </div>
                        {!analysisFresh && (
                            <div className="rounded-lg border border-amber-300 bg-white px-3 py-2 text-xs font-semibold text-amber-900">
                                A kiegészítő információk változtak. Indíts újraelemzést az ellenőrzés frissítéséhez.
                            </div>
                        )}
                    </div>

                    {coverageItems.length > 0 && (
                        <ul className="mt-3 space-y-3">
                            {coverageItems.map((work) => {
                                const statusLabel = work.status === "INCLUDED"
                                    ? work.userConfirmed ? "Jóváhagyva – szerepel az ajánlatban" : "AI-illesztés – ellenőrizd"
                                    : work.status === "MISSING"
                                        ? "Hiányzik"
                                        : work.status === "EXCLUDED"
                                            ? "Tudatosan kihagyva"
                                            : "Ellenőrzést igényel";
                                const preferredLineId = selectedLineItem[work.workId];
                                const selected = preferredLineId && lineItems.some((item) => item.lineItemId === preferredLineId)
                                    ? preferredLineId
                                    : work.linkedLineItemId ?? "";
                                return (
                                    <li key={work.workId} className="rounded-lg border border-amber-100 bg-white p-3">
                                        <div className="flex flex-wrap items-start justify-between gap-2">
                                            <div className="min-w-0">
                                                <p className="break-words text-sm font-semibold text-slate-900">{capitalizeFirstLetter(work.name)}</p>
                                                <p className="mt-1 text-sm text-slate-600">{work.explanation}</p>
                                                <p className="mt-1 text-xs text-slate-500">
                                                    Forrás: {work.source === "EMAIL" ? "eredeti e-mail" : work.source === "ADDITIONAL_INFO" ? "kiegészítő információ" : "AI-elemzés (a forrás nem különíthető el)"}
                                                    {work.quantity !== null && work.quantity > 0 && work.unit
                                                        ? ` · Felismert mennyiség: ${work.quantity} ${work.unit}`
                                                        : ""}
                                                </p>
                                            </div>
                                            <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ${
                                                work.status === "INCLUDED"
                                                    ? "bg-emerald-50 text-emerald-800"
                                                    : work.status === "EXCLUDED"
                                                        ? "bg-slate-100 text-slate-700"
                                                        : "bg-amber-100 text-amber-900"
                                            }`}>
                                                {statusLabel}
                                            </span>
                                        </div>
                                        {work.status !== "EXCLUDED" && (
                                            <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                                                {work.status === "INCLUDED" && !work.userConfirmed && selected && (
                                                    <Button
                                                        type="button"
                                                        variant="secondary"
                                                        disabled={busy}
                                                        onClick={() => onSetWorkDecision(work.workId, "INCLUDED", selected)}
                                                    >
                                                        Megerősítem a lefedettséget
                                                    </Button>
                                                )}
                                                <Button type="button" variant="secondary" disabled={busy} onClick={() => onAddWork(work)}>
                                                    Hozzáadás az ajánlathoz
                                                </Button>
                                                {lineItems.length > 0 && (
                                                    <>
                                                        <label className="sr-only" htmlFor={`coverage-line-${work.workId}`}>
                                                            Melyik ajánlati sor tartalmazza: {work.name}
                                                        </label>
                                                        <select
                                                            id={`coverage-line-${work.workId}`}
                                                            value={selected}
                                                            disabled={busy}
                                                            onChange={(event) => setSelectedLineItem((current) => ({
                                                                ...current,
                                                                [work.workId]: event.target.value,
                                                            }))}
                                                            className="min-h-10 min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-700 focus:border-brand-300 focus:outline-none focus:ring-4 focus:ring-brand-500/10"
                                                        >
                                                            <option value="">Válassz ajánlati sort</option>
                                                            {lineItems.map((item) => (
                                                                <option key={item.lineItemId ?? item.name} value={item.lineItemId ?? ""}>
                                                                    {item.name}
                                                                </option>
                                                            ))}
                                                        </select>
                                                        <Button
                                                            type="button"
                                                            variant="secondary"
                                                            disabled={busy || !selected}
                                                            onClick={() => onSetWorkDecision(work.workId, "INCLUDED", selected)}
                                                        >
                                                            Másik tétel tartalmazza
                                                        </Button>
                                                    </>
                                                )}
                                                <Button
                                                    type="button"
                                                    variant="ghost"
                                                    disabled={busy}
                                                    onClick={() => onSetWorkDecision(work.workId, "EXCLUDED")}
                                                >
                                                    Nem része az ajánlatnak
                                                </Button>
                                                {work.status !== "NEEDS_REVIEW" && (
                                                    <Button
                                                        type="button"
                                                        variant="ghost"
                                                        disabled={busy}
                                                        onClick={() => onSetWorkDecision(work.workId, "NEEDS_REVIEW")}
                                                    >
                                                        Ellenőrzésre hagyás
                                                    </Button>
                                                )}
                                            </div>
                                        )}
                                        {work.status === "EXCLUDED" && (
                                            <Button
                                                type="button"
                                                variant="ghost"
                                                className="mt-2"
                                                disabled={busy}
                                                onClick={() => onSetWorkDecision(work.workId, "NEEDS_REVIEW")}
                                            >
                                                Döntés visszavonása
                                            </Button>
                                        )}
                                    </li>
                                );
                            })}
                        </ul>
                    )}

                    {(coverageWarnings.length > 0 || reviewWarnings.length > 0) && (
                        <div className={coverageItems.length > 0 ? "mt-4 border-t border-amber-200 pt-3" : "mt-3"}>
                            <h4 className="text-sm font-semibold text-amber-950">AI-ellenőrzési figyelmeztetések</h4>
                            <ul className="mt-2 space-y-2">
                                {coverageWarnings.length > 0 ? coverageWarnings.map((warning) => (
                                    <li key={warning.warningId} className="flex flex-col gap-2 rounded-lg bg-white p-3 sm:flex-row sm:items-start sm:justify-between">
                                        <p className="min-w-0 text-sm text-amber-950">{warning.text}</p>
                                        <Button
                                            type="button"
                                            variant={warning.acknowledged ? "ghost" : "secondary"}
                                            className="shrink-0"
                                            disabled={busy}
                                            onClick={() => onAcknowledgeWarning(warning.warningId, !warning.acknowledged)}
                                        >
                                            {warning.acknowledged ? "Ellenőrzés visszavonása" : "Ellenőriztem"}
                                        </Button>
                                    </li>
                                )) : reviewWarnings.map((warning, index) => (
                                    <li key={`${warning}-${index}`} className="rounded-lg bg-white px-3 py-2 text-sm text-amber-950">
                                        {warning}
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}
                </section>
            )}
        </section>
    );
}
