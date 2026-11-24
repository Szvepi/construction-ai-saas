type Props = {
    netTotal: number | null;
    taxRegime: "AAM" | "VAT_REGISTERED" | "REVERSE_CHARGE_UNSUPPORTED";
    vatRate: number | null;
    vatAmount: number | null;
    grossTotal: number | null;
    taxConfigurationValid: boolean;
    stale: boolean;
};

function formatCurrency(value: number | null): string {
    if (value == null) return "—";
    return `${new Intl.NumberFormat("hu-HU", {maximumFractionDigits: 0}).format(value)} Ft`;
}

export function QuoteSummary({
                                 netTotal,
                                 taxRegime,
                                 vatRate,
                                 vatAmount,
                                 grossTotal,
                                 taxConfigurationValid,
                                 stale
                             }: Props) {
    const invalidVatRate = vatRate == null
        || !Number.isFinite(vatRate)
        || vatRate <= 0
        || vatRate > 100;

    return (
        <section
            aria-label="Ajánlat összesítése"
            className="min-w-[190px] rounded-xl border border-slate-200 bg-slate-50 p-3"
        >
            <h2 className="mb-2 text-sm font-semibold leading-5 text-slate-800">
                Az ajánlat összege a jelenlegi tételek alapján
            </h2>
            <p className="text-sm text-slate-700">Nettó: {formatCurrency(netTotal)}</p>
            {taxRegime === "VAT_REGISTERED" ? (
                <>
                    <p className="mt-1 text-sm text-slate-700">
                        ÁFA {vatRate == null ? "" : `(${vatRate}%)`}: {formatCurrency(vatAmount)}
                    </p>
                    <p className="mt-1 text-lg font-bold tracking-tight text-slate-900">
                        Bruttó: {formatCurrency(grossTotal)}
                    </p>
                </>
            ) : taxRegime === "AAM" ? (
                <>
                    <p className="mt-1 text-xs text-slate-600">Alanyi adómentes (ÁFA nélkül)</p>
                    <p className="mt-1 text-lg font-bold tracking-tight text-slate-900">
                        Fizetendő: {formatCurrency(grossTotal)}
                    </p>
                </>
            ) : (
                <p className="mt-1 text-xs text-rose-800">
                    Ez az adózási mód jelenleg nem támogatott; a véglegesítés le van tiltva.
                </p>
            )}
            {!taxConfigurationValid && taxRegime === "VAT_REGISTERED" && invalidVatRate && (
                <p className="mt-1 max-w-xs text-xs text-amber-800">Állíts be érvényes ÁFA-kulcsot a céges
                    adatoknál.</p>
            )}
            {stale && <p className="mt-1 text-xs text-amber-800">Mentés után Mira újraszámolja az összegeket.</p>}
        </section>
    );
}
