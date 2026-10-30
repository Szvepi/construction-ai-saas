type Props = {
    total: number;
    vatRegistered: boolean;
};

function formatCurrency(value: number): string {
    return `${new Intl.NumberFormat("hu-HU", {maximumFractionDigits: 0}).format(value)} Ft`;
}

export function QuoteSummary({total, vatRegistered}: Props) {
    return (
        <section aria-label="Ajánlat összesítése" className="min-w-[190px]">
            {vatRegistered ? (
                <div>
                    <p className="text-xs font-medium text-slate-500">Nettó ajánlati összeg</p>
                    <p className="mt-1 text-xl font-bold tracking-tight text-slate-900">{formatCurrency(total)}</p>
                    <p className="mt-1 max-w-xs text-xs text-amber-800">
                        PDF és Gmail véglegesítéshez előbb ÁFA-kulcsot kell beállítani.
                    </p>
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
