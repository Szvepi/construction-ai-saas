import {Button} from "@/components/ui/Button";

type Props = {
    emailId: number;
    draftId: number | null;
    clientName: string;
    additionalNotes: string;
    includeAdditionalNotes: boolean;
    reviewWarnings: string[];
    unmapped: string[];
    busy: boolean;
    onAnalyze: () => void;
    onClientNameChange: (value: string) => void;
    onAdditionalNotesChange: (value: string) => void;
    onIncludeAdditionalNotesChange: (value: boolean) => void;
    onAddUnmapped: (value: string) => void;
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
    unmapped,
    busy,
    onAnalyze,
    onClientNameChange,
    onAdditionalNotesChange,
    onIncludeAdditionalNotesChange,
    onAddUnmapped,
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

            {draftId && reviewWarnings.length > 0 && (
                <div role="status" className="mt-5 rounded-xl border border-amber-200 bg-amber-50 p-4">
                    <h3 className="text-sm font-semibold text-amber-900">Ellenőrzést igénylő AI-megállapítások</h3>
                    <ul className="mt-2 space-y-2 text-sm text-amber-900">
                        {reviewWarnings.map((warning, index) => (
                            <li key={`${warning}-${index}`} className="rounded-lg bg-white/70 px-3 py-2">
                                {warning}
                            </li>
                        ))}
                    </ul>
                </div>
            )}

            {draftId && unmapped.length > 0 && (
                <div className="mt-4 rounded-xl border border-orange-200 bg-orange-50 p-4">
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <h3 className="text-sm font-semibold text-orange-950">Nem illeszkedett árjegyzéki tételhez</h3>
                        <p className="text-xs text-orange-800">Ellenőrizd, vagy vedd fel egyedi tételként.</p>
                    </div>
                    <ul className="mt-2 divide-y divide-orange-200/70">
                        {unmapped.map((item, index) => {
                            const label = capitalizeFirstLetter(item);
                            return (
                                <li key={`${item}-${index}`} className="flex min-w-0 items-center justify-between gap-3 py-2">
                                    <span className="min-w-0 break-words text-sm text-orange-950">{label}</span>
                                    <Button
                                        type="button"
                                        variant="secondary"
                                        className="shrink-0 !px-3 !py-1.5"
                                        disabled={busy}
                                        onClick={() => onAddUnmapped(label)}
                                    >
                                        Hozzáadás
                                    </Button>
                                </li>
                            );
                        })}
                    </ul>
                </div>
            )}
        </section>
    );
}
