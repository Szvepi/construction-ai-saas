import {useState} from "react";
import type {CatalogItemType, DraftLineItem, DraftQuoteSnapshot} from "@/lib/types";

type Props = {
    lineItems: DraftLineItem[];
    quoteSnapshot: DraftQuoteSnapshot | null;
    busy: boolean;
    onUpdateQuantity: (index: number, value: string) => void;
    onUpdateUnitPrice: (index: number, value: string) => void;
    onUpdatePriceReason: (index: number, value: string) => void;
    onUpdateParameter: (lineIndex: number, parameterIndex: number, value: string) => void;
    onApproveAssumptions: (lineIndex: number, approved: boolean) => void;
    onRejectAssumption: (lineIndex: number, parameterIndex: number) => void;
    onAddManualLine: (input: {
        name: string; quantity: number; unit: string; netUnitPrice: number;
        itemType: CatalogItemType; workId?: string;
    }) => void;
    onUpdateManualLine: (lineItemId: string, input: {
        name: string; quantity: number; unit: string; netUnitPrice: number;
        itemType: CatalogItemType;
    }) => void;
    onDeleteLine: (lineItemId: string) => void;
};

const inputClassName = "w-full rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm text-slate-800 outline-none transition focus:border-brand-300 focus:ring-4 focus:ring-brand-500/10 disabled:bg-slate-50";

function formatCurrency(value: number | null): string {
    return value == null
        ? "Nincs még kiszámítva"
        : `${new Intl.NumberFormat("hu-HU", {maximumFractionDigits: 0}).format(value)} Ft`;
}

function parameterLabel(id: string): string {
    const labels: Record<string, string> = {
        thickness: "Rétegvastagság",
        layerThickness: "Rétegvastagság",
        height: "Belmagasság",
        length: "Hosszúság",
        width: "Szélesség",
        coats: "Rétegek száma",
        layers: "Rétegek száma",
        count: "Darabszám",
        area: "Terület",
    };
    return labels[id] ?? "Kiegészítő méret vagy mennyiség";
}

