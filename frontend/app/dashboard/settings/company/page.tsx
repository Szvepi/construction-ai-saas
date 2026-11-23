"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {useRouter} from "next/navigation";
import {AsYouType} from "libphonenumber-js";
import {DashboardLayout} from "@/components/layout/DashboardLayout";
import {Button} from "@/components/ui/Button";
import {ApiError, apiFetch} from "@/lib/api";
import {isAuthenticated} from "@/lib/auth";
import type {CompanyProfile, SaveCompanyProfileRequest} from "@/lib/types";

type FormData = SaveCompanyProfileRequest & Pick<CompanyProfile, "contactEmail"> & {
    legalFormSelection: string;
    vatRateSelection: string;
};

type TextFieldProps = {
    label: string;
    value: string | null;
    required?: boolean;
    readOnly?: boolean;
    placeholder?: string;
    maxLength?: number;
    inputMode?: React.HTMLAttributes<HTMLInputElement>["inputMode"];
    inputType?: "text" | "tel" | "email";
    helperText?: string;
    onChange?: (value: string) => void;
};

const EMPTY_FORM: FormData = {
    companyName: "",
    legalForm: "",
    legalFormSelection: "",
    vatRateSelection: "",
    taxNumber: "",
    contactEmail: "",
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
    taxRegime: "AAM",
    vatRate: null,
    offerTerms: "Ajánlatunk a megküldéstől számított 15 naptári napig érvényes.\n" +
        "\n" +
        "Az ajánlatot a rendelkezésünkre bocsátott információk, a megadott mennyiségek és az ismert műszaki követelmények alapján állítottuk össze.\n" +
        "\n" +
        "A helyszíni adottságok, a tényleges mennyiségek vagy a műszaki tartalom változása esetén az ajánlat módosítása válhat szükségessé. Az esetleges változásokat a kivitelezés megkezdése előtt egyeztetjük a megrendelővel.",
};

const LEGAL_FORM_OPTIONS = [
    "Kft.",
    "Bt.",
    "Kkt.",
    "Zrt.",
    "Nyrt.",
    "Egyéni vállalkozó",
    "Egyéni cég",
    "Szövetkezet",
    "Egyesület",
    "Alapítvány",
    "Nonprofit Kft.",
    "Nonprofit Zrt.",
];

const VAT_RATE_OPTIONS = [5, 18, 27];

function TextField({
                       label,
                       value,
                       required,
                       readOnly,
                       placeholder,
                       maxLength,
                       inputMode,
                       inputType,
                       helperText,
                       onChange,
                   }: TextFieldProps) {
    return (
        <label className="block text-sm font-medium text-slate-700">
            {label}{required ? " *" : ""}
            <input
                type={inputType ?? "text"}
                value={value ?? ""}
                required={required}
                readOnly={readOnly}
                placeholder={placeholder}
                maxLength={maxLength}
                inputMode={inputMode}
                onChange={onChange ? (event) => onChange(event.target.value) : undefined}
                className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 read-only:bg-slate-100"
            />
            {helperText && <span className="mt-1 block text-xs font-normal text-slate-500">{helperText}</span>}
        </label>
    );
}

function formatTaxNumberInput(value: string) {
    const digits = value.replace(/\D/g, "").slice(0, 11);
    if (digits.length <= 8) {
        return digits;
    }
    if (digits.length === 9) {
        return `${digits.slice(0, 8)}-${digits.slice(8)}`;
    }
    return `${digits.slice(0, 8)}-${digits.slice(8, 9)}-${digits.slice(9)}`;
}

function formatSavedTaxNumber(value: string) {
    if (/^\d{8}(?:[-\s]?\d)(?:[-\s]?\d{2})$/.test(value)) {
        return formatTaxNumberInput(value);
    }
    return value;
}

function formatPhoneInput(value: string) {
    const trimmedValue = value.trim();
    if (!trimmedValue) {
        return "";
    }
    const internationalValue = trimmedValue.startsWith("00")
        ? `+${trimmedValue.slice(2)}`
        : trimmedValue;
    return new AsYouType("HU").input(internationalValue);
}

