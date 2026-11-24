export type QuoteProgressData = {
    analysisComplete: boolean;
    analysisFresh: boolean;
    unresolvedWorkCount: number;
    unacknowledgedWarningCount: number;
    lineItemCount: number;
    quoteReady: boolean;
    previewReady: boolean;
    gmailDraftCreated: boolean;
};

type Props = {
    progress: QuoteProgressData;
};

type Step = {
    id: "analysis" | "work" | "quote" | "preview" | "draft";
    label: string;
    targetId: string;
    done: boolean;
    optional?: boolean;
};

export function QuoteProgressBar({progress}: Props) {
    const workReviewComplete = progress.analysisComplete
        && progress.analysisFresh
        && progress.unresolvedWorkCount === 0
        && progress.unacknowledgedWarningCount === 0;
    const currentStep = !progress.analysisComplete
        ? "analysis"
        : !workReviewComplete
            ? "work"
            : !progress.quoteReady
                ? "quote"
                : !progress.gmailDraftCreated
                    ? "draft"
                    : null;
    const steps: Step[] = [
        {
            id: "analysis",
            label: "E-mail elemzése",
            targetId: "draft-analysis",
            done: progress.analysisComplete,
        },
        {
            id: "work",
            label: "Kért munkák",
            targetId: "quote-coverage",
            done: workReviewComplete,
        },
        {
            id: "quote",
            label: "Ajánlat ellenőrzése",
            targetId: "quote-lines",
            done: progress.quoteReady,
        },
        {
            id: "preview",
            label: "PDF-előnézet",
            targetId: progress.previewReady ? "pdf-preview" : "pdf-preview-button",
            done: progress.previewReady,
            optional: true,
        },
        {
            id: "draft",
            label: "Gmail-piszkozat",
            targetId: "gmail-draft-button",
            done: progress.gmailDraftCreated,
        },
    ];
    const requiredDoneCount = [
        progress.analysisComplete,
        workReviewComplete,
        progress.quoteReady,
        progress.gmailDraftCreated,
    ].filter(Boolean).length;

    const currentMessage = currentStep === "analysis"
        ? "Indítsd el az e-mail AI-elemzését."
        : currentStep === "work"
            ? !progress.analysisFresh
                ? "A kiegészítő szöveg változott. Indíts új elemzést."
                : progress.unresolvedWorkCount > 0
                    ? `${progress.unresolvedWorkCount} felismert munka vár ellenőrzésre.`
                    : `${progress.unacknowledgedWarningCount} figyelmeztetés vár ellenőrzésre.`
            : currentStep === "quote"
                ? progress.lineItemCount === 0
                    ? "Adj hozzá legalább egy ajánlati sort."
                    : "Ellenőrizd a mennyiségeket, a számításokat és az adóbeállítást."
                : currentStep === "draft"
                    ? "Az ajánlat előkészítése kész. Létrehozhatod a Gmail-piszkozatot."
                    : "A Gmail-piszkozat elkészült.";

    function scrollToStep(targetId: string) {
        const target = document.getElementById(targetId)
            ?? document.getElementById("draft-analysis");
        target?.scrollIntoView({behavior: "smooth", block: "start"});
    }

    return (
        <section
            aria-label="Ajánlatkészítés előrehaladása"
            className="mb-5 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5"
        >
            <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-sm font-semibold text-slate-900">Ajánlatkészítés</h2>
                <span className="text-xs font-medium text-slate-500">
                    {requiredDoneCount}/4 kötelező lépés kész
                </span>
            </div>
            <ol className="mt-3 grid grid-cols-2 gap-x-3 gap-y-3 sm:grid-cols-5">
                {steps.map((step, index) => {
                    const isCurrent = step.id === currentStep;
                    const stepState = step.done ? "Kész" : step.optional ? "Opcionális" : isCurrent ? "Te következel" : "Hátravan";
                    const barClass = step.done
                        ? "bg-emerald-500"
                        : isCurrent
                            ? "bg-brand-600"
                            : "bg-slate-200";
                    const numberClass = step.done
                        ? "bg-emerald-100 text-emerald-800"
                        : isCurrent
                            ? "bg-brand-100 text-brand-800"
                            : "bg-slate-100 text-slate-500";

                    return (
                        <li key={step.id} className="min-w-0">
                            <button
                                type="button"
                                aria-label={`${index + 1}. lépés: ${step.label}. ${stepState}.`}
                                aria-current={isCurrent ? "step" : undefined}
                                onClick={() => scrollToStep(step.targetId)}
                                className="w-full min-w-0 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
                            >
                                <span className={`block h-1.5 rounded-full ${barClass}`}/>
                                <span className="mt-2 flex items-center gap-2">
                                    <span
                                        className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${numberClass}`}>
                                        {step.done ? "✓" : index + 1}
                                    </span>
                                    <span className="min-w-0 text-xs font-semibold text-slate-800">{step.label}</span>
                                </span>
                                <span className="ml-8 mt-0.5 block text-xs text-slate-500">
                                    {step.id === "work" && isCurrent && progress.unresolvedWorkCount > 0
                                        ? `${progress.unresolvedWorkCount} ellenőrizendő`
                                        : step.id === "work" && isCurrent && progress.unacknowledgedWarningCount > 0
                                            ? `${progress.unacknowledgedWarningCount} figyelmeztetés`
                                            : stepState}
                                </span>
                            </button>
                        </li>
                    );
                })}
            </ol>
            <p role="status" className="mt-3 text-sm text-slate-600">{currentMessage}</p>
        </section>
    );
}