export function QuoteLineItemsEditor({
    lineItems,
    quoteSnapshot,
    busy,
    onUpdateQuantity,
    onUpdateUnitPrice,
    onUpdatePriceReason,
    onUpdateParameter,
    onApproveAssumptions,
    onRejectAssumption,
    onAddManualLine,
    onUpdateManualLine,
    onDeleteLine,
}: Props) {
    const [expanded, setExpanded] = useState<Record<string, boolean>>({});
    const [manualName, setManualName] = useState("");
    const [manualQuantity, setManualQuantity] = useState("1");
    const [manualUnit, setManualUnit] = useState("db");
    const [manualPrice, setManualPrice] = useState("");
    const [manualType, setManualType] = useState<CatalogItemType>("OTHER");
    const [manualWorkId, setManualWorkId] = useState("");
    const [editingManual, setEditingManual] = useState<{
        id: string; name: string; quantity: string; unit: string; price: string; itemType: CatalogItemType;
    } | null>(null);

    return (
        <section className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/[0.02] sm:p-6">
            <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Ajánlati tartalom</p>
                <h2 className="mt-1 text-lg font-semibold text-slate-900">Munkák és ajánlati sorok</h2>
                <p className="mt-1 text-xs text-slate-500">
                    A katalógus ára nem változik. Az itt megadott egyedi ár csak erre az ajánlatra érvényes.
                </p>
            </div>

            <details className="mt-4 rounded-xl border border-slate-200 bg-slate-50 p-4">
                <summary className="cursor-pointer text-sm font-semibold text-slate-800">Egyszeri tétel hozzáadása</summary>
                <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                    <label className="text-xs font-medium text-slate-600">Megnevezés
                        <input value={manualName} onChange={(event) => setManualName(event.target.value)}
                               disabled={busy} className={`${inputClassName} mt-1`} />
                    </label>
                    <label className="text-xs font-medium text-slate-600">Kapcsolódó munkafázis
                        <select value={manualWorkId} onChange={(event) => setManualWorkId(event.target.value)}
                                disabled={busy} className={`${inputClassName} mt-1`}>
                            <option value="">Önálló tétel</option>
                            {quoteSnapshot?.workItems.map((work) => (
                                <option key={work.workId} value={work.workId}>{work.originalRequest}</option>
                            ))}
                        </select>
                    </label>
                    <label className="text-xs font-medium text-slate-600">Tétel típusa
                        <select value={manualType} onChange={(event) => setManualType(event.target.value as CatalogItemType)}
                                disabled={busy} className={`${inputClassName} mt-1`}>
                            <option value="MATERIAL">Anyag</option>
                            <option value="LABOR">Munkadíj</option>
                            <option value="OTHER">Egyéb díj</option>
                            <option value="COMBINED">Anyag és munkadíj együtt</option>
                        </select>
                    </label>
                    <label className="text-xs font-medium text-slate-600">Mennyiség
                        <input type="number" min="0.01" step="any" value={manualQuantity}
                               onChange={(event) => setManualQuantity(event.target.value)}
                               disabled={busy} className={`${inputClassName} mt-1`} />
                    </label>
                    <label className="text-xs font-medium text-slate-600">Mértékegység
                        <input value={manualUnit} onChange={(event) => setManualUnit(event.target.value)}
                               disabled={busy} placeholder="db, m, m2, m3..."
                               className={`${inputClassName} mt-1`} />
                    </label>
                    <label className="text-xs font-medium text-slate-600">Nettó egységár (Ft)
                        <input type="number" min="0.01" step="any" value={manualPrice}
                               onChange={(event) => setManualPrice(event.target.value)}
                               disabled={busy} className={`${inputClassName} mt-1`} />
                    </label>
                </div>
                <button type="button" disabled={busy || !manualName.trim()
                    || Number(manualQuantity) <= 0 || Number(manualPrice) <= 0 || !manualUnit.trim()}
                        onClick={() => {
                            onAddManualLine({
                                name: manualName, quantity: Number(manualQuantity), unit: manualUnit,
                                netUnitPrice: Number(manualPrice), itemType: manualType,
                                workId: manualWorkId || undefined,
                            });
                            setManualName("");
                            setManualQuantity("1");
                            setManualPrice("");
                        }}
                        className="mt-3 rounded-lg bg-brand-700 px-3 py-2 text-sm font-semibold text-white disabled:opacity-50">
                    Tétel hozzáadása
                </button>
            </details>

            {lineItems.length === 0 ? (
                <div className="mt-5 rounded-xl border border-dashed border-slate-300 px-4 py-10 text-center">
                    <p className="text-sm font-medium text-slate-700">Még nincs beárazott ajánlati sor.</p>
                    <p className="mt-1 text-sm text-slate-500">
                        A hiányzó katalógustétel vagy kalkulációs adat ellenőrzést igényel.
                    </p>
                </div>
            ) : (
                <div className="mt-5 space-y-4">
                    {lineItems.map((item, index) => {
                        const line = quoteSnapshot?.lineItems.find((candidate) =>
                            candidate.lineItemId === item.lineItemId);
                        const work = quoteSnapshot?.workItems.find((candidate) =>
                            candidate.workId === line?.workId);
                        const status = line?.calculationResult.status;
                        const needsApproval = item.calculationParameters?.some((parameter) =>
                            parameter.source === "AI_ASSUMPTION" && !parameter.rejected);
                        const expandedLine = expanded[item.lineItemId ?? String(index)] ?? false;
                        const editingThisLine = editingManual?.id === item.lineItemId ? editingManual : null;
                        const originalQuantity = line?.requiredQuantity;
                        const calculatedQuantity = line?.userOverrideQuantity ?? line?.customerChargeQuantity;

                        return (
                            <article key={item.lineItemId ?? index}
                                     className="rounded-xl border border-slate-200 bg-white p-4">
                                <div className="flex flex-wrap items-start justify-between gap-3">
                                    <div className="min-w-0">
                                        <p className="break-words text-base font-semibold text-slate-900">{item.name}</p>
                                        {work && <p className="mt-1 text-sm text-slate-600">{work.originalRequest}</p>}
                                        <p className="mt-1 text-xs text-slate-500">
                                            {originalQuantity
                                                ? `Kiinduló számított mennyiség: ${originalQuantity.value} ${originalQuantity.unit}`
                                                : "A mennyiség a hiányzó adatok megadása után számítható ki"}
                                            {calculatedQuantity && line?.userOverrideQuantity
                                                ? ` · Ajánlati mennyiség: ${calculatedQuantity.value} ${calculatedQuantity.unit}`
                                                : ""}
                                        </p>
                                    </div>
                                    <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${
                                        status === "VALID" ? "bg-emerald-50 text-emerald-800"
                                            : status === "INVALID" ? "bg-rose-50 text-rose-800"
                                                : "bg-amber-50 text-amber-900"
                                    }`}>
                                        {status === "VALID" ? "Számítás rendben"
                                            : status === "INVALID" ? "Érvénytelen számítás"
                                                : status === "NEEDS_INPUT" ? "Adat szükséges"
                                                    : status === "NEEDS_REVIEW" ? "Ellenőrzés szükséges"
                                                        : "Ellenőrizendő"}
                                    </span>
                                </div>

                                {line?.explanation && (
                                    <p className="mt-3 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-700">
                                        {line.explanation}
                                    </p>
                                )}
                                {line?.calculationResult.issues.map((issue, issueIndex) => (
                                    <p key={`${issue.code}-${issueIndex}`}
                                       className="mt-2 text-sm font-medium text-amber-800">{issue.message}</p>
                                ))}

                                <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                                    <label className="text-xs font-medium text-slate-600">
                                        Ajánlati mennyiség ({item.unit})
                                        <input
                                            aria-label={`Ajánlati mennyiség ${item.name} ${item.lineItemId}`}
                                            type="number" min="0.01" step="0.01"
                                            value={item.quantity ?? ""}
                                            disabled={busy || item.quantity == null}
                                            onChange={(event) => onUpdateQuantity(index, event.target.value)}
                                            className={`${inputClassName} mt-1`}
                                        />
                                        {line?.userOverrideQuantity && (
                                            <button type="button" className="mt-1 text-xs text-brand-700 underline"
                                                    disabled={busy}
                                                    onClick={() => onUpdateQuantity(
                                                        index, String(line.customerChargeQuantity?.value ?? ""))}>
                                                Számított mennyiség visszaállítása
                                            </button>
                                        )}
                                    </label>
                                    <label className="text-xs font-medium text-slate-600">
                                        Nettó egységár
                                        <input
                                            aria-label={`Nettó egységár ${item.name} ${item.lineItemId}`}
                                            type="number" min="0.01" step="1" value={item.unitPrice}
                                            disabled={busy}
                                            onChange={(event) => onUpdateUnitPrice(index, event.target.value)}
                                            className={`${inputClassName} mt-1`}
                                        />
                                        {item.priceOverridden && (
                                            <>
                                                <span className="mt-1 block text-amber-800">Egyedi ár alkalmazva</span>
                                                <input
                                                    aria-label={`Egyedi ár indoklása ${item.name}`}
                                                    value={item.priceOverrideReason ?? ""}
                                                    onChange={(event) => onUpdatePriceReason(index, event.target.value)}
                                                    placeholder="Az egyedi ár rövid indoka"
                                                    className={`${inputClassName} mt-1`}
                                                />
                                            </>
                                        )}
                                        {!item.priceOverridden && item.catalogUnitPrice != null && (
                                            <span className="mt-1 block text-slate-500">
                                                Katalógus alapára: {formatCurrency(item.catalogUnitPrice)}
                                            </span>
                                        )}
                                    </label>
                                    <div className="text-xs font-medium text-slate-600">
                                        Nettó sorösszeg
                                        <p className="mt-2 text-base font-semibold text-slate-900">
                                            {formatCurrency(item.subtotal)}
                                        </p>
                                    </div>
                                    <div className="text-xs font-medium text-slate-600">
                                        Mértékegység
                                        <p className="mt-2 text-base text-slate-800">{item.unit}</p>
                                    </div>
                                </div>

                                {item.lineItemId && (
                                    <button type="button" disabled={busy}
                                            onClick={() => onDeleteLine(item.lineItemId!)}
                                            className="mt-3 rounded-lg border border-rose-200 px-3 py-1.5 text-xs font-semibold text-rose-800">
                                        Tétel eltávolítása az ajánlatból
                                    </button>
                                )}
                                {item.userOverrideLine && (
                                    <div className="mt-3 flex flex-wrap gap-2">
                                        <button type="button" disabled={busy}
                                                onClick={() => setEditingManual(editingManual?.id === item.lineItemId
                                                    ? null
                                                    : {
                                                        id: item.lineItemId ?? "",
                                                        name: item.name,
                                                        quantity: String(item.quantity ?? ""),
                                                        unit: item.unit,
                                                        price: String(item.unitPrice),
                                                        itemType: item.itemType ?? "OTHER",
                                                    })}
                                                className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700">
                                            {editingThisLine ? "Szerkesztés bezárása" : "Kézi tétel szerkesztése"}
                                        </button>
                                        {editingThisLine && (
                                            <div className="basis-full grid gap-2 sm:grid-cols-2">
                                                <input aria-label="Kézi tétel megnevezése" value={editingThisLine.name}
                                                       onChange={(event) => setEditingManual({...editingThisLine, name: event.target.value})}
                                                       className={inputClassName} />
                                                <select aria-label="Kézi tétel típusa" value={editingThisLine.itemType}
                                                        onChange={(event) => setEditingManual({
                                                            ...editingThisLine, itemType: event.target.value as CatalogItemType,
                                                        })} className={inputClassName}>
                                                    <option value="MATERIAL">Anyag</option>
                                                    <option value="LABOR">Munkadíj</option>
                                                    <option value="OTHER">Egyéb díj</option>
                                                    <option value="COMBINED">Anyag és munkadíj együtt</option>
                                                </select>
                                                <input aria-label="Kézi tétel mennyisége" type="number" min="0.01"
                                                       value={editingThisLine.quantity}
                                                       onChange={(event) => setEditingManual({...editingThisLine, quantity: event.target.value})}
                                                       className={inputClassName} />
                                                <input aria-label="Kézi tétel mértékegysége" value={editingThisLine.unit}
                                                       onChange={(event) => setEditingManual({...editingThisLine, unit: event.target.value})}
                                                       className={inputClassName} />
                                                <input aria-label="Kézi tétel nettó egységára" type="number" min="0.01"
                                                       value={editingThisLine.price}
                                                       onChange={(event) => setEditingManual({...editingThisLine, price: event.target.value})}
                                                       className={inputClassName} />
                                                <button type="button" disabled={busy || !editingThisLine.name.trim()
                                                    || Number(editingThisLine.quantity) <= 0
                                                    || Number(editingThisLine.price) <= 0}
                                                        onClick={() => {
                                                            onUpdateManualLine(editingThisLine.id, {
                                                                name: editingThisLine.name,
                                                                quantity: Number(editingThisLine.quantity),
                                                                unit: editingThisLine.unit,
                                                                netUnitPrice: Number(editingThisLine.price),
                                                                itemType: editingThisLine.itemType,
                                                            });
                                                            setEditingManual(null);
                                                        }}
                                                        className="rounded-lg bg-brand-700 px-3 py-2 text-sm font-semibold text-white disabled:opacity-50">
                                                    Módosítások mentése
                                                </button>
                                            </div>
                                        )}
                                    </div>
                                )}

                                {item.calculationParameters?.some((parameter) =>
                                    parameter.source === "AI_ASSUMPTION" || parameter.value == null) && (
                                    <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50/70 p-3">
                                        <p className="text-sm font-semibold text-amber-950">Kalkulációs adatok ellenőrzése</p>
                                        <div className="mt-2 grid gap-3 sm:grid-cols-2">
                                            {item.calculationParameters.map((parameter, parameterIndex) => (
                                                <label key={parameter.id}
                                                       className="text-xs font-medium text-amber-950">
                                                    {parameterLabel(parameter.id)} ({parameter.unit})
                                                    <input
                                                        aria-label={`${parameterLabel(parameter.id)} ${item.name}`}
                                                        type="number" min="0" step="any"
                                                        value={parameter.value ?? ""}
                                                        disabled={busy}
                                                        onChange={(event) => onUpdateParameter(
                                                            index, parameterIndex, event.target.value)}
                                                        className={`${inputClassName} mt-1`}
                                                    />
                                                    <span className="mt-1 block font-normal">
                                                        {parameter.source === "AI_ASSUMPTION"
                                                            ? "AI által feltételezett érték"
                                                            : parameter.source === "CATALOG_DEFAULT"
                                                                ? "Katalógus alapbeállítás"
                                                                : "Ügyféltől vagy kézi módosításból"}
                                                    </span>
                                                </label>
                                            ))}
                                        </div>
                                        {needsApproval && (
                                            <label className="mt-3 flex items-start gap-2 text-sm text-amber-950">
                                                <input
                                                    type="checkbox"
                                                    checked={item.assumptionsApproved ?? false}
                                                    disabled={busy}
                                                    onChange={(event) => onApproveAssumptions(index, event.target.checked)}
                                                    className="mt-0.5 h-4 w-4 rounded border-amber-400"
                                                />
                                                <span>Jóváhagyom a fenti adatokat és feltételezéseket</span>
                                            </label>
                                        )}
                                        {item.calculationParameters?.map((parameter, parameterIndex) =>
                                            parameter.source === "AI_ASSUMPTION" && !parameter.rejected ? (
                                                <button key={`reject-${parameter.id}`} type="button"
                                                        disabled={busy}
                                                        onClick={() => onRejectAssumption(index, parameterIndex)}
                                                        className="mt-2 text-xs font-semibold text-rose-800 underline">
                                                    Feltételezés elutasítása: {parameterLabel(parameter.id)}
                                                </button>
                                            ) : parameter.rejected ? (
                                                <p key={`rejected-${parameter.id}`} className="mt-2 text-xs text-rose-800">
                                                    Elutasított feltételezés; adj meg tényleges értéket.
                                                </p>
                                            ) : null)}
                                    </div>
                                )}

                                {line?.calculationPlan && (
                                    <details className="mt-3 text-xs text-slate-600">
                                        <summary className="cursor-pointer font-medium text-slate-700">
                                            Számítás részletei
                                        </summary>
                                        <ol className="mt-2 list-inside list-decimal space-y-1">
                                            {line.calculationResult.trace.map((step) => (
                                                <li key={step.stepId}>
                                                    {step.operation === "AREA" ? "Terület" : step.operation === "VOLUME"
                                                        ? "Térfogat" : step.operation === "PERIMETER"
                                                            ? "Kerület" : "Mennyiség"}
                                                    {step.result ? `: ${step.result.value} ${step.result.unit}` : ""}
                                                </li>
                                            ))}
                                        </ol>
                                    </details>
                                )}
                            </article>
                        );
                    })}
                </div>
            )}
        </section>
    );
}