export default function CompanySettingsPage() {
    const router = useRouter();
    const [formData, setFormData] = useState<FormData>(EMPTY_FORM);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [saved, setSaved] = useState(false);
    const errorRef = useRef<HTMLDivElement>(null);

    const loadProfile = useCallback(async () => {
        try {
            const profile = await apiFetch<CompanyProfile>("/api/company-settings");
            const legalForm = profile.legalForm ?? "";
            const vatRate = profile.vatRate ?? null;
            setFormData({
                companyName: profile.companyName ?? "",
                legalForm,
                legalFormSelection: LEGAL_FORM_OPTIONS.includes(legalForm) ? legalForm : legalForm ? "OTHER" : "",
                taxNumber: formatSavedTaxNumber(profile.taxNumber ?? ""),
                contactEmail: profile.contactEmail ?? "",
                euTaxNumber: profile.euTaxNumber ?? "",
                offerorName: profile.offerorName ?? "",
                offerorPhone: formatPhoneInput(profile.offerorPhone ?? ""),
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
                taxRegime: profile.taxRegime ?? (profile.vatRegistered ? "VAT_REGISTERED" : "AAM"),
                vatRate,
                vatRateSelection: vatRate == null
                    ? ""
                    : VAT_RATE_OPTIONS.includes(vatRate) ? String(vatRate) : "OTHER",
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

    useEffect(() => {
        if (!error) {
            return;
        }

        errorRef.current?.scrollIntoView({behavior: "smooth", block: "center"});
        errorRef.current?.focus({preventScroll: true});
    }, [error]);

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
            const {
                contactEmail: _contactEmail,
                legalFormSelection: _legalFormSelection,
                vatRateSelection: _vatRateSelection,
                ...request
            }: FormData = formData;
            const savedProfile = await apiFetch<CompanyProfile>("/api/company-settings", {
                method: "PUT",
                body: request,
            });
            setFormData((current) => ({
                ...current,
                contactEmail: savedProfile.contactEmail ?? current.contactEmail,
                offerorPhone: formatPhoneInput(savedProfile.offerorPhone ?? ""),
            }));
            setSaved(true);
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                router.push("/auth/login");
                return;
            }
            setError(err instanceof ApiError
                ? `A mentés nem sikerült: ${err.message}`
                : "A céges adatok mentése sikertelen.");
        } finally {
            setSubmitting(false);
        }
    };

    if (!isAuthenticated()) {
        return null;
    }

    return (
        <DashboardLayout>
            <main className="mx-auto max-w-5xl space-y-6 px-4 py-6">
                <div>
                    <h1 className="text-3xl font-bold text-slate-900">Céges beállítások</h1>
                    <p className="mt-1 text-sm text-slate-500">
                        Add meg a céged adatait; a mentett adatokat később bármikor módosíthatod.
                    </p>
                </div>

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
                                <label className="block text-sm font-medium text-slate-700">
                                    Cégforma *
                                    <select
                                        value={formData.legalFormSelection}
                                        required
                                        onChange={(event) => {
                                            const legalFormSelection = event.target.value;
                                            setFormData((current) => ({
                                                ...current,
                                                legalFormSelection,
                                                legalForm: legalFormSelection === "OTHER" ? "" : legalFormSelection,
                                            }));
                                            setSaved(false);
                                        }}
                                        className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                    >
                                        <option value="">Válassz cégformát</option>
                                        {LEGAL_FORM_OPTIONS.map((legalForm) => (
                                            <option key={legalForm} value={legalForm}>{legalForm}</option>
                                        ))}
                                        <option value="OTHER">Egyéb</option>
                                    </select>
                                </label>
                                {formData.legalFormSelection === "OTHER" && (
                                    <TextField
                                        label="Egyéb cégforma"
                                        value={formData.legalForm}
                                        required
                                        placeholder="Add meg a cégformát"
                                        onChange={(value) => updateField("legalForm", value)}
                                    />
                                )}
                                <TextField
                                    label="Adószám"
                                    value={formData.taxNumber}
                                    required
                                    placeholder="12345676-1-42"
                                    maxLength={13}
                                    inputMode="numeric"
                                    // helperText="A belföldi adószám 8-1-2 számjegyű."
                                    onChange={(value) => updateField("taxNumber", formatTaxNumberInput(value))}
                                />
                                <TextField
                                    label="Közösségi adószám"
                                    value={formData.euTaxNumber}
                                    onChange={(value) => updateField("euTaxNumber", value)}
                                />
                                <TextField
                                    label="Ajánlatadó neve"
                                    value={formData.offerorName}
                                    helperText="Kitöltésével az ajánlaton megjelenik az ajánlatért felelős kapcsolattartó neve."
                                    onChange={(value) => updateField("offerorName", value)}
                                />
                                <TextField
                                    label="Ajánlatadó telefonszáma"
                                    value={formData.offerorPhone}
                                    placeholder="+36 30 123 4567"
                                    maxLength={30}
                                    inputMode="tel"
                                    inputType="tel"
                                    helperText="Magyar számot 06 vagy +36 előhívóval, külföldit + országkóddal adj meg, mellék nélkül. A mező nem kötelező."
                                    onChange={(value) => updateField("offerorPhone", formatPhoneInput(value))}
                                />
                                <TextField
                                    label="Kapcsolattartó e-mail címe"
                                    value={formData.contactEmail}
                                    readOnly
                                    inputType="email"
                                    helperText="Az ajánlaton is megjelenik."
                                />
                                <label className="block text-sm font-medium text-slate-700">
                                    Adózási mód
                                    <select
                                        value={formData.taxRegime}
                                        onChange={(event) => {
                                            const taxRegime = event.target.value as FormData["taxRegime"];
                                            setFormData((current) => ({
                                                ...current,
                                                taxRegime,
                                                vatRegistered: taxRegime === "VAT_REGISTERED",
                                                vatRate: taxRegime === "VAT_REGISTERED" ? current.vatRate : null,
                                                vatRateSelection: taxRegime === "VAT_REGISTERED"
                                                    ? current.vatRateSelection
                                                    : "",
                                            }));
                                            setSaved(false);
                                        }}
                                        className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2"
                                    >
                                        <option value="AAM">Alanyi adómentes (AAM)</option>
                                        <option value="VAT_REGISTERED">ÁFA-körös</option>
                                        {/*<option value="REVERSE_CHARGE_UNSUPPORTED">Fordított adózás (jelenleg nem támogatott)</option>*/}
                                    </select>
                                </label>
                                {formData.taxRegime === "VAT_REGISTERED" && (
                                    <div className="space-y-3">
                                        <label className="block text-sm font-medium text-slate-700">
                                            ÁFA-kulcs (%)
                                            <select
                                                value={formData.vatRateSelection}
                                                required
                                                onChange={(event) => {
                                                    const vatRateSelection = event.target.value;
                                                    setFormData((current) => ({
                                                        ...current,
                                                        vatRateSelection,
                                                        vatRate: vatRateSelection === "OTHER"
                                                            ? null
                                                            : vatRateSelection ? Number(vatRateSelection) : null,
                                                    }));
                                                    setSaved(false);
                                                }}
                                                className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2"
                                            >
                                                <option value="">Válassz ÁFA-kulcsot</option>
                                                {VAT_RATE_OPTIONS.map((rate) => (
                                                    <option key={rate} value={rate}>{rate}%</option>
                                                ))}
                                                <option value="OTHER">Egyéb</option>
                                            </select>
                                        </label>
                                        {formData.vatRateSelection === "OTHER" && (
                                            <label className="block text-sm font-medium text-slate-700">
                                                Egyedi ÁFA-kulcs (%)
                                                <input
                                                    type="number" min="0.01" max="100" step="0.01" required
                                                    value={formData.vatRate ?? ""}
                                                    onChange={(event) => {
                                                        const value = event.target.value;
                                                        setFormData((current) => ({
                                                            ...current,
                                                            vatRate: value === "" ? null : Number(value),
                                                        }));
                                                        setSaved(false);
                                                    }}
                                                    className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2"
                                                />
                                            </label>
                                        )}
                                        <p className="text-xs font-normal text-slate-500">
                                            Az ajánlatban szereplő katalógusárak nettó árak. Az itt megadott kulcs
                                            alapján számolunk.
                                        </p>
                                    </div>
                                )}
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
                                    value={formData.offerTerms ?? ""}
                                    onChange={(event) => updateField("offerTerms", event.target.value)}
                                    className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                                />
                            </label>
                            <p className="text-sm text-slate-500">
                                Szabadon szerkesztheted. Üresen hagyva ez a rész nem jelenik meg az ajánlatban.
                                Az ÁFA-tájékoztatás automatikusan, a fenti ÁFA-beállítás alapján kerül a PDF-be.
                            </p>
                        </section>

                        <div className="space-y-3">
                            {error && (
                                <div
                                    ref={errorRef}
                                    role="alert"
                                    aria-live="assertive"
                                    tabIndex={-1}
                                    className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700 focus:outline-none focus:ring-2 focus:ring-red-500"
                                >
                                    {error}
                                </div>
                            )}
                            {saved && (
                                <div
                                    role="status"
                                    aria-live="polite"
                                    className="rounded-md bg-green-50 p-4 text-sm text-green-700"
                                >
                                    A céges adatok mentése sikerült.
                                </div>
                            )}
                            <Button type="submit" disabled={submitting}>
                                {submitting ? "Mentés…" : "Céges adatok mentése"}
                            </Button>
                        </div>
                    </form>
                )}
            </main>
        </DashboardLayout>
    );
}
