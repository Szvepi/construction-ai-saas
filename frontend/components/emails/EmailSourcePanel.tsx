import type {EmailDetail} from "@/lib/types";

type Props = {
    email: EmailDetail;
};

const CATEGORY_LABELS: Record<EmailDetail["category"], string> = {
    QUOTE_REQUEST: "Ajánlatkérés",
    OTHER: "Egyéb",
    SPAM: "Spam",
};

function formatDate(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat("hu-HU", {
        year: "numeric",
        month: "long",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
    }).format(date);
}

export function EmailSourcePanel({email}: Props) {
    return (
        <section className="min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm shadow-slate-900/[0.02]">
            <div className="border-b border-slate-100 p-5 sm:p-6">
                <div className="flex flex-wrap items-center justify-between gap-3">
                    <span className="inline-flex rounded-full border border-blue-100 bg-blue-50 px-2.5 py-1 text-xs font-semibold text-blue-700">
                        {CATEGORY_LABELS[email.category]}
                    </span>
                    <time className="text-xs text-slate-500" dateTime={email.receivedAt}>
                        {formatDate(email.receivedAt)}
                    </time>
                </div>
                <h1 className="mt-4 break-words text-xl font-semibold tracking-tight text-slate-900 sm:text-2xl">
                    {email.subject || "(nincs tárgy)"}
                </h1>
                <div className="mt-4 flex min-w-0 items-center gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-brand-50 text-sm font-bold text-brand-700">
                        {(email.fromAddress || "?").trim().charAt(0).toLocaleUpperCase("hu-HU")}
                    </div>
                    <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-slate-900">
                            {email.fromAddress || "Ismeretlen feladó"}
                        </p>
                        <p className="text-xs text-slate-500">Eredeti e-mail</p>
                    </div>
                </div>
            </div>
            <div className="p-5 sm:p-6">
                <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Levél tartalma</h2>
                <div className="mt-3 max-h-[min(68vh,800px)] overflow-y-auto overscroll-contain">
                    <pre className="max-w-full whitespace-pre-wrap break-words font-sans text-sm leading-6 text-slate-700 [overflow-wrap:anywhere]">
                        {email.bodyText || "A levél nem tartalmaz megjeleníthető szöveget."}
                    </pre>
                </div>
            </div>
        </section>
    );
}
