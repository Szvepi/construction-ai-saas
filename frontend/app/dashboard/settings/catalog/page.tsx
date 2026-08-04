"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api";
import { isAuthenticated } from "@/lib/auth";
import type { CatalogItem, CalculationStrategy } from "@/lib/types";
import { STRATEGY_DESCRIPTIONS } from "@/lib/types";
import { AppHeader } from "@/components/layout/AppHeader";
import { DashboardNav } from "@/components/layout/DashboardNav";
import { Button } from "@/components/ui/Button";

type FormData = {
  name: string;
  unitPrice: number | "";
  unit: string;
  calculationStrategy: CalculationStrategy | "";
};

const UNITS = ["m2", "m3", "db", "fm"];
const STRATEGIES: CalculationStrategy[] = [
  "DIRECT",
  "WALL_SURFACE_3X",
  "ROOM_PERIMETER",
  "VOLUME_BY_THICKNESS",
  "WASTE_PERCENTAGE_10",
];

export default function CatalogSettingsPage() {
  const router = useRouter();
  const [items, setItems] = useState<CatalogItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formData, setFormData] = useState<FormData>({
    name: "",
    unitPrice: "",
    unit: "m2",
    calculationStrategy: "DIRECT",
  });

  const loadItems = useCallback(async () => {
    try {
      const data = await apiFetch<CatalogItem[]>("/api/catalog");
      setItems(data);
      setError(null);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/auth/login");
        return;
      }
      setError(err instanceof ApiError ? err.message : "Failed to load catalog");
    }
  }, [router]);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.push("/auth/login");
      return;
    }
    loadItems().finally(() => setLoading(false));
  }, [router, loadItems]);

  const resetForm = () => {
    setFormData({
      name: "",
      unitPrice: "",
      unit: "m2",
      calculationStrategy: "DIRECT",
    });
    setEditingId(null);
    setShowForm(false);
  };

  const handleEdit = (item: CatalogItem) => {
    setFormData({
      name: item.name,
      unitPrice: item.unitPrice,
      unit: item.unit,
      calculationStrategy: item.calculationStrategy || "DIRECT",
    });
    setEditingId(item.id);
    setShowForm(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    try {
      const payload = {
        name: formData.name,
        unitPrice: formData.unitPrice,
        unit: formData.unit,
        calculationStrategy: formData.calculationStrategy || null,
      };

      if (editingId) {
        await apiFetch(`/api/catalog/${editingId}`, {
          method: "PUT",
          body: payload,
        });
      } else {
        await apiFetch("/api/catalog", {
          method: "POST",
          body: payload,
        });
      }

      resetForm();
      await loadItems();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save item");
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm("Are you sure you want to delete this item?")) return;

    try {
      await apiFetch(`/api/catalog/${id}`, { method: "DELETE" });
      await loadItems();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete item");
    }
  };

  if (!isAuthenticated()) {
    return null;
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <DashboardNav />
      <div className="mx-auto max-w-5xl px-4 py-6 space-y-6">
        <div className="flex items-center justify-between">
          <h1 className="text-3xl font-bold text-slate-900">Catalog Settings</h1>
          <Button onClick={() => setShowForm(true)} disabled={showForm}>
            Add New Item
          </Button>
        </div>

        {error && (
          <div className="rounded-md bg-red-50 p-4 text-sm text-red-700">
            {error}
          </div>
        )}

        {showForm && (
          <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="mb-4 text-lg font-semibold text-slate-900">
              {editingId ? "Edit Item" : "Add New Catalog Item"}
            </h2>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700">
                  Item Name *
                </label>
                <input
                  type="text"
                  required
                  value={formData.name}
                  onChange={(e) =>
                    setFormData({ ...formData, name: e.target.value })
                  }
                  className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  placeholder="e.g., Internal Wall Painting"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700">
                    Unit Price *
                  </label>
                  <input
                    type="number"
                    required
                    step="0.01"
                    min="0"
                    value={formData.unitPrice}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        unitPrice: e.target.value === "" ? "" : parseFloat(e.target.value),
                      })
                    }
                    className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                    placeholder="0.00"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700">
                    Unit *
                  </label>
                  <select
                    required
                    value={formData.unit}
                    onChange={(e) =>
                      setFormData({ ...formData, unit: e.target.value })
                    }
                    className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  >
                    {UNITS.map((u) => (
                      <option key={u} value={u}>
                        {u}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700">
                  Calculation Strategy
                </label>
                <select
                  value={formData.calculationStrategy}
                  onChange={(e) =>
                    setFormData({
                      ...formData,
                      calculationStrategy: e.target.value as CalculationStrategy | "",
                    })
                  }
                  className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                >
                  <option value="">-- Select a strategy --</option>
                  {STRATEGIES.map((strategy) => (
                    <option key={strategy} value={strategy}>
                      {STRATEGY_DESCRIPTIONS[strategy]}
                    </option>
                  ))}
                </select>
              </div>

              <div className="flex gap-3 pt-4">
                <Button
                  type="submit"
                  disabled={submitting}
                  className="bg-blue-600 hover:bg-blue-700"
                >
                  {submitting ? "Saving…" : editingId ? "Update Item" : "Add Item"}
                </Button>
                <Button
                  type="button"
                  onClick={resetForm}
                  disabled={submitting}
                  variant="secondary"
                >
                  Cancel
                </Button>
              </div>
            </form>
          </div>
        )}

        {loading ? (
          <div className="text-center text-slate-500">Loading catalog…</div>
        ) : items.length === 0 ? (
          <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center">
            <p className="text-slate-600">No catalog items yet</p>
            <p className="text-sm text-slate-500">
              Click "Add New Item" to create your first price book entry.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
            <table className="w-full">
              <thead className="bg-slate-50 border-b border-slate-200">
                <tr>
                  <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                    Name
                  </th>
                  <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                    Unit Price
                  </th>
                  <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                    Unit
                  </th>
                  <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                    Strategy
                  </th>
                  <th className="px-6 py-3 text-right text-sm font-semibold text-slate-900">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {items.map((item) => (
                  <tr
                    key={item.id}
                    className="hover:bg-slate-50 transition-colors"
                  >
                    <td className="px-6 py-4 text-sm text-slate-900">
                      {item.name}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-900">
                      {item.unitPrice.toFixed(2)}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-900">
                      {item.unit}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">
                      {item.calculationStrategy
                        ? STRATEGY_DESCRIPTIONS[item.calculationStrategy]
                        : "—"}
                    </td>
                    <td className="px-6 py-4 text-right text-sm space-x-2">
                      <button
                        onClick={() => handleEdit(item)}
                        disabled={showForm && editingId === item.id}
                        className="inline-block text-blue-600 hover:text-blue-800 disabled:text-slate-400 transition-colors font-medium"
                      >
                        Edit
                      </button>
                      <button
                        onClick={() => handleDelete(item.id)}
                        disabled={showForm}
                        className="inline-block text-red-600 hover:text-red-800 disabled:text-slate-400 transition-colors font-medium"
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
