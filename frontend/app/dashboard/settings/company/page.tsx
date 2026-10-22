"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {AppHeader} from "@/components/layout/AppHeader";
import {DashboardNav} from "@/components/layout/DashboardNav";
import {Button} from "@/components/ui/Button";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CompanyProfile, SaveCompanyProfileRequest} from "@/lib/types";

type FormData = {
    [Key in keyof SaveCompanyProfileRequest]: NonNullable<SaveCompanyProfileRequest[Key]>;
};

type TextFieldProps = {
    label: string;
    value: string;
    required?: boolean;
    readOnly?: boolean;
    onChange?: (value: string) => void;
};

const EMPTY_FORM: FormData = {
    companyName: "",
    legalForm: "",
    taxNumber: "",
    euTaxNumber: "",
    offerorName: "",
    offerorPhone: "",
    postalCode: "",
    city: "",
    streetName: "",
    streetType: "",
    houseNumber: "",
    building: "",
    staircase: "",
    floor: "",
    door: "",
    vatRegistered: false,
    offerTerms: "Jelen árajánlat a kiküldéstől számított 15 napig érvényes.\n\nA megadott árak a levélben leírt paraméterek alapján számított tájékoztató jellegű becslések. A végleges árajánlatot a helyszíni felmérést követően rögzítjük.",
};

function TextField({label, value, required, readOnly, onChange}: TextFieldProps) {
    return (
        <label className="block text-sm font-medium text-slate-700">
            {label}{required ? " *" : ""}
            <input
                type="text"
                value={value}
                required={required}
                readOnly={readOnly}
                onChange={onChange ? (event) => onChange(event.target.value) : undefined}
                className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 read-only:bg-slate-100"
            />
        </label>
    );
}

