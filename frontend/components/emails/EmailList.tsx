"use client";

import Link from "next/link";
import type { EmailSummary } from "@/lib/types";

type Props = { emails: EmailSummary[] };

export function EmailList({ emails }: Props) {
  if (emails.length === 0) {
    return (
      <p className="py-12 text-center text-slate-500">
        No emails yet. Connect Gmail and refresh.
      </p>
    );
  }

  return (
    <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white">
      {emails.map((email) => (
        <li key={email.id}>
          <Link
            href={`/emails/${email.id}`}
            className="block px-4 py-3 hover:bg-slate-50"
          >
            <div className="flex items-start justify-between gap-2">
              <span className="font-medium text-slate-900 line-clamp-1">
                {email.subject || "(no subject)"}
              </span>
              {email.replied && (
                <span className="shrink-0 rounded bg-green-100 px-2 py-0.5 text-xs text-green-800">
                  Replied
                </span>
              )}
            </div>
            <p className="text-sm text-slate-600">{email.fromAddress}</p>
          </Link>
        </li>
      ))}
    </ul>
  );
}
