"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CalculationStrategy, CatalogItem} from "@/lib/types";
import {STRATEGY_DESCRIPTIONS} from "@/lib/types";
import {AppHeader} from "@/components/layout/AppHeader";
import {DashboardNav} from "@/components/layout/DashboardNav";
import {Button} from "@/components/ui/Button";

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
            setError(err instanceof ApiError ? err.message : "Az árjegyzék betöltése sikertelen.");
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
            setError(err instanceof ApiError ? err.message : "A munkafázis mentése sikertelen.");
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm("Biztosan törölni szeretnéd ezt a munkafázist?")) return;

        try {
            await apiFetch(`/api/catalog/${id}`, {method: "DELETE"});
            await loadItems();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "A munkafázis törlése sikertelen.");
        }
    };

    if (!isAuthenticated()) {
        return null;
    }

    return (
        <div className="min-h-screen bg-slate-50">
            <AppHeader/>
            <DashboardNav/>
            <div className="mx-auto max-w-5xl px-4 py-6 space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-3xl font-bold text-slate-900">Árjegyzék és Munkafázisok</h1>
                        <p className="mt-1 text-sm text-slate-500">
                            Állítsd be az alapértelmezett díjaidat és kalkulációs szabályaidat az automatikus
                            ajánlatkészítéshez.
                        </p>
                    </div>
                    <Button onClick={() => setShowForm(true)} disabled={showForm}>
                        Új munkafázis felvétele
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
                            {editingId ? "Munkafázis szerkesztése" : "Új munkafázis rögzítése"}
                        </h2>
                        <form onSubmit={handleSubmit} className="space-y-4">
                            <div>
                                <label className="block text-sm font-medium text-slate-700">
                                    Munkafázis megnevezése *
                                </label>
                                <input
                                    type="text"
                                    required
                                    value={formData.name}
                                    onChange={(e) =>
                                        setFormData({...formData, name: e.target.value})
                                    }
                                    className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                    placeholder="pl. Térkő lerakás munkadíj, Zúzottkő alapozás"
                                />
                            </div>

                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-sm font-medium text-slate-700">
                                        Egységár (Netto Ft) *
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
                                        placeholder="0"
                                    />
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-slate-700">
                                        Mértékegység *
                                    </label>
                                    <select
                                        required
                                        value={formData.unit}
                                        onChange={(e) =>
                                            setFormData({...formData, unit: e.target.value})
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
                                    Kalkulációs logika
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
                                    <option value="">-- Válassz kalkulációs logikát --</option>
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
                                    {submitting ? "Mentés…" : editingId ? "Módosítások mentése" : "Munkafázis rögzítése"}
                                </Button>
                                <Button
                                    type="button"
                                    onClick={resetForm}
                                    disabled={submitting}
                                    variant="secondary"
                                >
                                    Mégse
                                </Button>
                            </div>
                        </form>
                    </div>
                )}

                {loading ? (
                    <div className="text-center text-slate-500">Árjegyzék betöltése…</div>
                ) : items.length === 0 ? (
                    <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center">
                        <p className="text-slate-600 font-medium">Még nincsenek elmentett munkafázisok</p>
                        <p className="mt-1 text-sm text-slate-500">
                            Kattints az &quot;Új munkafázis felvétele&quot; gombra az első tételed rögzítéséhez.
                        </p>
                    </div>
                ) : (
                    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
                        <table className="w-full">
                            <thead className="bg-slate-50 border-b border-slate-200">
                            <tr>
                                <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                                    Munkafázis / Megnevezés
                                </th>
                                <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                                    Egységár
                                </th>
                                <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                                    Mértékegység
                                </th>
                                <th className="px-6 py-3 text-left text-sm font-semibold text-slate-900">
                                    Kalkulációs logika
                                </th>
                                <th className="px-6 py-3 text-right text-sm font-semibold text-slate-900">
                                    Műveletek
                                </th>
                            </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-200">
                            {items.map((item) => (
                                <tr
                                    key={item.id}
                                    className="hover:bg-slate-50 transition-colors"
                                >
                                    <td className="px-6 py-4 text-sm font-medium text-slate-900">
                                        {item.name}
                                    </td>
                                    <td className="px-6 py-4 text-sm text-slate-900">
                                        {item.unitPrice.toLocaleString("hu-HU")} Ft
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
                                            Szerkesztés
                                        </button>
                                        <button
                                            onClick={() => handleDelete(item.id)}
                                            disabled={showForm}
                                            className="inline-block text-red-600 hover:text-red-800 disabled:text-slate-400 transition-colors font-medium"
                                        >
                                            Törlés
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