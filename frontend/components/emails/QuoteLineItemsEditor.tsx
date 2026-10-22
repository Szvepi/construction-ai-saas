import type {DraftLineItem} from "@/lib/types";

type Props = {
    lineItems: DraftLineItem[];
    vatRegistered: boolean;
    busy: boolean;
    onUpdate: (index: number, field: keyof DraftLineItem, value: string | number) => void;
    onAdd: () => void;
    onRemove: (index: number) => void;
};

const inputClassName = "min-w-0 w-full rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm text-slate-800 outline-none transition focus:border-brand-300 focus:ring-4 focus:ring-brand-500/10 disabled:bg-slate-50";

function formatCurrency(value: number): string {
    return `${new Intl.NumberFormat("hu-HU", {maximumFractionDigits: 0}).format(value)} Ft`;
}

export function QuoteLineItemsEditor({lineItems, vatRegistered, busy, onUpdate, onAdd, onRemove}: Props) {
    return (
        <section className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/[0.02] sm:p-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Ajánlati tartalom</p>
                    <h2 className="mt-1 text-lg font-semibold text-slate-900">Tételek</h2>
                </div>
                <button
                    type="button"
                    onClick={onAdd}
                    disabled={busy}
                    className="inline-flex h-10 items-center justify-center rounded-xl border border-slate-200 bg-white px-3.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                    + Új tétel
                </button>
            </div>

            {lineItems.length === 0 ? (
                <div className="mt-5 rounded-xl border border-dashed border-slate-300 px-4 py-10 text-center">
                    <p className="text-sm font-medium text-slate-700">Még nincs ajánlati tétel.</p>
                    <p className="mt-1 text-sm text-slate-500">Indíts AI-elemzést, vagy adj hozzá egyedi sort.</p>
                </div>
            ) : (
                <>
                    <div className="mt-5 hidden overflow-x-auto md:block">
                        <table className="w-full min-w-[660px] border-separate border-spacing-0 text-sm">
                            <thead>
                                <tr className="text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
                                    <th className="border-b border-slate-200 px-2 pb-3">Tétel</th>
                                    <th className="border-b border-slate-200 px-2 pb-3">Mennyiség</th>
                                    <th className="border-b border-slate-200 px-2 pb-3">Egység</th>
                                    <th className="border-b border-slate-200 px-2 pb-3">{vatRegistered ? "Bruttó egységár" : "Egységár"}</th>
                                    <th className="border-b border-slate-200 px-2 pb-3 text-right">{vatRegistered ? "Bruttó összeg" : "Sorösszeg"}</th>
                                    <th className="border-b border-slate-200 px-2 pb-3"/>
                                </tr>
                            </thead>
                            <tbody>
                                {lineItems.map((item, index) => (
                                    <tr key={index} className="group">
                                        <td className="border-b border-slate-100 px-2 py-3">
                                            <input aria-label={`Tétel neve ${index + 1}`} value={item.name} disabled={busy} onChange={(event) => onUpdate(index, "name", event.target.value)} className={inputClassName}/>
                                        </td>
                                        <td className="w-28 border-b border-slate-100 px-2 py-3">
                                            <input aria-label={`Mennyiség ${index + 1}`} type="number" min="0" step="0.01" value={item.quantity} disabled={busy} onChange={(event) => onUpdate(index, "quantity", event.target.value)} className={inputClassName}/>
                                        </td>
                                        <td className="w-24 border-b border-slate-100 px-2 py-3">
                                            <input aria-label={`Mértékegység ${index + 1}`} value={item.unit} disabled={busy} onChange={(event) => onUpdate(index, "unit", event.target.value)} className={inputClassName}/>
                                        </td>
                                        <td className="w-36 border-b border-slate-100 px-2 py-3">
                                            <input aria-label={`Egységár ${index + 1}`} type="number" min="0" step="100" value={item.unitPrice} disabled={busy} onChange={(event) => onUpdate(index, "unitPrice", event.target.value)} className={inputClassName}/>
                                        </td>
                                        <td className="whitespace-nowrap border-b border-slate-100 px-2 py-3 text-right font-semibold text-slate-800">
                                            {formatCurrency(item.subtotal)}
                                        </td>
                                        <td className="border-b border-slate-100 px-2 py-3 text-right">
                                            <button
                                                type="button"
                                                aria-label={`${item.name || `Tétel ${index + 1}`} törlése`}
                                                disabled={busy}
                                                onClick={() => onRemove(index)}
                                                className="rounded-lg px-2 py-1 text-sm font-medium text-rose-600 hover:bg-rose-50 disabled:opacity-50"
                                            >
                                                Törlés
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    <ul className="mt-5 space-y-3 md:hidden">
                        {lineItems.map((item, index) => (
                            <li key={index} className="min-w-0 rounded-xl border border-slate-200 bg-slate-50/70 p-3">
                                <div className="flex items-start gap-2">
                                    <label className="min-w-0 flex-1">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Tétel megnevezése</span>
                                        <input aria-label={`Tétel neve ${index + 1}`} value={item.name} disabled={busy} onChange={(event) => onUpdate(index, "name", event.target.value)} className={inputClassName}/>
                                    </label>
                                    <button
                                        type="button"
                                        aria-label={`${item.name || `Tétel ${index + 1}`} törlése`}
                                        disabled={busy}
                                        onClick={() => onRemove(index)}
                                        className="mt-5 shrink-0 rounded-lg px-2 py-2 text-sm font-medium text-rose-600 hover:bg-rose-50 disabled:opacity-50"
                                    >
                                        Törlés
                                    </button>
                                </div>
                                <div className="mt-3 grid grid-cols-2 gap-3">
                                    <label className="min-w-0">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Mennyiség</span>
                                        <input aria-label={`Mennyiség ${index + 1}`} type="number" min="0" step="0.01" value={item.quantity} disabled={busy} onChange={(event) => onUpdate(index, "quantity", event.target.value)} className={inputClassName}/>
                                    </label>
                                    <label className="min-w-0">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Mértékegység</span>
                                        <input aria-label={`Mértékegység ${index + 1}`} value={item.unit} disabled={busy} onChange={(event) => onUpdate(index, "unit", event.target.value)} className={inputClassName}/>
                                    </label>
                                    <label className="min-w-0 col-span-2">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">{vatRegistered ? "Bruttó egységár" : "Egységár"}</span>
                                        <input aria-label={`Egységár ${index + 1}`} type="number" min="0" step="100" value={item.unitPrice} disabled={busy} onChange={(event) => onUpdate(index, "unitPrice", event.target.value)} className={inputClassName}/>
                                    </label>
                                </div>
                                <div className="mt-3 flex items-center justify-between border-t border-slate-200 pt-3 text-sm">
                                    <span className="text-slate-500">{vatRegistered ? "Bruttó sorösszeg" : "Sorösszeg"}</span>
                                    <span className="font-semibold text-slate-900">{formatCurrency(item.subtotal)}</span>
                                </div>
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </section>
    );
}