export default function CompanySettingsPage() {
    const router = useRouter();
    const [formData, setFormData] = useState<FormData>(EMPTY_FORM);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [saved, setSaved] = useState(false);

    const loadProfile = useCallback(async () => {
        try {
            const profile = await apiFetch<CompanyProfile>("/api/company-settings");
            setFormData({
                companyName: profile.companyName ?? "",
                legalForm: profile.legalForm ?? "",
                taxNumber: profile.taxNumber ?? "",
                euTaxNumber: profile.euTaxNumber ?? "",
                offerorName: profile.offerorName ?? "",
                offerorPhone: profile.offerorPhone ?? "",
                postalCode: profile.postalCode ?? "",
                city: profile.city ?? "",
                streetName: profile.streetName ?? "",
                streetType: profile.streetType ?? "",
                houseNumber: profile.houseNumber ?? "",
                building: profile.building ?? "",
                staircase: profile.staircase ?? "",
                floor: profile.floor ?? "",
                door: profile.door ?? "",
                vatRegistered: profile.vatRegistered ?? false,
                offerTerms: profile.offerTerms ?? "",
            });
            setError(null);
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            setError(err instanceof ApiError ? err.message : "A céges adatok betöltése sikertelen.");
        }
    }, [router]);

    useEffect(() => {
        if (!isAuthenticated()) {
            router.push("/auth/login");
            return;
        }
        loadProfile().finally(() => setLoading(false));
    }, [router, loadProfile]);

    const updateField = (field: keyof FormData, value: string) => {
        setFormData((current) => ({...current, [field]: value}));
        setSaved(false);
    };

    const handleSubmit = async (event: React.FormEvent) => {
        event.preventDefault();
        setSubmitting(true);
        setError(null);
        setSaved(false);

        try {
            const request: SaveCompanyProfileRequest = formData;
            await apiFetch<CompanyProfile>("/api/company-settings", {
                method: "PUT",
                body: request,
            });
            setSaved(true);
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            setError(err instanceof ApiError ? err.message : "A céges adatok mentése sikertelen.");
        } finally {
            setSubmitting(false);
        }
    };

    if (!isAuthenticated()) {
        return null;
    }

    return (
        <div className="min-h-screen bg-slate-50">
            <AppHeader/>
            <DashboardNav/>
            <main className="mx-auto max-w-5xl space-y-6 px-4 py-6">
                <div>
                    <h1 className="text-3xl font-bold text-slate-900">Céges beállítások</h1>
                    <p className="mt-1 text-sm text-slate-500">
                        Add meg a céged adatait; a mentett adatokat később bármikor módosíthatod.
                    </p>
                </div>

                {error && (
                    <div className="rounded-md bg-red-50 p-4 text-sm text-red-700">
                        {error}
                    </div>
                )}
                {saved && (
                    <div className="rounded-md bg-green-50 p-4 text-sm text-green-700">
                        A céges adatok mentése sikerült.
                    </div>
                )}

                {loading ? (
                    <div className="text-center text-slate-500">Céges adatok betöltése…</div>
                ) : (
                    <form onSubmit={handleSubmit} className="space-y-6">
                        <section className="space-y-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
                            <h2 className="text-lg font-semibold text-slate-900">Cégadatok</h2>
                            <div className="grid gap-4 md:grid-cols-2">
                                <TextField
                                    label="Cégnév"
                                    value={formData.companyName}
                                    required
                                    onChange={(value) => updateField("companyName", value)}
                                />
                                <TextField
                                    label="Cégforma"
                                    value={formData.legalForm}
                                    required
                                    onChange={(value) => updateField("legalForm", value)}
                                />
                                <TextField
                                    label="Adószám"
                                    value={formData.taxNumber}
                                    required
                                    onChange={(value) => updateField("taxNumber", value)}
                                />
                                <TextField
                                    label="Közösségi adószám"
                                    value={formData.euTaxNumber}
                                    onChange={(value) => updateField("euTaxNumber", value)}
                                />
                                <TextField
                                    label="Ajánlatadó neve"
                                    value={formData.offerorName}
                                    onChange={(value) => updateField("offerorName", value)}
                                />
                                <TextField
                                    label="Ajánlatadó telefonszáma"
                                    value={formData.offerorPhone}
                                    onChange={(value) => updateField("offerorPhone", value)}
                                />
                                <label className="flex items-start gap-3 text-sm font-medium text-slate-700">
                                    <input
                                        type="checkbox"
                                        checked={formData.vatRegistered}
                                        onChange={(event) => {
                                            setFormData((current) => ({
                                                ...current,
                                                vatRegistered: event.target.checked,
                                            }));
                                            setSaved(false);
                                        }}
                                        className="mt-1 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                    />
                                    <span>
                                        A cég ÁFA-körös
                                        <span className="mt-1 block font-normal text-slate-500">
                                            Az árjegyzékben megadott bruttó árakból az ajánlat nettó összeget és 27% ÁFÁ-t számol.
                                        </span>
                                    </span>
                                </label>
                            </div>
                        </section>

                        <section className="space-y-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
                            <h2 className="text-lg font-semibold text-slate-900">Székhely adatok</h2>
                            <div className="grid gap-4 md:grid-cols-3">
                                <TextField label="Ország" value="Magyarország" readOnly/>
                                <TextField
                                    label="Irányítószám"
                                    value={formData.postalCode}
                                    required
                                    onChange={(value) => updateField("postalCode", value)}
                                />
                                <TextField
                                    label="Település"
                                    value={formData.city}
                                    required
                                    onChange={(value) => updateField("city", value)}
                                />
                                <TextField
                                    label="Közterület neve"
                                    value={formData.streetName}
                                    required
                                    onChange={(value) => updateField("streetName", value)}
                                />
                                <TextField
                                    label="Közterület jellege"
                                    value={formData.streetType}
                                    required
                                    onChange={(value) => updateField("streetType", value)}
                                />
                                <TextField
                                    label="Házszám"
                                    value={formData.houseNumber}
                                    required
                                    onChange={(value) => updateField("houseNumber", value)}
                                />
                                <TextField
                                    label="Épület"
                                    value={formData.building}
                                    onChange={(value) => updateField("building", value)}
                                />
                                <TextField
                                    label="Lépcsőház"
                                    value={formData.staircase}
                                    onChange={(value) => updateField("staircase", value)}
                                />
                                <TextField
                                    label="Szint"
                                    value={formData.floor}
                                    onChange={(value) => updateField("floor", value)}
                                />
                                <TextField
                                    label="Ajtó"
                                    value={formData.door}
                                    onChange={(value) => updateField("door", value)}
                                />
                            </div>
                        </section>

                        <section className="space-y-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
                            <h2 className="text-lg font-semibold text-slate-900">Ajánlati feltételek</h2>
                            <label className="block text-sm font-medium text-slate-700" htmlFor="offer-terms">
                                Az ajánlatban megjelenő feltételek és tájékoztatás
                                <textarea
                                    id="offer-terms"
                                    rows={6}
                                    value={formData.offerTerms}
                                    onChange={(event) => updateField("offerTerms", event.target.value)}
                                    className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                />
                            </label>
                            <p className="text-sm text-slate-500">
                                Szabadon szerkesztheted. Üresen hagyva ez a rész nem jelenik meg az ajánlatban.
                                Az ÁFA-tájékoztatás automatikusan, a fenti ÁFA-beállítás alapján kerül a PDF-be.
                            </p>
                        </section>

                        <Button type="submit" disabled={submitting}>
                            {submitting ? "Mentés…" : "Céges adatok mentése"}
                        </Button>
                    </form>
                )}
            </main>
        </div>
    );
}
