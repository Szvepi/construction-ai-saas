"use client";

import { useEffect, useMemo, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import type { AnalysisResponse, DraftLineItem, EmailDetail, GenerateDraftResponse } from "@/lib/types";
import { Button } from "@/components/ui/Button";

type Props = {
  emailId: number;
  email?: EmailDetail | null;
  onSent: () => void;
};

const currencyFormatter = new Intl.NumberFormat("hu-HU", {
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
});

function makeRow(name = "", unit = "m2", quantity = 1, unitPrice = 0): DraftLineItem {
  return {
    name,
    unit,
    quantity,
    unitPrice,
    subtotal: quantity * unitPrice,
  };
}

export function DraftPanel({ emailId, email, onSent }: Props) {
  const [draftId, setDraftId] = useState<number | null>(null);
  const [clientName, setClientName] = useState("");
  const [reviewWarnings, setReviewWarnings] = useState<string[]>([]);
  const [unmapped, setUnmapped] = useState<string[]>([]);
  const [lineItems, setLineItems] = useState<DraftLineItem[]>([]);
  const [body, setBody] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saveSuccess, setSaveSuccess] = useState<string | null>(null);

  useEffect(() => {
    if (email?.draftEmail) {
      setDraftId(email.draftEmail.draftId);
      setBody(email.draftEmail.draftBody);
      setClientName(email.draftEmail.clientName ?? "");
      setUnmapped(email.draftEmail.unmappedRequests ?? []);
      setReviewWarnings(email.draftEmail.reviewWarnings ?? []);
      setLineItems((email.draftEmail.lineItems ?? []).map((item) => ({
        ...item,
        subtotal: Number(item.quantity ?? 0) * Number(item.unitPrice ?? 0),
      })));
    }
  }, [email]);

  const total = useMemo(
    () => lineItems.reduce((sum, item) => sum + item.subtotal, 0),
    [lineItems],
  );

  const isQuoteRequest = email?.category === "QUOTE_REQUEST";

  async function analyze() {
    setLoading(true);
    setError(null);
    setSaveSuccess(null);
    try {
      const data = await apiFetch<AnalysisResponse>(`/api/drafts/analyze/${emailId}`, { method: "POST" });
      setDraftId(data.draftId);
      setClientName(data.clientName ?? "");
      setReviewWarnings(data.reviewWarnings ?? []);
      setUnmapped(data.unmappedRequests ?? []);
      setLineItems((data.lineItems ?? []).map((item) => ({
        ...item,
        subtotal: Number(item.quantity ?? 0) * Number(item.unitPrice ?? 0),
      })));
      setBody("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Az elemzés sikertelen");
    } finally {
      setLoading(false);
    }
  }

  async function save() {
    if (!draftId) return;
    setLoading(true);
    setError(null);
    setSaveSuccess(null);

    try {
      const payload = {
        clientName: clientName || email?.fromAddress || "Érdeklődő",
        lineItems: lineItems.map((item) => ({
          ...item,
          quantity: Number(item.quantity || 0),
          unitPrice: Number(item.unitPrice || 0),
          subtotal: Number(item.quantity || 0) * Number(item.unitPrice || 0),
        })),
      };

      await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/save`, {
        method: "POST",
        body: payload,
      });

      setSaveSuccess("A módosítások mentésre kerültek!");
      setTimeout(() => setSaveSuccess(null), 3000);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "A mentés sikertelen");
    } finally {
      setLoading(false);
    }
  }

  function updateLineItem(index: number, field: keyof DraftLineItem, value: string | number) {
    setLineItems((current) => {
      const next = [...current];
      const row = { ...next[index] };

      if (field === "quantity" || field === "unitPrice" || field === "subtotal") {
        const numericValue = Number(value) || 0;
        row[field] = numericValue as never;
      } else {
        row[field] = String(value) as never;
      }

      if (field === "quantity" || field === "unitPrice") {
        row.subtotal = Number(row.quantity ?? 0) * Number(row.unitPrice ?? 0);
      }

      next[index] = row;
      return next;
    });
  }

  function addCustomRow() {
    setLineItems((current) => [...current, makeRow("Egyedi tétel", "db", 1, 0)]);
  }

  function removeLineItem(index: number) {
    setLineItems((current) => current.filter((_, i) => i !== index));
  }

  async function finalize() {
    if (!draftId) return;
    setLoading(true);
    setError(null);

    try {
      const payload = {
        clientName: clientName || email?.fromAddress || "Érdeklődő",
        lineItems: lineItems.map((item) => ({
          ...item,
          quantity: Number(item.quantity || 0),
          unitPrice: Number(item.unitPrice || 0),
          subtotal: Number(item.quantity || 0) * Number(item.unitPrice || 0),
        })),
      };

      const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/finalize`, {
        method: "POST",
        body: payload,
      });

      setBody(data.draftBody ?? "");
      setUnmapped(data.unmappedRequests ?? []);
      onSent();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "A véglegesítés sikertelen");
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
      <div className="flex items-center justify-between gap-3">
        <h3 className="font-semibold text-slate-900">Ajánlat előkészítése</h3>
        {!draftId && (
          <Button onClick={analyze} disabled={loading}>
            {loading ? "Elemzés…" : "AI elemzés"}
          </Button>
        )}
      </div>

      {draftId && (
        <>
          {reviewWarnings.length > 0 && (
            <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3">
              <h4 className="text-sm font-semibold text-amber-900">⚠️ AI Figyelmeztetések</h4>
              <ul className="mt-2 space-y-2 text-sm text-amber-900">
                {reviewWarnings.map((warning, index) => (
                  <li key={`${warning}-${index}`} className="rounded bg-amber-100/80 p-2">
                    {warning}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {unmapped.length > 0 && (
            <div className="mt-4 rounded-lg border border-yellow-200 bg-yellow-50 p-3">
              <div className="flex items-center justify-between gap-3">
                <h4 className="text-sm font-semibold text-yellow-900">Nincs a katalógusban</h4>
                <Button variant="secondary" className="!px-3 !py-1.5" onClick={addCustomRow}>
                  + Hozzáadás egyedi áron
                </Button>
              </div>
              <ul className="mt-2 list-disc pl-5 text-sm text-yellow-900">
                {unmapped.map((item, index) => (
                  <li key={`${item}-${index}`}>{item}</li>
                ))}
              </ul>
            </div>
          )}

          <div className="mt-4">
            <div className="mb-2 flex items-center justify-between gap-3">
              <label className="text-sm font-medium text-slate-700">Ügyfél neve</label>
            </div>
            <input
              value={clientName}
              onChange={(event) => setClientName(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-800 focus:border-brand-500 focus:outline-none"
              placeholder="Ügyfél neve"
            />
          </div>

          <div className="mt-4 overflow-x-auto">
            <table className="min-w-full border-separate border-spacing-y-2 text-sm">
              <thead>
                <tr className="text-left text-slate-600">
                  <th className="px-2 py-1">Név</th>
                  <th className="px-2 py-1">Mennyiség</th>
                  <th className="px-2 py-1">Egység</th>
                  <th className="px-2 py-1">Egységár</th>
                  <th className="px-2 py-1">Összeg</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {lineItems.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-2 py-4 text-center text-slate-500">
                      Nincs tétel az elemzéshez.
                    </td>
                  </tr>
                ) : (
                  lineItems.map((item, index) => (
                    <tr key={index} className="rounded-lg bg-slate-50 align-top">
                      <td className="rounded-l-lg px-2 py-2">
                        <input
                          value={item.name}
                          onChange={(event) => updateLineItem(index, "name", event.target.value)}
                          className="w-full rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                        />
                      </td>
                      <td className="px-2 py-2">
                        <input
                          type="number"
                          min="0"
                          step="0.01"
                          value={item.quantity}
                          onChange={(event) => updateLineItem(index, "quantity", event.target.value)}
                          className="w-24 rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                        />
                      </td>
                      <td className="px-2 py-2">
                        <input
                          value={item.unit}
                          onChange={(event) => updateLineItem(index, "unit", event.target.value)}
                          className="w-20 rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                        />
                      </td>
                      <td className="px-2 py-2">
                        <input
                          type="number"
                          min="0"
                          step="100"
                          value={item.unitPrice}
                          onChange={(event) => updateLineItem(index, "unitPrice", event.target.value)}
                          className="w-28 rounded border border-slate-300 bg-white px-2 py-1.5 focus:border-brand-500 focus:outline-none"
                        />
                      </td>
                      <td className="px-2 py-2 text-right font-medium text-slate-700">
                        {currencyFormatter.format(item.subtotal)} Ft
                      </td>
                      <td className="rounded-r-lg px-2 py-2 text-right">
                        <button
                          type="button"
                          className="text-sm text-red-600 hover:text-red-700"
                          onClick={() => removeLineItem(index)}
                        >
                          Töröl
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          <div className="mt-4 flex items-center justify-between rounded-lg bg-slate-100 px-3 py-2">
            <span className="text-sm text-slate-600">Összesen</span>
            <span className="text-lg font-semibold text-slate-900">{currencyFormatter.format(total)} Ft</span>
          </div>

          <div className="mt-5 flex gap-2">
            <Button variant="secondary" onClick={addCustomRow}>
              + Új sor
            </Button>
            <Button onClick={save} disabled={loading} variant="secondary">
              {loading ? "Mentés…" : "Mentés"}
            </Button>
            <Button onClick={finalize} disabled={loading || lineItems.length === 0}>
              {loading ? "Küldés…" : "Válasz elküldése"}
            </Button>
          </div>
        </>
      )}

      {body && (
        <div className="mt-5 rounded-lg border border-slate-200 bg-slate-50 p-3">
          <div
            className="prose prose-sm max-w-none text-sm text-slate-800"
            dangerouslySetInnerHTML={{ __html: body }}
          />
        </div>
      )}

      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      {saveSuccess && <p className="mt-3 text-sm text-green-600">✓ {saveSuccess}</p>}
    </section>
  );
}
