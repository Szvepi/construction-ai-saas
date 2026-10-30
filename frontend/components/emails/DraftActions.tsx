import {Button} from "@/components/ui/Button";
import {QuoteSummary} from "@/components/emails/QuoteSummary";

export type DraftAction = "analyze" | "save" | "preview" | "finalize" | null;

type Props = {
    draftId: number | null;
    lineItemCount: number;
    busyAction: DraftAction;
    isDirty: boolean;
    gmailDraftCreated: boolean;
    saveSuccess: string | null;
    total: number;
    vatRegistered: boolean;
    onSave: () => void;
    onPreview: () => void;
    onCreateGmailDraft: () => void;
};

export function DraftActions({
    draftId,
    lineItemCount,
    busyAction,
    isDirty,
    gmailDraftCreated,
    saveSuccess,
    total,
    vatRegistered,
    onSave,
    onPreview,
    onCreateGmailDraft,
}: Props) {
    const busy = busyAction !== null;

    return (
        <div className="sticky bottom-3 z-10 rounded-2xl border border-slate-200 bg-white p-4 shadow-lg shadow-slate-900/[0.06] sm:p-5">
            <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
                <div className="min-w-0">
                    {isDirty ? (
                        <p role="status" className="flex items-center gap-2 text-sm font-medium text-amber-800">
                            <span className="h-2 w-2 shrink-0 rounded-full bg-amber-500"/>
                            Nem mentett módosítások
                        </p>
                    ) : saveSuccess ? (
                        <p role="status" className="text-sm font-medium text-emerald-700">{saveSuccess}</p>
                    ) : (
                        <p className="text-xs text-slate-500">
                            {draftId ? "Az ajánlat összege a jelenlegi tételek alapján." : "Elemzés után szerkesztheted az ajánlatot."}
                        </p>
                    )}
                </div>
                <div className="flex min-w-0 flex-col gap-4 sm:flex-row sm:items-center sm:justify-between xl:justify-end">
                    {draftId && <QuoteSummary total={total} vatRegistered={vatRegistered}/>}
                    <div className="flex flex-wrap gap-2 sm:justify-end">
                        <Button onClick={onSave} disabled={!draftId || busy} className="w-full sm:w-auto">
                            {busyAction === "save" ? "Mentés…" : "Mentés"}
                        </Button>
                        <Button
                            variant="secondary"
                            onClick={onPreview}
                            disabled={!draftId || lineItemCount === 0 || busy || vatRegistered}
                            className="w-full sm:w-auto"
                        >
                            {busyAction === "preview" ? "PDF-előnézet készül…" : "PDF-előnézet"}
                        </Button>
                        <Button
                            variant="secondary"
                            onClick={onCreateGmailDraft}
                            disabled={!draftId || busy || gmailDraftCreated || vatRegistered}
                            className="w-full sm:w-auto"
                        >
                            {busyAction === "finalize"
                                ? "Gmail-piszkozat készül…"
                                : gmailDraftCreated ? "Gmail-piszkozat már elkészült" : "Gmail-piszkozat készítése"}
                        </Button>
                    </div>
                </div>
            </div>
        </div>
    );
}
