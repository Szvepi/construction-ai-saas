"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CatalogItem, CatalogItemType} from "@/lib/types";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {Button} from "@/components/ui/Button";
import {InfoIcon} from "@/components/ui/InfoIcon";
import {CatalogInfoBanner} from "@/components/catalog/CatalogInfoBanner";

type FormData = {
    name: string;
    netSellingUnitPrice: number | "";
    unit: string;
    itemType: CatalogItemType | "";
    netProcurementUnitCost: number | "";
    estimatedOwnLaborUnitCost: number | "";
};

type PresetItem = {
    name: string;
    unit: string;
    itemType: CatalogItemType;
};

type PresetType = "paving" | "electrical" | "painting" | "plumbing" | "masonry" | "tiling" | "general";

const PRESETS: Record<PresetType, { title: string; icon: string; items: PresetItem[] }> = {
    paving: {
        title: "Térkövezés & Földmunka",
        icon: "🧹",
        items: [
            {name: "Tereprendezés és földkiemelés", unit: "m2", itemType: "LABOR"},
            {name: "Alapozó zúzottkő réteg", unit: "m3", itemType: "MATERIAL"},
            {name: "Szegélykő", unit: "m", itemType: "MATERIAL"},
            {name: "Szegélykő lerakási munkadíj", unit: "m", itemType: "LABOR"},
            {name: "Térkőanyag", unit: "m2", itemType: "MATERIAL"},
            {name: "Térkő lerakás", unit: "m2", itemType: "LABOR"},
        ],
    },
    electrical: {
        title: "Villanyszerelés",
        icon: "⚡",
        items: [
            {name: "Konnektorszerelés", unit: "db", itemType: "LABOR"},
            {name: "Kapcsolószerelés", unit: "db", itemType: "LABOR"},
            {name: "Lámpakiállás", unit: "db", itemType: "LABOR"},
            {name: "Szerelvényanyag", unit: "db", itemType: "MATERIAL"},
        ],
    },
    painting: {
        title: "Festés & Gipszkartonozás",
        icon: "🎨",
        items: [
            {name: "Fal előkészítés/glettelés", unit: "m2", itemType: "LABOR"},
            {name: "Falfestési munkadíj", unit: "m2", itemType: "LABOR"},
            {name: "Mennyezetfestési munkadíj", unit: "m2", itemType: "LABOR"},
            {name: "Festékanyag", unit: "l", itemType: "MATERIAL"},
            {name: "Gipszkarton válaszfal", unit: "m2", itemType: "COMBINED"},
        ],
    },
    plumbing: {
        title: "Víz-, Gáz- & Épületgépészet",
        icon: "🚿",
        items: [
            {name: "Víz- és szennyvízkiállás kiépítése", unit: "db", itemType: "LABOR"},
            {name: "Szerelvényezés (csaptelep, mosdó, WC)", unit: "db", itemType: "COMBINED"},
            {name: "Padlófűtés csőfektetés", unit: "m2", itemType: "LABOR"},
            {name: "Radiátor felszerelése és bekötése", unit: "db", itemType: "COMBINED"},
        ],
    },
    masonry: {
        title: "Kőműves munkák & Szerkezet",
        icon: "🧱",
        items: [
            {name: "Válaszfalazás (Ytong / tégla)", unit: "m2", itemType: "COMBINED"},
            {name: "Oldalfal vakolása és simítása", unit: "m2", itemType: "LABOR"},
            {name: "Aljzatkiegyenlítés / Estrich betonozás", unit: "m2", itemType: "LABOR"},
            {name: "Kézi / gépi bontási munkák", unit: "m3", itemType: "LABOR"},
        ],
    },
    tiling: {
        title: "Hidegburkolás",
        icon: "🔲",
        items: [
            {name: "Csempe / járólap fektetése", unit: "m2", itemType: "LABOR"},
            {name: "Burkolóanyag", unit: "m2", itemType: "MATERIAL"},
            {name: "Kenhető vízszigetelés készítése", unit: "m2", itemType: "LABOR"},
            {name: "Lábazat felrakása és fugázás", unit: "m", itemType: "LABOR"},
            {name: "Csempe élvédőzés", unit: "m", itemType: "LABOR"},
        ],
    },
    general: {
        title: "Általános Kivitelezés",
        icon: "🛠️",
        items: [
            {name: "Kiszállási és felmérési díj", unit: "alkalom", itemType: "OTHER"},
            {name: "Bontási törmelék / sitt elszállítása", unit: "m3", itemType: "OTHER"},
            {name: "Konténer rendelés és ügyintézés", unit: "db", itemType: "OTHER"},
            {name: "Segédanyag beszerzés és szállítás", unit: "alkalom", itemType: "MATERIAL"},
        ],
    },
};

