"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CalculationStrategy, CatalogItem} from "@/lib/types";
import {STRATEGY_DESCRIPTIONS} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {Button} from "@/components/ui/Button";
import {CatalogInfoBanner} from "@/components/catalog/CatalogInfoBanner";

type FormData = {
    name: string;
    unitPrice: number | "";
    unit: string;
    calculationStrategy: CalculationStrategy | "";
};

type PresetItem = {
    name: string;
    unit: string;
    calculationStrategy: CalculationStrategy;
};

type PresetType = "paving" | "electrical" | "painting" | "plumbing" | "masonry" | "tiling" | "general";

const PRESETS: Record<PresetType, { title: string; icon: string; items: PresetItem[] }> = {
    paving: {
        title: "Térkövezés & Földmunka",
        icon: "🧹",
        items: [
            {name: "Tereprendezés és földkiemelés", unit: "m2", calculationStrategy: "DIRECT"},
            {name: "Alapozó zúzottkő réteg terítése", unit: "m3", calculationStrategy: "VOLUME_BY_THICKNESS"},
            {name: "Szegélykő lerakása beton ágyazatba", unit: "fm", calculationStrategy: "ROOM_PERIMETER"},
            {name: "Térkő lerakás (munkadíj)", unit: "m2", calculationStrategy: "WASTE_PERCENTAGE_10"},
        ],
    },
    electrical: {
        title: "Villanyszerelés",
        icon: "⚡",
        items: [
            {name: "Szerelvényezés", unit: "db", calculationStrategy: "DIRECT"},
            {name: "Kábelnyomvonal kiépítés", unit: "fm", calculationStrategy: "DIRECT"},
            {name: "Kismegszakító cseréje", unit: "db", calculationStrategy: "DIRECT"},
        ],
    },
    painting: {
        title: "Festés & Gipszkartonozás",
        icon: "🎨",
        items: [
            {name: "Fal előkészítés/glettelés", unit: "m2", calculationStrategy: "WALL_SURFACE_3X"},
            {name: "Tisztasági festés", unit: "m2", calculationStrategy: "WALL_SURFACE_3X"},
            {name: "Gipszkarton válaszfal", unit: "m2", calculationStrategy: "DIRECT"},
        ],
    },
    plumbing: {
        title: "Víz-, Gáz- & Épületgépészet",
        icon: "🚿",
        items: [
            {name: "Víz- és szennyvízkiállás kiépítése", unit: "db", calculationStrategy: "DIRECT"},
            {name: "Szerelvényezés (csaptelep, mosdó, WC)", unit: "db", calculationStrategy: "DIRECT"},
            {name: "Padlófűtés csőfektetés", unit: "m2", calculationStrategy: "DIRECT"},
            {name: "Radiátor felszerelése és bekötése", unit: "db", calculationStrategy: "DIRECT"},
        ],
    },
    masonry: {
        title: "Kőműves munkák & Szerkezet",
        icon: "🧱",
        items: [
            {name: "Válaszfalazás (Ytong / tégla)", unit: "m2", calculationStrategy: "DIRECT"},
            {name: "Oldalfal vakolása és simítása", unit: "m2", calculationStrategy: "WALL_SURFACE_3X"},
            {name: "Aljzatkiegyenlítés / Estrich betonozás", unit: "m2", calculationStrategy: "DIRECT"},
            {name: "Kézi / gépi bontási munkák", unit: "m3", calculationStrategy: "DIRECT"},
        ],
    },
    tiling: {
        title: "Hidegburkolás",
        icon: "🔲",
        items: [
            {name: "Csempe / járólap fektetés", unit: "m2", calculationStrategy: "WASTE_PERCENTAGE_10"},
            {name: "Kenhető vízszigetelés készítése", unit: "m2", calculationStrategy: "DIRECT"},
            {name: "Lábazat felrakása és fugázás", unit: "fm", calculationStrategy: "ROOM_PERIMETER"},
            {name: "Csempe élvédőzés", unit: "fm", calculationStrategy: "DIRECT"},
        ],
    },
    general: {
        title: "Általános Kivitelezés",
        icon: "🛠️",
        items: [
            {name: "Kiszállási és felmérési díj", unit: "alkalom", calculationStrategy: "DIRECT"},
            {name: "Bontási törmelék / sitt elszállítása", unit: "m3", calculationStrategy: "DIRECT"},
            {name: "Konténer rendelés és ügyintézés", unit: "db", calculationStrategy: "DIRECT"},
            {name: "Segédanyag beszerzés és szállítás", unit: "alkalom", calculationStrategy: "DIRECT"},
        ],
    },
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
    const [loadingPreset, setLoadingPreset] = useState<PresetType | null>(null);
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

    const handleLoadPreset = async (presetType: PresetType) => {
        const preset = PRESETS[presetType];
        setLoadingPreset(presetType);
        setError(null);

        let createdCount = 0;
        try {
            for (const item of preset.items) {
                await apiFetch("/api/catalog", {
                    method: "POST",
                    body: {
                        name: item.name,
                        unitPrice: 0,
                        unit: item.unit,
                        calculationStrategy: item.calculationStrategy,
                    },
                });
                createdCount += 1;
            }

            await loadItems();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : "A sablon betöltése sikertelen.";
            await loadItems();
            setError(
                createdCount > 0
                    ? `A sablon részben betöltődött (${createdCount}/${preset.items.length} tétel). ${message}`
                    : message,
            );
        } finally {
            setLoadingPreset(null);
        }
    };

    if (!isAuthenticated()) {
        return null;
    }

    return (
        <DashboardLayout>
            <div className="mx-auto max-w-5xl px-4 py-6 space-y-6">
                <CatalogInfoBanner/>
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
                ) : items.length === 0 && !showForm ? (
                    <div
                        className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-6 text-center sm:p-8">
                        <h2 className="text-lg font-semibold text-slate-800">
                            Még nincsenek elmentett munkafázisok
                        </h2>
                        <p className="mx-auto mt-2 max-w-2xl text-sm text-slate-600">
                            Tölts be egy előre feltöltött szakmai sablont az azonnali indításhoz, vagy adj hozzá
                            munkafázisokat kézzel!
                        </p>
                        <div className="mt-6 grid gap-3 text-left sm:grid-cols-3">
                            {(Object.entries(PRESETS) as [PresetType, typeof PRESETS[PresetType]][]).map(([presetType, preset]) => (
                                <button
                                    key={presetType}
                                    type="button"
                                    onClick={() => handleLoadPreset(presetType)}
                                    disabled={loadingPreset !== null}
                                    className="rounded-lg border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:border-blue-300 hover:bg-blue-50 hover:shadow focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60"
                                >
                                    <span className="block font-semibold text-slate-900">
                                        <span aria-hidden="true" className="mr-1.5">{preset.icon}</span>
                                        {preset.title}
                                    </span>
                                    <span
                                        className="mt-3 block text-xs font-medium uppercase tracking-wide text-slate-500">
                                        Minta tételek
                                    </span>
                                    <span className="mt-1 block text-sm leading-6 text-slate-600">
                                        {preset.items.map((item) => item.name).join(", ")}
                                    </span>
                                    <span className="mt-3 block text-xs text-slate-500">
                                        {loadingPreset === presetType
                                            ? "Sablon betöltése…"
                                            : `${preset.items.length} tétel · 0 Ft-os, módosítható alapárakkal`}
                                    </span>
                                </button>
                            ))}
                        </div>
                        <div className="my-5 flex items-center gap-3 text-sm text-slate-400">
                            <span className="h-px flex-1 bg-slate-200"/>
                            <span>vagy</span>
                            <span className="h-px flex-1 bg-slate-200"/>
                        </div>
                        <Button
                            type="button"
                            variant="secondary"
                            onClick={() => setShowForm(true)}
                            disabled={loadingPreset !== null}
                        >
                            + Első munkafázis felvétele kézzel
                        </Button>
                    </div>
                ) : items.length > 0 ? (
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
                                    <span className="inline-flex items-center gap-1.5">
                                        AI Számítási Szabály
                                        <span className="group relative inline-flex">
                                            <button
                                                type="button"
                                                aria-label="Információ az AI számítási szabályról"
                                                aria-describedby="catalog-calculation-tooltip"
                                                className="inline-flex h-4 w-4 items-center justify-center rounded-full text-slate-400 hover:text-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                            >
                                                <svg
                                                    aria-hidden="true"
                                                    viewBox="0 0 20 20"
                                                    fill="none"
                                                    className="h-4 w-4"
                                                >
                                                    <circle cx="10" cy="10" r="8" stroke="currentColor"
                                                            strokeWidth="1.5"/>
                                                    <path d="M10 9v5m0-8h.01" stroke="currentColor" strokeWidth="1.5"
                                                          strokeLinecap="round"/>
                                                </svg>
                                            </button>
                                            <span
                                                id="catalog-calculation-tooltip"
                                                role="tooltip"
                                                className="invisible absolute left-0 top-full z-20 mt-2 w-72 rounded-md bg-slate-800 px-3 py-2 text-left text-xs font-normal leading-relaxed text-white opacity-0 shadow-lg transition-opacity group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100"
                                            >
                                                Megadja az AI-nak, hogy milyen matematikai összefüggést használjon a megrendelő által megadott méretekből (pl. m2-ből hogyan számoljon szegélyléc folyómétert vagy zúzottkő köbmétert).
                                            </span>
                                        </span>
                                    </span>
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
                ) : null}
            </div>
        </DashboardLayout>
    );
}