"use client";

import { useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import type { EmailDetail, GenerateDraftResponse } from "@/lib/types";
import { Button } from "@/components/ui/Button";

type Props = {
  emailId: number;
  email?: EmailDetail | null;
  onSent: () => void;
};

export function DraftPanel({ emailId, email, onSent }: Props) {
  const [draftId, setDraftId] = useState<number | null>(null);
  const [body, setBody] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isQuoteRequest = email?.category === "QUOTE_REQUEST";

  async function generate() {
    setLoading(true);
    setError(null);
    try {
      const data = await apiFetch<GenerateDraftResponse>(
        `/api/emails/${emailId}/drafts/generate`,
        { method: "POST" },
      );
      setDraftId(data.draftId);
      setBody(data.draftBody);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "A generálás sikertelen");
    } finally {
      setLoading(false);
    }
  }

  async function send() {
    if (!draftId) return;
    setLoading(true);
    setError(null);
    try {
      await apiFetch(`/api/emails/${emailId}/drafts/${draftId}/send`, {
        method: "POST",
        body: { draftBody: body },
      });
      onSent();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Küldés sikertelen");
    } finally {
      setLoading(false);
    }
  }

  if (!isQuoteRequest) {
    return (
      <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-4">
        <p className="text-sm text-amber-800">
          Az AI válasz generálása csak árajánlat kérésekhez érhető el.
        </p>
      </section>
    );
  }

  return (
    <section className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
      <h3 className="font-semibold">Válaszvázlat</h3>
      {!draftId ? (
        <Button className="mt-3" onClick={generate} disabled={loading}>
          {loading ? "Generálás…" : "Válasz generálása"}
        </Button>
      ) : (
        <>
          <textarea
            className="mt-3 w-full min-h-[160px] rounded-lg border border-slate-300 p-3 text-sm"
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
          <div className="mt-3 flex gap-2">
            <Button onClick={send} disabled={loading || !body.trim()}>
              Küldés
            </Button>
          </div>
        </>
      )}
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
    </section>
  );
}