const UNITS = ["m2", "m3", "m", "cm", "l", "db", "kg", "h", "alkalom"];
const ITEM_TYPE_LABELS: Record<CatalogItemType, string> = {
    MATERIAL: "Anyag",
    LABOR: "Munkadíj",
    COMBINED: "Anyag + munkadíj",
    OTHER: "Egyéb díj",
};
const ITEM_TYPE_BADGE_STYLES: Record<CatalogItemType, string> = {
    MATERIAL: "bg-emerald-50 text-emerald-700",
    LABOR: "bg-blue-50 text-blue-700",
    COMBINED: "bg-violet-50 text-violet-700",
    OTHER: "bg-amber-50 text-amber-700",
};
const ITEM_TYPE_OPTIONS: {
    value: CatalogItemType;
    icon: string;
    description: string;
    example: string;
    guidance: string;
    preview: string;
}[] = [
    {
        value: "MATERIAL",
        icon: "⬡",
        description: "Csak az anyag eladási ára. A munkadíj nincs benne.",
        example: "Térkő – 5 500 Ft/m²",
        guidance: "A megadott ár csak az anyag eladási ára. A munkadíjat külön tételként számold fel.",
        preview: "Nettó anyagár. A munkadíjat nem tartalmazza.",
    },
    {
        value: "LABOR",
        icon: "⚒",
        description: "Csak a munkavégzés díja. Az anyagot külön számolod.",
        example: "Térkövezés munkadíja – 6 000 Ft/m²",
        guidance: "A megadott ár kizárólag a munkavégzésért felszámított díj. Az anyag nincs benne.",
        preview: "Nettó munkadíj. Az anyagköltséget nem tartalmazza.",
    },
    {
        value: "COMBINED",
        icon: "▤",
        description: "Az anyagot és a kivitelezést együtt tartalmazó egységár.",
        example: "Térkövezés anyaggal – 15 000 Ft/m²",
        guidance: "Ez az ár tartalmazza az anyagot és a munkadíjat is. Ugyanazt az anyagot vagy munkát ne számold fel még egyszer külön tételként.",
        preview: "Nettó kombinált egységár. Az anyagot és a munkadíjat is tartalmazza.",
    },
    {
        value: "OTHER",
        icon: "◇",
        description: "Kiszállítás, gépbérlés vagy más külön felszámított díj.",
        example: "Kiszállási díj – 12 000 Ft/alkalom",
        guidance: "Külön felszámított díj, például kiszállítás vagy gépbérlés. A megadott mértékegységhez tartozó árat add meg.",
        preview: "Nettó egyéb díj az adott mértékegységre.",
    },
];
const UNIT_LABELS: Record<string, string> = {
    m2: "m²",
    m3: "m³",
    m: "fm",
    cm: "cm",
    l: "l",
    db: "db",
    kg: "kg",
    h: "óra",
    alkalom: "alkalom",
};
const UNIT_DESCRIPTIONS: Record<string, string> = {
    m2: "négyzetméter",
    m3: "köbméter",
    m: "folyóméter",
    cm: "centiméter",
    l: "liter",
    db: "darab",
    kg: "kilogramm",
    h: "munkaóra",
    alkalom: "alkalom",
};

function formatPrice(value: number): string {
    return value.toLocaleString("hu-HU", {maximumFractionDigits: 2});
}

function createEmptyFormData(): FormData {
    return {
        name: "",
        netSellingUnitPrice: "",
        unit: "m2",
        itemType: "",
        netProcurementUnitCost: "",
        estimatedOwnLaborUnitCost: "",
    };
}

