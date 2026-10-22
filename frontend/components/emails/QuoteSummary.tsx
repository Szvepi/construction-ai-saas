type Props = {
    total: number;
    net: number;
    vat: number;
    vatRegistered: boolean;
};

function formatCurrency(value: number): string {
    return `${new Intl.NumberFormat("hu-HU", {maximumFractionDigits: 0}).format(value)} Ft`;
}

export function QuoteSummary({total, net, vat, vatRegistered}: Props) {
    return (
        <section aria-label="Ajánlat összesítése" className="min-w-[190px]">
            {vatRegistered ? (
                <div className="space-y-1 text-sm text-slate-600">
                    <div className="flex justify-between gap-4">
                        <span>Nettó összesen</span>
                        <span className="font-medium text-slate-800">{formatCurrency(net)}</span>
                    </div>
                    <div className="flex justify-between gap-4">
                        <span>ÁFA (27%)</span>
                        <span className="font-medium text-slate-800">{formatCurrency(vat)}</span>
                    </div>
                    <div className="flex justify-between gap-4 border-t border-slate-200 pt-2 text-base font-bold text-slate-900">
                        <span>Bruttó fizetendő</span>
                        <span className="whitespace-nowrap">{formatCurrency(total)}</span>
                    </div>
                </div>
            ) : (
                <div>
                    <p className="text-xs font-medium text-slate-500">Fizetendő összesen</p>
                    <p className="mt-1 text-xl font-bold tracking-tight text-slate-900">{formatCurrency(total)}</p>
                </div>
            )}
        </section>
    );
}
