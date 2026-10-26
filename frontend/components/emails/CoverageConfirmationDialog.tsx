"use client";

import {useEffect, useRef} from "react";
import {Button} from "@/components/ui/Button";

type Props = {
    unresolvedIssues: string[];
    busy: boolean;
    onReturn: () => void;
    onContinue: () => void;
};

export function CoverageConfirmationDialog({unresolvedIssues, busy, onReturn, onContinue}: Props) {
    const dialogRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialogRef.current?.querySelector<HTMLButtonElement>("button:not([disabled])")?.focus();

        function handleKeyDown(event: KeyboardEvent) {
            if (event.key === "Escape" && !busy) {
                event.preventDefault();
                onReturn();
                return;
            }
            if (event.key !== "Tab" || !dialogRef.current) return;
            const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(
                'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
            ));
            if (focusable.length === 0) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        }

        document.addEventListener("keydown", handleKeyDown);
        return () => {
            document.removeEventListener("keydown", handleKeyDown);
            previouslyFocused?.focus();
        };
    }, [busy, onReturn]);

    return (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-950/50 p-0 sm:items-center sm:p-4">
            <div
                ref={dialogRef}
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="coverage-dialog-title"
                aria-describedby="coverage-dialog-description"
                className="max-h-[90vh] w-full overflow-y-auto rounded-t-2xl bg-white p-5 shadow-2xl sm:max-w-xl sm:rounded-2xl sm:p-6"
            >
                <div className="flex items-start gap-3">
                    <span aria-hidden="true" className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-amber-100 text-sm font-bold text-amber-900">!</span>
                    <div className="min-w-0">
                        <h2 id="coverage-dialog-title" className="text-lg font-semibold text-slate-950">
                            Az ajánlat ellenőrzést igényel
                        </h2>
                        <p id="coverage-dialog-description" className="mt-1 text-sm text-slate-600">
                            Az alábbi munkák vagy AI-figyelmeztetések még nincsenek feloldva. Készíthetsz Gmail-piszkozatot így is.
                        </p>
                    </div>
                </div>
                <ul className="mt-4 max-h-64 space-y-2 overflow-y-auto rounded-xl border border-amber-100 bg-amber-50/60 p-3 text-sm text-slate-800">
                    {unresolvedIssues.map((issue, index) => (
                        <li key={`${issue}-${index}`} className="break-words rounded-lg bg-white px-3 py-2">
                            {issue}
                        </li>
                    ))}
                </ul>
                <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                    <Button type="button" variant="secondary" disabled={busy} onClick={onReturn}>
                        Vissza az ajánlathoz
                    </Button>
                    <Button type="button" disabled={busy} onClick={onContinue}>
                        {busy ? "Gmail-piszkozat készül…" : "Gmail-piszkozat készítése így is"}
                    </Button>
                </div>
            </div>
        </div>
    );
}
