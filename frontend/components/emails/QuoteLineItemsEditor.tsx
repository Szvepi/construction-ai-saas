import type {DraftLineItem} from "@/lib/types";

type Props = {
    lineItems: DraftLineItem[];
    busy: boolean;
    onUpdate: (index: number, field: "quantity", value: string | number) => void;
};

const inputClassName = "w-full rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm text-slate-800 outline-none transition focus:border-brand-300 focus:ring-4 focus:ring-brand-500/10 disabled:bg-slate-50";

function formatCurrency(value: number): string {
    return `${new Intl.NumberFormat("hu-HU", {maximumFractionDigits: 0}).format(value)} Ft`;
}

export function QuoteLineItemsEditor({lineItems, busy, onUpdate}: Props) {
    return (
        <section className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/[0.02] sm:p-6">
            <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Ajánlati tartalom</p>
                <h2 className="mt-1 text-lg font-semibold text-slate-900">Tételek</h2>
                <p className="mt-1 text-xs text-slate-500">
                    A katalógus és az egységár a számítás hiteles forrása; itt csak a mennyiség írható felül.
                </p>
            </div>

            {lineItems.length === 0 ? (
                <div className="mt-5 rounded-xl border border-dashed border-slate-300 px-4 py-10 text-center">
                    <p className="text-sm font-medium text-slate-700">Nincs érvényesen beárazott ajánlati tétel.</p>
                    <p className="mt-1 text-sm text-slate-500">
                        Pótold a hiányzó katalógustételt vagy adatot, majd futtass új elemzést.
                    </p>
                </div>
            ) : (
                <>
                    <div className="mt-5 hidden overflow-x-auto md:block">
                        <table className="w-full min-w-[620px] border-separate border-spacing-0 text-sm">
                            <thead>
                                <tr className="text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
                                    <th className="border-b border-slate-200 px-2 pb-3">Tétel</th>
                                    <th className="border-b border-slate-200 px-2 pb-3">Mennyiség</th>
                                    <th className="border-b border-slate-200 px-2 pb-3">Egység</th>
                                    <th className="border-b border-slate-200 px-2 pb-3">Nettó egységár</th>
                                    <th className="border-b border-slate-200 px-2 pb-3 text-right">Nettó sorösszeg</th>
                                </tr>
                            </thead>
                            <tbody>
                                {lineItems.map((item, index) => (
                                    <tr key={item.lineItemId ?? index}>
                                        <td className="border-b border-slate-100 px-2 py-3 text-slate-800">
                                            {item.name}
                                        </td>
                                        <td className="w-32 border-b border-slate-100 px-2 py-3">
                                            <input
                                                aria-label={`Mennyiség ${index + 1}`}
                                                type="number"
                                                min="0.01"
                                                step="0.01"
                                                value={item.quantity}
                                                disabled={busy}
                                                onChange={(event) => onUpdate(index, "quantity", event.target.value)}
                                                className={inputClassName}
                                            />
                                        </td>
                                        <td className="w-24 border-b border-slate-100 px-2 py-3 text-slate-600">
                                            {item.unit}
                                        </td>
                                        <td className="w-36 border-b border-slate-100 px-2 py-3 text-slate-600">
                                            {formatCurrency(item.unitPrice)}
                                        </td>
                                        <td className="whitespace-nowrap border-b border-slate-100 px-2 py-3 text-right font-semibold text-slate-800">
                                            {formatCurrency(item.subtotal)}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    <ul className="mt-5 space-y-3 md:hidden">
                        {lineItems.map((item, index) => (
                            <li key={item.lineItemId ?? index}
                                className="min-w-0 rounded-xl border border-slate-200 bg-slate-50/70 p-3">
                                <p className="break-words text-sm font-semibold text-slate-900">{item.name}</p>
                                <div className="mt-3 grid grid-cols-2 gap-3">
                                    <label className="min-w-0">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Mennyiség</span>
                                        <input
                                            aria-label={`Mennyiség ${index + 1}`}
                                            type="number"
                                            min="0.01"
                                            step="0.01"
                                            value={item.quantity}
                                            disabled={busy}
                                            onChange={(event) => onUpdate(index, "quantity", event.target.value)}
                                            className={inputClassName}
                                        />
                                    </label>
                                    <div className="min-w-0">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Egység</span>
                                        <p className="rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm text-slate-700">
                                            {item.unit}
                                        </p>
                                    </div>
                                    <div className="min-w-0">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Nettó egységár</span>
                                        <p className="rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm text-slate-700">
                                            {formatCurrency(item.unitPrice)}
                                        </p>
                                    </div>
                                    <div className="min-w-0">
                                        <span className="mb-1.5 block text-xs font-medium text-slate-500">Nettó sorösszeg</span>
                                        <p className="rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-sm font-semibold text-slate-900">
                                            {formatCurrency(item.subtotal)}
                                        </p>
                                    </div>
                                </div>
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </section>
    );
}
