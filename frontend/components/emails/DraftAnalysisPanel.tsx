import {useEffect, useState} from "react";
import {apiFetch} from "@/lib/api";
import type {
    CatalogItem, CoverageStatus, CoverageWarning, CoverageWorkItem, DraftLineItem, DraftQuoteSnapshot,
    WorkMappingPreviewResponse,
} from "@/lib/types";
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
    quoteSnapshot: DraftQuoteSnapshot | null;
    catalogItems: CatalogItem[];
    busy: boolean;
    analysisFresh: boolean;
    onAnalyze: () => void;
    onClientNameChange: (value: string) => void;
    onAdditionalNotesChange: (value: string) => void;
    onIncludeAdditionalNotesChange: (value: boolean) => void;
    onSetWorkDecision: (workId: string, status: CoverageStatus, lineItemId?: string) => void;
    onAcknowledgeWarning: (warningId: string, acknowledged: boolean) => void;
    onAddCatalogLine: (
        workId: string, catalogItemId: number, unmappedId: string | null,
        parameters: {id: string; value: number; unit: string}[]
    ) => void;
};

function capitalizeFirstLetter(value: string): string {
    return value.charAt(0).toLocaleUpperCase("hu-HU") + value.slice(1);
}

function CatalogLineMapper({
                               work,
                               unmappedId,
                               draftId,
                               catalogItems,
                               busy,
                               onAdd,
                           }: {
    work: CoverageWorkItem;
    unmappedId?: string | null;
    draftId: number | null;
    catalogItems: CatalogItem[];
    busy: boolean;
    onAdd: (
        workId: string, catalogItemId: number, unmappedId: string | null,
        parameters: {id: string; value: number; unit: string}[]
    ) => void;
}) {
    const [catalogItemId, setCatalogItemId] = useState<number | "">("");
    const [query, setQuery] = useState("");
    const [preview, setPreview] = useState<WorkMappingPreviewResponse | null>(null);
    const [parameterValues, setParameterValues] = useState<Record<string, string>>({});
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [loadingPreview, setLoadingPreview] = useState(false);
    const filteredCatalog = catalogItems.filter((item) =>
        `${item.name} ${item.unit}`.toLocaleLowerCase("hu-HU")
            .includes(query.trim().toLocaleLowerCase("hu-HU")));
    const selected = catalogItems.find((item) => item.id === catalogItemId);

    useEffect(() => {
        let active = true;
        if (!draftId || catalogItemId === "") {
            setPreview(null);
            setParameterValues({});
            setPreviewError(null);
            return () => { active = false; };
        }
        setLoadingPreview(true);
        setPreviewError(null);
        const params = new URLSearchParams({
            workId: work.workId,
            catalogItemId: String(catalogItemId),
        });
        if (unmappedId) params.set("unmappedId", unmappedId);
        apiFetch<WorkMappingPreviewResponse>(
            `/api/drafts/${draftId}/work-mappings/preview?${params.toString()}`
        ).then((data) => {
            if (!active) return;
            setPreview(data);
            setParameterValues(Object.fromEntries(data.parameters.map((parameter) => [
                parameter.id, parameter.displayValue == null ? "" : String(parameter.displayValue),
            ])));
        }).catch((error: unknown) => {
            if (active) setPreviewError(error instanceof Error ? error.message : "A számítási adatok nem tölthetők be.");
        }).finally(() => {
            if (active) setLoadingPreview(false);
        });
        return () => { active = false; };
    }, [draftId, catalogItemId, work.workId, unmappedId]);

    return (
        <div className="mt-3 grid gap-2">
            <label className="text-xs font-medium text-slate-600">
                Keresés a saját katalógusban
                <input
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    disabled={busy}
                    placeholder="Például: térkő lerakás"
                    className="mt-1 w-full rounded-lg border border-slate-200 px-2.5 py-2 text-sm"
                />
                <select
                    value={catalogItemId}
                    onChange={(event) => setCatalogItemId(event.target.value ? Number(event.target.value) : "")}
                    disabled={busy}
                    className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm"
                >
                    <option value="">Válassz a saját katalógusból</option>
                    {filteredCatalog.map((item) => (
                        <option key={item.id} value={item.id}>{item.name} ({item.unit})</option>
                    ))}
                </select>
            </label>
            {loadingPreview && <p className="text-xs text-slate-500">Számítási bemenetek betöltése…</p>}
            {previewError && <p role="alert" className="text-xs text-rose-700">{previewError}</p>}
            {preview && (
                <>
                    <p className="text-xs text-slate-600">
                        {preview.calculationStatus === "NEEDS_INPUT"
                            ? "Add meg a hiányzó adatokat; ezek nélkül nem készül végleges mennyiség."
                            : preview.calculationStatus === "INVALID"
                                ? "Ehhez a tételhez nem használható érvényes számítás. Válassz másik katalógustételt."
                                : `A számítás ${preview.outputUnit} egységben készül.`}
                    </p>
                    <div className="grid gap-2 sm:grid-cols-2">
                        {preview.parameters.filter((parameter) => parameter.editable).map((parameter) => (
                            <label key={parameter.id} className="text-xs font-medium text-slate-600">
                                {parameter.label} ({parameter.displayUnit})
                                {parameter.source === "CATALOG_DEFAULT" || parameter.source === "CONTRACTOR_PROFILE"
                                    ? <span className="ml-1 font-normal text-slate-500">· kivitelezői alapérték</span>
                                    : null}
                                <input
                                    type="number" min="0" step="any"
                                    value={parameterValues[parameter.id] ?? ""}
                                    onChange={(event) => setParameterValues((current) => ({
                                        ...current, [parameter.id]: event.target.value,
                                    }))}
                                    disabled={busy}
                                    className="mt-1 w-full rounded-lg border border-slate-200 px-2.5 py-2 text-sm"
                                />
                                {parameter.required && !parameterValues[parameter.id]
                                    && <span className="mt-1 block text-amber-800">Szükséges adat</span>}
                            </label>
                        ))}
                    </div>
                    {preview.issues.map((issue, index) => (
                        <p key={`${issue.code}-${index}`} className="text-xs text-amber-800">{issue.message}</p>
                    ))}
                </>
            )}
            <Button
                type="button"
                variant="secondary"
                disabled={busy || loadingPreview || !selected || !preview || preview.calculationStatus === "INVALID"}
                onClick={() => {
                    if (!selected || !preview) return;
                    const parameters = preview.parameters
                        .filter((parameter) => parameter.editable && parameterValues[parameter.id] !== "")
                        .map((parameter) => ({
                            id: parameter.id,
                            value: Number(parameterValues[parameter.id]),
                            unit: parameter.displayUnit,
                        }));
                    onAdd(work.workId, selected.id, unmappedId ?? null, parameters);
                }}
            >
                Tétel hozzárendelése
            </Button>
        </div>
    );
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
                                       quoteSnapshot,
                                       catalogItems,
                                       busy,
                                       analysisFresh,
                                       onAnalyze,
                                       onClientNameChange,
                                       onAdditionalNotesChange,
                                       onIncludeAdditionalNotesChange,
                                       onSetWorkDecision,
                                       onAcknowledgeWarning,
                                       onAddCatalogLine,
                                   }: Props) {
    const notesId = `additional-notes-${emailId}`;
    const includeNotesId = `include-additional-notes-${emailId}`;
    const clientNameId = `client-name-${emailId}`;

    return (
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/[0.02] sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-brand-700">AI-elemzés</p>
                    <h2 className="mt-1 text-lg font-semibold text-slate-900">
                        {draftId ? "Ajánlat előkészítése" : "Ajánlatkérés elemzése"}
                    </h2>
                    <p className="mt-1 text-sm text-slate-500">
                        Mira a levél és az árjegyzék alapján javasol tételeket.
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
                        Mira az elemzéskor figyelembe veszi ezt a szöveget.
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
                <section id="quote-coverage"
                         className="mt-5 scroll-mt-24 rounded-xl border border-amber-200 bg-amber-50/70 p-4">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                        <div>
                            <h3 className="text-sm font-semibold text-amber-950">Ajánlat teljességének ellenőrzése</h3>
                            <p className="mt-1 text-xs text-amber-900">
                                Mira javaslatokat ad; a lefedettség végső megítélése a tiéd.
                            </p>
                        </div>
                        {!analysisFresh && (
                            <div
                                className="rounded-lg border border-amber-300 bg-white px-3 py-2 text-xs font-semibold text-amber-900">
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
                                const linkedNames = (work.linkedLineItemIds ?? [])
                                    .map((lineId) => lineItems.find((item) => item.lineItemId === lineId)?.name)
                                    .filter((name): name is string => Boolean(name));
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
                                                {linkedNames.length > 0 && (
                                                    <p className="mt-1 text-xs text-slate-500">
                                                        Kapcsolódó sorok: {linkedNames.join(", ")}
                                                    </p>
                                                )}
                                                {work.calculationStatus && work.calculationStatus !== "VALID" && (
                                                    <p className="mt-1 text-xs font-medium text-amber-800">
                                                        Kalkuláció: {work.calculationStatus === "INVALID"
                                                            ? "érvénytelen bemenet vagy művelet"
                                                            : work.calculationStatus === "NEEDS_INPUT"
                                                                ? "hiányzó adat"
                                                                : "feltételezés ellenőrzése szükséges"}
                                                    </p>
                                                )}
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
                                                {work.status === "INCLUDED" && !work.userConfirmed
                                                    && work.linkedLineItemId && (
                                                    <Button
                                                        type="button"
                                                        variant="secondary"
                                                        disabled={busy}
                                                        onClick={() => onSetWorkDecision(
                                                            work.workId, "INCLUDED", work.linkedLineItemId ?? undefined)}
                                                    >
                                                        Megerősítem a lefedettséget
                                                    </Button>
                                                )}
                                                {work.recognitionStatus === "UNMAPPED"
                                                    || work.recognitionStatus === "PARTIALLY_MAPPED" ? (
                                                    (quoteSnapshot?.workItems.find((candidate) =>
                                                        candidate.workId === work.workId)?.unmappedLines ?? [])
                                                        .map((unmapped, index) => (
                                                            <div key={unmapped.unmappedId ?? `${work.workId}-${index}`}
                                                                 className="basis-full rounded-lg bg-amber-50/60 p-3">
                                                                <p className="text-xs text-amber-900">
                                                                    {unmapped.explanation ?? unmapped.reason}
                                                                </p>
                                                                {catalogItems.length > 0 ? (
                                                                    <CatalogLineMapper
                                                                        work={work}
                                                                        unmappedId={unmapped.unmappedId}
                                                                        draftId={draftId}
                                                                        catalogItems={catalogItems}
                                                                        busy={busy}
                                                                        onAdd={onAddCatalogLine}
                                                                    />
                                                                ) : <p className="mt-2 text-xs text-amber-900">
                                                                    Előbb adj katalógustételt a céges árjegyzékhez.
                                                                </p>}
                                                            </div>
                                                        ))
                                                ) : null}
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
                                    <li key={warning.warningId}
                                        className="flex flex-col gap-2 rounded-lg bg-white p-3 sm:flex-row sm:items-start sm:justify-between">
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
                                    <li key={`${warning}-${index}`}
                                        className="rounded-lg bg-white px-3 py-2 text-sm text-amber-950">
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
