"use client";

import {InfoIcon} from "@/components/ui/InfoIcon";

export function CatalogInfoBanner() {
    return (
        <details
            open
            className="rounded-xl border border-blue-200 bg-gradient-to-r from-blue-50 to-white text-slate-700 shadow-sm"
        >
            <summary className="flex cursor-pointer list-none items-center gap-3 px-4 py-3 marker:hidden sm:px-5">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-white shadow-sm">
                    <InfoIcon className="h-5 w-5 text-xs text-blue-600"/>
                </span>
                <span className="flex-1 text-base font-semibold text-slate-900">
                    Mire szolgál az árjegyzék?
                </span>
                <span aria-hidden="true" className="text-sm text-slate-500">
                    Részletek
                </span>
                <span aria-hidden="true" className="text-slate-500">
                    ⌄
                </span>
            </summary>
            <div className="border-t border-blue-100 px-4 py-4 sm:px-5">
                <div className="grid gap-4 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,2fr)_minmax(12rem,0.9fr)] lg:items-center">
                    <div className="space-y-1 text-sm text-slate-700">
                        <p>
                            Itt adhatod meg, milyen munkákat végzel, milyen anyagokat használsz, és mennyit számolsz fel értük az ügyfeleidnek.
                        </p>
                        <p className="font-medium text-slate-900">
                            A MIRA ezeket az árakat használja az AI-val készített árajánlatokhoz.
                        </p>
                    </div>
                    <ol className="space-y-2 text-sm text-slate-700">
                        <li className="flex gap-2">
                            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-blue-100 font-semibold text-blue-700">1</span>
                            <span>Válaszd ki, hogy az adott tétel anyag, munkadíj, anyagot és munkadíjat tartalmazó szolgáltatás vagy egyéb díj.</span>
                        </li>
                        <li className="flex gap-2">
                            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-blue-100 font-semibold text-blue-700">2</span>
                            <span>Add meg a nettó eladási egységárat, amelyet az ügyfélnek számolsz fel.</span>
                        </li>
                        <li className="flex gap-2">
                            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-blue-100 font-semibold text-blue-700">3</span>
                            <span>A MIRA a megfelelő mennyiségekkel számolja az ajánlatot. Ha nincs megfelelő ár, azt jelzi, nem talál ki helyetted összeget.</span>
                        </li>
                    </ol>
                    <p className="border-t border-blue-100 pt-3 text-sm text-slate-600 lg:border-l lg:border-t-0 lg:py-2 lg:pl-4">
                        A belső költségek opcionálisak, csak a saját költségeid és árrésed követésére szolgálnak.
                    </p>
                </div>
            </div>
        </details>
    );
}