const EXAMPLE_QUANTITIES: Record<string, number> = {
    m2: 45,
    m3: 6,
    m: 30,
    cm: 100,
    l: 10,
    db: 10,
    kg: 100,
    h: 8,
    alkalom: 1,
};

export default function CatalogSettingsPage() {
    const router = useRouter();
    const [items, setItems] = useState<CatalogItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<number | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [loadingPreset, setLoadingPreset] = useState<PresetType | null>(null);
    const [itemTypeError, setItemTypeError] = useState<string | null>(null);
    const [formData, setFormData] = useState<FormData>(createEmptyFormData());

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
        setFormData(createEmptyFormData());
        setEditingId(null);
        setItemTypeError(null);
        setError(null);
        setShowForm(false);
    };

    const startNewItem = () => {
        setFormData(createEmptyFormData());
        setEditingId(null);
        setItemTypeError(null);
        setError(null);
        setShowForm(true);
    };

    const handleEdit = (item: CatalogItem) => {
        setFormData({
            name: item.name,
            netSellingUnitPrice: item.netSellingUnitPrice,
            unit: item.unit,
            itemType: item.itemType,
            netProcurementUnitCost: item.netProcurementUnitCost ?? "",
            estimatedOwnLaborUnitCost: item.estimatedOwnLaborUnitCost ?? "",
        });
        setEditingId(item.id);
        setItemTypeError(null);
        setError(null);
        setShowForm(true);
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        const selectedItemType = formData.itemType;
        if (!selectedItemType) {
            setItemTypeError("Válaszd ki, mit tartalmaz az árjegyzéktétel.");
            return;
        }
        if (!e.currentTarget.reportValidity()) {
            return;
        }

        setSubmitting(true);
        setError(null);
        setItemTypeError(null);

        try {
            const payload = {
                name: formData.name,
                netSellingUnitPrice: formData.netSellingUnitPrice,
                unit: formData.unit,
                itemType: selectedItemType,
                netProcurementUnitCost: formData.netProcurementUnitCost === "" ? null : formData.netProcurementUnitCost,
                estimatedOwnLaborUnitCost: formData.estimatedOwnLaborUnitCost === "" ? null : formData.estimatedOwnLaborUnitCost,
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
            setError(err instanceof ApiError ? err.message : "Az árjegyzéktétel mentése sikertelen.");
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm("Biztosan törölni szeretnéd ezt az árjegyzéktételt?")) return;

        try {
            await apiFetch(`/api/catalog/${id}`, {method: "DELETE"});
            await loadItems();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az árjegyzéktétel törlése sikertelen.");
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
                        netSellingUnitPrice: 0,
                        unit: item.unit,
                        itemType: item.itemType,
                        netProcurementUnitCost: null,
                        estimatedOwnLaborUnitCost: null,
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

    const selectedType = ITEM_TYPE_OPTIONS.find((option) => option.value === formData.itemType);
    const previewUnitPrice =
        typeof formData.netSellingUnitPrice === "number" &&
        Number.isFinite(formData.netSellingUnitPrice) &&
        formData.netSellingUnitPrice >= 0 &&
        Math.abs(formData.netSellingUnitPrice * 100 - Math.round(formData.netSellingUnitPrice * 100)) < 1e-8
            ? formData.netSellingUnitPrice
            : null;
    const previewUnit = UNIT_LABELS[formData.unit] ?? formData.unit;
    const exampleQuantity = EXAMPLE_QUANTITIES[formData.unit] ?? 1;
    const calculatedPreviewTotal = previewUnitPrice === null ? null : previewUnitPrice * exampleQuantity;
    const previewTotal =
        calculatedPreviewTotal !== null && Number.isFinite(calculatedPreviewTotal)
            ? calculatedPreviewTotal
            : null;
    const previewCalculation =
        previewUnitPrice === null || previewTotal === null
            ? "Add meg az egységárat az előnézethez."
            : `${exampleQuantity} × ${formatPrice(previewUnitPrice)} Ft = ${formatPrice(previewTotal)} Ft`;

    return (
        <DashboardLayout>
            <div className="mx-auto max-w-7xl space-y-6 px-4 py-6">
                <header className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900 sm:text-3xl">
                            Árjegyzék – anyagok és munkadíjak
                        </h1>
                        <p className="mt-1 max-w-3xl text-sm text-slate-600">
                            Állítsd be, milyen tételekkel és árakkal dolgozol. A MIRA ezeket használja az automatikus
                            ajánlatkészítéshez.
                        </p>
                    </div>
                    <Button onClick={startNewItem} disabled={showForm} className="shrink-0">
                        Új árjegyzéktétel
                    </Button>
                </header>

                <CatalogInfoBanner/>

                {error && (
                    <div role="alert" className="rounded-md bg-red-50 p-4 text-sm text-red-700">
                        {error}
                    </div>
                )}

                {showForm && (
                    <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
                        <h2 className="mb-5 text-xl font-semibold text-slate-900">
                            {editingId ? "Árjegyzéktétel szerkesztése" : "Új árjegyzéktétel"}
                        </h2>
                        <form
                            noValidate
                            onSubmit={handleSubmit}
                            className="grid gap-6 lg:grid-cols-[minmax(0,1.7fr)_minmax(18rem,0.9fr)]"
                        >
                            <div className="space-y-5">
                                <fieldset
                                    aria-required={true}
                                    aria-invalid={itemTypeError ? "true" : undefined}
                                    aria-describedby={itemTypeError ? "catalog-item-type-error" : undefined}
                                >
                                    <legend className="mb-3 text-sm font-semibold text-slate-900">
                                        Mit tartalmaz az ajánlati tétel? *
                                    </legend>
                                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                                        {ITEM_TYPE_OPTIONS.map((option) => {
                                            const selected = formData.itemType === option.value;
                                            return (
                                                <label key={option.value} className="relative block cursor-pointer">
                                                    <input
                                                        className="peer sr-only"
                                                        type="radio"
                                                        name="itemType"
                                                        value={option.value}
                                                        checked={selected}
                                                        onChange={() => {
                                                            setFormData({...formData, itemType: option.value});
                                                            setItemTypeError(null);
                                                            setError(null);
                                                        }}
                                                    />
                                                    <span
                                                        className={`relative flex h-full min-h-44 flex-col rounded-lg border p-3 transition ${
                                                            selected
                                                                ? "border-blue-600 bg-blue-50/70 ring-2 ring-blue-100"
                                                                : "border-slate-200 bg-white hover:border-blue-300 hover:bg-blue-50/30"
                                                        } peer-focus-visible:outline-none peer-focus-visible:ring-2 peer-focus-visible:ring-blue-500`}
                                                    >
                                                        <span className="mb-2 flex items-center justify-between">
                                                            <span aria-hidden="true"
                                                                  className="text-xl text-blue-700">{option.icon}</span>
                                                            {selected && (
                                                                <span aria-label="Kiválasztva"
                                                                      className="flex h-5 w-5 items-center justify-center rounded-full bg-blue-600 text-xs text-white">
                                                                    ✓
                                                                </span>
                                                            )}
                                                        </span>
                                                        <span className="text-sm font-semibold text-slate-900">
                                                            {ITEM_TYPE_LABELS[option.value]}
                                                        </span>
                                                        <span className="mt-1 text-xs leading-5 text-slate-600">
                                                            {option.description}
                                                        </span>
                                                        <span
                                                            className="mt-auto pt-3 text-xs font-medium text-slate-500">
                                                            {option.example}
                                                        </span>
                                                    </span>
                                                </label>
                                            );
                                        })}
                                    </div>
                                    <p className="mt-2 text-xs text-slate-500">
                                        A példák csak szemléltető árak, nem kerülnek automatikusan a mezőkbe.
                                    </p>
                                    {itemTypeError && (
                                        <p id="catalog-item-type-error" role="alert"
                                           className="mt-2 text-sm font-medium text-red-700">
                                            {itemTypeError}
                                        </p>
                                    )}
                                </fieldset>

                                <div role="status" aria-live="polite"
                                     className="flex gap-3 rounded-lg border border-blue-100 bg-blue-50/70 p-3 text-sm text-blue-950">
                                    <InfoIcon className="h-5 w-5 text-xs text-blue-700"/>
                                    <p>{selectedType?.guidance ?? "Válaszd ki, mit tartalmaz az árjegyzéktétel."}</p>
                                </div>

                                <div className="space-y-4">
                                    <div>
                                        <label htmlFor="catalog-item-name"
                                               className="block text-sm font-medium text-slate-700">
                                            Tétel megnevezése *
                                        </label>
                                        <input
                                            id="catalog-item-name"
                                            type="text"
                                            required
                                            value={formData.name}
                                            onChange={(e) => setFormData({...formData, name: e.target.value})}
                                            className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                            placeholder="pl. Tereprendezés és földkiemelés"
                                        />
                                    </div>

                                    <div className="grid gap-4 sm:grid-cols-2">
                                        <div>
                                            <label htmlFor="catalog-item-price"
                                                   className="block text-sm font-medium text-slate-700">
                                                Nettó eladási egységár (Ft) *
                                            </label>
                                            <input
                                                id="catalog-item-price"
                                                type="number"
                                                required
                                                step="0.01"
                                                min="0"
                                                value={formData.netSellingUnitPrice}
                                                onChange={(e) =>
                                                    setFormData({
                                                        ...formData,
                                                        netSellingUnitPrice: e.target.value === "" ? "" : parseFloat(e.target.value),
                                                    })
                                                }
                                                className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                                placeholder="pl. 1 000"
                                            />
                                            <p className="mt-1 text-xs leading-5 text-slate-500">
                                                Ezt az összeget számolod fel az ügyfélnek egy mértékegység után. Nem a
                                                beszerzési vagy önköltségi ár.
                                            </p>
                                        </div>

                                        <div>
                                            <label htmlFor="catalog-item-unit"
                                                   className="block text-sm font-medium text-slate-700">
                                                Mértékegység *
                                            </label>
                                            <select
                                                id="catalog-item-unit"
                                                required
                                                value={formData.unit}
                                                onChange={(e) => setFormData({...formData, unit: e.target.value})}
                                                className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                            >
                                                {UNITS.map((unit) => (
                                                    <option key={unit} value={unit}>
                                                        {UNIT_DESCRIPTIONS[unit] === unit
                                                            ? UNIT_LABELS[unit]
                                                            : `${UNIT_LABELS[unit]} – ${UNIT_DESCRIPTIONS[unit]}`}
                                                    </option>
                                                ))}
                                            </select>
                                            <p className="mt-1 text-xs text-slate-500">
                                                Válaszd ki, milyen egységben számolod el ezt a tételt.
                                            </p>
                                        </div>
                                    </div>
                                </div>

                                {formData.itemType && (
                                    <details className="rounded-lg bg-slate-50 p-4">
                                        <summary className="cursor-pointer font-semibold text-slate-800">
                                            Belső költségek (opcionális)
                                        </summary>
                                        <p className="mt-2 text-sm text-slate-600">
                                            Ezek az adatok a saját költségeid és árrésed követésére szolgálnak. Az ügyfélnek
                                            felszámított ár a nettó eladási egységár.
                                        </p>
                                        <div className="mt-4 grid gap-4 sm:grid-cols-2">
                                            {(formData.itemType === "MATERIAL" || formData.itemType === "COMBINED") && (
                                                <div>
                                                    <label htmlFor="catalog-procurement-cost"
                                                           className="block text-sm font-medium text-slate-700">
                                                        Beszerzési költség / egység
                                                    </label>
                                                    <input
                                                        id="catalog-procurement-cost"
                                                        type="number"
                                                        step="0.01"
                                                        min="0"
                                                        value={formData.netProcurementUnitCost}
                                                        onChange={(e) => setFormData({
                                                            ...formData,
                                                            netProcurementUnitCost: e.target.value === "" ? "" : parseFloat(e.target.value)
                                                        })}
                                                        className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                                        placeholder="pl. 600"
                                                    />
                                                </div>
                                            )}
                                            {(formData.itemType === "LABOR" || formData.itemType === "COMBINED") && (
                                                <div>
                                                    <label htmlFor="catalog-own-labor-cost"
                                                           className="block text-sm font-medium text-slate-700">
                                                        Saját munkaköltség / egység
                                                    </label>
                                                    <input
                                                        id="catalog-own-labor-cost"
                                                        type="number"
                                                        step="0.01"
                                                        min="0"
                                                        value={formData.estimatedOwnLaborUnitCost}
                                                        onChange={(e) => setFormData({
                                                            ...formData,
                                                            estimatedOwnLaborUnitCost: e.target.value === "" ? "" : parseFloat(e.target.value)
                                                        })}
                                                        className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 placeholder-slate-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                                        placeholder="pl. 750"
                                                    />
                                                </div>
                                            )}
                                            {formData.itemType === "OTHER" && (
                                                <p className="text-sm text-slate-600">
                                                    Ennél a tételtípusnál nincs külön belső költségmező.
                                                </p>
                                            )}
                                        </div>
                                    </details>
                                )}

                                <div className="flex flex-wrap gap-3 pt-1">
                                    <Button
                                        type="submit"
                                        disabled={submitting}
                                        className="bg-blue-600 hover:bg-blue-700"
                                    >
                                        {submitting ? "Mentés…" : editingId ? "Módosítások mentése" : "Tétel mentése"}
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
                            </div>

                            <aside
                                className="h-fit rounded-xl border border-blue-100 bg-gradient-to-b from-blue-50 to-white p-4 sm:p-5 lg:sticky lg:top-6">
                                <h3 className="flex items-center gap-2 text-lg font-semibold text-slate-900">
                                    <span aria-hidden="true" className="text-blue-700">▦</span>
                                    Így használja a MIRA ezt az árat
                                </h3>
                                <div className="mt-4 space-y-3 text-sm">
                                    <p>
                                        <span
                                            className="block text-xs font-medium uppercase tracking-wide text-slate-500">Tétel</span>
                                        <span
                                            className="font-medium text-slate-900">{formData.name || "Tétel megnevezése"}</span>
                                    </p>
                                    <p>
                                        <span
                                            className="block text-xs font-medium uppercase tracking-wide text-slate-500">Nettó egységár</span>
                                        <span className="font-medium text-slate-900">
                                            {previewUnitPrice === null ? "Add meg az árat" : `${formatPrice(previewUnitPrice)} Ft`}/{previewUnit}
                                        </span>
                                    </p>
                                    <p className="text-slate-700">Szemléltető
                                        mennyiség: {exampleQuantity} {previewUnit}</p>
                                    <p className="rounded-lg bg-blue-100/80 px-3 py-4 text-center text-lg font-bold text-blue-800 sm:text-xl">
                                        {previewCalculation}
                                    </p>
                                    <p className="text-slate-700">
                                        {selectedType?.preview ?? "A tételtípus kiválasztása után itt jelenik meg, mit tartalmaz az ár."}
                                    </p>
                                </div>
                                <div className="mt-4 border-t border-blue-100 pt-4 text-xs leading-5 text-slate-600">
                                    <p>
                                        Ez csak szemléltető szorzás; az ajánlat tényleges mennyiségét és kalkulációját
                                        nem módosítja.
                                    </p>
                                    <p className="mt-2">
                                        A végleges összeget a MIRA az ajánlat tételei, mennyiségei és a meglévő
                                        kalkuláció alapján állítja össze.
                                    </p>
                                </div>
                            </aside>
                        </form>
                    </section>
                )}

                {loading ? (
                    <div className="py-8 text-center text-slate-500">Árjegyzék betöltése…</div>
                ) : items.length === 0 && !showForm ? (
                    <div
                        className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center sm:p-8">
                        <h2 className="text-lg font-semibold text-slate-800">
                            Még nincsenek elmentett árjegyzéktételek
                        </h2>
                        <p className="mx-auto mt-2 max-w-2xl text-sm text-slate-600">
                            Tölts be egy szakmai sablont, vagy adj hozzá árjegyzéktételeket kézzel. A sablon tételeinek
                            0 Ft-os alapárát mentés előtt módosítsd a saját eladási áraidra.
                        </p>
                        <div className="mt-6 grid gap-3 text-left sm:grid-cols-2 xl:grid-cols-3">
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
                            onClick={startNewItem}
                            disabled={loadingPreset !== null}
                        >
                            + Első árjegyzéktétel felvétele kézzel
                        </Button>
                    </div>
                ) : items.length > 0 ? (
                    <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5">
                        <h2 className="mb-4 text-lg font-semibold text-slate-900">Mentett árjegyzéktételek</h2>

                        <div className="space-y-3 md:hidden">
                            {items.map((item) => (
                                <article key={item.id} className="rounded-lg border border-slate-200 p-4">
                                    <div className="flex items-start justify-between gap-3">
                                        <div className="min-w-0">
                                            <h3 className="break-words font-medium text-slate-900">{item.name}</h3>
                                            <span
                                                className={`mt-2 inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${ITEM_TYPE_BADGE_STYLES[item.itemType]}`}>
                                                {ITEM_TYPE_LABELS[item.itemType]}
                                            </span>
                                        </div>
                                        <div className="shrink-0 text-right">
                                            <div
                                                className="font-semibold text-slate-900">{formatPrice(item.netSellingUnitPrice)} Ft
                                            </div>
                                            <div
                                                className="text-sm text-slate-500">/{UNIT_LABELS[item.unit] ?? item.unit}</div>
                                        </div>
                                    </div>
                                    <div className="mt-4 flex gap-4 border-t border-slate-100 pt-3 text-sm">
                                        <button
                                            type="button"
                                            onClick={() => handleEdit(item)}
                                            disabled={showForm && editingId === item.id}
                                            className="font-medium text-blue-600 hover:text-blue-800 disabled:text-slate-400"
                                        >
                                            Szerkesztés
                                        </button>
                                        <button
                                            type="button"
                                            onClick={() => handleDelete(item.id)}
                                            disabled={showForm}
                                            className="font-medium text-red-600 hover:text-red-800 disabled:text-slate-400"
                                        >
                                            Törlés
                                        </button>
                                    </div>
                                </article>
                            ))}
                        </div>

                        <div className="hidden overflow-x-auto md:block">
                            <table className="w-full text-left">
                                <thead className="border-b border-slate-200 bg-slate-50">
                                <tr>
                                    <th className="px-4 py-3 text-sm font-semibold text-slate-900">Tétel megnevezése
                                    </th>
                                    <th className="px-4 py-3 text-sm font-semibold text-slate-900">Tételtípus</th>
                                    <th className="px-4 py-3 text-sm font-semibold text-slate-900">Nettó egységár</th>
                                    <th className="px-4 py-3 text-sm font-semibold text-slate-900">Mértékegység</th>
                                    <th className="px-4 py-3 text-right text-sm font-semibold text-slate-900">Műveletek</th>
                                </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100">
                                {items.map((item) => (
                                    <tr key={item.id} className="transition-colors hover:bg-slate-50">
                                        <td className="px-4 py-3 text-sm font-medium text-slate-900">{item.name}</td>
                                        <td className="px-4 py-3">
                                            <span
                                                className={`inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${ITEM_TYPE_BADGE_STYLES[item.itemType]}`}>
                                                {ITEM_TYPE_LABELS[item.itemType]}
                                            </span>
                                        </td>
                                        <td className="px-4 py-3 text-sm text-slate-900">{formatPrice(item.netSellingUnitPrice)} Ft</td>
                                        <td className="px-4 py-3 text-sm text-slate-700">{UNIT_LABELS[item.unit] ?? item.unit}</td>
                                        <td className="space-x-3 px-4 py-3 text-right text-sm">
                                            <button
                                                type="button"
                                                onClick={() => handleEdit(item)}
                                                disabled={showForm && editingId === item.id}
                                                className="font-medium text-blue-600 transition-colors hover:text-blue-800 disabled:text-slate-400"
                                            >
                                                Szerkesztés
                                            </button>
                                            <button
                                                type="button"
                                                onClick={() => handleDelete(item.id)}
                                                disabled={showForm}
                                                className="font-medium text-red-600 transition-colors hover:text-red-800 disabled:text-slate-400"
                                            >
                                                Törlés
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        </div>
                    </section>
                ) : null}
            </div>
        </DashboardLayout>
    );
}