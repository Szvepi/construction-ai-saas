"use client";

import {useEffect, useMemo, useState} from "react";
import {ApiError, apiFetch, apiFetchBlob} from "@/lib/api";
import type {
    AnalysisResponse,
    CoverageResponse,
    CoverageStatus,
    CoverageWarning,
    CoverageWorkItem,
    CatalogItem,
    DraftLineItem,
    DraftQuoteSnapshot,
    EmailDetail,
    GenerateDraftResponse
} from "@/lib/types";
import {type DraftAction, DraftActions} from "@/components/emails/DraftActions";
import {DraftAnalysisPanel} from "@/components/emails/DraftAnalysisPanel";
import {QuoteLineItemsEditor} from "@/components/emails/QuoteLineItemsEditor";
import {CoverageConfirmationDialog} from "@/components/emails/CoverageConfirmationDialog";

type Props = {
    emailId: number;
    email?: EmailDetail | null;
    onSent: () => void;
    onDirtyChange?: (dirty: boolean) => void;
    onPdfPreviewChange?: (preview: { url: string; draftId: number } | null) => void;
};

type DraftFields = {
    clientName: string;
    additionalNotes: string;
    includeAdditionalNotes: boolean;
    lineItems: DraftLineItem[];
};

function snapshot(fields: DraftFields): string {
    return JSON.stringify(fields);
}

function normalizeLineItems(items: DraftLineItem[] | null | undefined): DraftLineItem[] {
    return (items ?? []).map((item) => ({...item}));
}

export function DraftPanel({emailId, email, onSent, onDirtyChange, onPdfPreviewChange}: Props) {
    const [draftId, setDraftId] = useState<number | null>(null);
    const [clientName, setClientName] = useState("");
    const [additionalNotes, setAdditionalNotes] = useState("");
    const [includeAdditionalNotes, setIncludeAdditionalNotes] = useState(true);
    const [vatRegistered, setVatRegistered] = useState(false);
    const [taxRegime, setTaxRegime] = useState<GenerateDraftResponse["taxRegime"]>("AAM");
    const [vatRate, setVatRate] = useState<number | null>(null);
    const [vatAmount, setVatAmount] = useState<number | null>(null);
    const [grossTotal, setGrossTotal] = useState<number | null>(null);
    const [taxConfigurationValid, setTaxConfigurationValid] = useState(true);
    const [draftVersion, setDraftVersion] = useState<number | null>(null);
    const [quoteSnapshot, setQuoteSnapshot] = useState<DraftQuoteSnapshot | null>(null);
    const [catalogItems, setCatalogItems] = useState<CatalogItem[]>([]);
    const [netQuotePrice, setNetQuotePrice] = useState<number | null>(null);
    const [reviewWarnings, setReviewWarnings] = useState<string[]>([]);
    const [coverageItems, setCoverageItems] = useState<CoverageWorkItem[]>([]);
    const [coverageWarnings, setCoverageWarnings] = useState<CoverageWarning[]>([]);
    const [analysisFresh, setAnalysisFresh] = useState(true);
    const [gmailDraftCreated, setGmailDraftCreated] = useState(false);
    const [confirmationIssues, setConfirmationIssues] = useState<string[] | null>(null);
    const [confirmationFingerprint, setConfirmationFingerprint] = useState<string | null>(null);
    const [lineItems, setLineItems] = useState<DraftLineItem[]>([]);
    const [body, setBody] = useState("");
    const [pdfUrl, setPdfUrl] = useState<string | null>(null);
    const [busyAction, setBusyAction] = useState<DraftAction>(null);
    const [error, setError] = useState<string | null>(null);
    const [versionConflict, setVersionConflict] = useState(false);
    const [saveSuccess, setSaveSuccess] = useState<string | null>(null);
    const [previewGenerated, setPreviewGenerated] = useState(false);
    const [savedSnapshot, setSavedSnapshot] = useState<string | null>(null);

    const currentSnapshot = useMemo(() => snapshot({
        clientName,
        additionalNotes,
        includeAdditionalNotes,
        lineItems,
    }), [clientName, additionalNotes, includeAdditionalNotes, lineItems]);
    const isDirty = savedSnapshot !== null && currentSnapshot !== savedSnapshot;

    useEffect(() => {
        onDirtyChange?.(isDirty);
    }, [isDirty, onDirtyChange]);

    useEffect(() => {
        let active = true;
        if (!draftId) {
            setCatalogItems([]);
            return () => { active = false; };
        }
        apiFetch<CatalogItem[]>("/api/catalog")
            .then((items) => {
                if (active) setCatalogItems(items);
            })
            .catch((err) => {
                if (active) setError(err instanceof ApiError ? err.message : "A katalógus betöltése sikertelen.");
            });
        return () => { active = false; };
    }, [draftId]);

    useEffect(() => {
        let active = true;
        const currentEmail = email;

        if (currentEmail?.draftEmail) {
            const items = normalizeLineItems(currentEmail.draftEmail.lineItems);
            const initialFields: DraftFields = {
                clientName: currentEmail.draftEmail.clientName ?? "",
                additionalNotes: currentEmail.draftEmail.additionalNotes ?? "",
                includeAdditionalNotes: currentEmail.draftEmail.includeAdditionalNotes ?? true,
                lineItems: items,
            };
            setDraftId(currentEmail.draftEmail.draftId);
            setBody(currentEmail.draftEmail.draftBody);
            setPreviewGenerated(false);
            setPdfUrl(null);
            setClientName(initialFields.clientName);
            setAdditionalNotes(initialFields.additionalNotes);
            setIncludeAdditionalNotes(initialFields.includeAdditionalNotes);
            setVatRegistered(currentEmail.draftEmail.vatRegistered ?? false);
            setTaxRegime(currentEmail.draftEmail.taxRegime ?? "AAM");
            setVatRate(currentEmail.draftEmail.vatRate ?? null);
            setVatAmount(currentEmail.draftEmail.vatAmount ?? null);
            setGrossTotal(currentEmail.draftEmail.grossQuotePrice ?? null);
            setTaxConfigurationValid(currentEmail.draftEmail.taxConfigurationValid ?? true);
            setDraftVersion(currentEmail.draftEmail.draftVersion ?? null);
            setQuoteSnapshot(currentEmail.draftEmail.quoteSnapshot ?? null);
            setNetQuotePrice(currentEmail.draftEmail.netQuotePrice ?? null);
            setReviewWarnings(currentEmail.draftEmail.reviewWarnings ?? []);
            setCoverageItems(currentEmail.draftEmail.coverageItems ?? []);
            setCoverageWarnings(currentEmail.draftEmail.coverageWarnings ?? []);
            setAnalysisFresh(currentEmail.draftEmail.analysisFresh ?? true);
            setGmailDraftCreated(currentEmail.draftEmail.gmailDraftCreated ?? false);
            setLineItems(items);
            setSavedSnapshot(snapshot(initialFields));
            if (currentEmail.draftEmail.draftBody) {
                apiFetchBlob(`/api/drafts/${currentEmail.draftEmail.draftId}/pdf`)
                    .then((blob) => {
                        if (!active) return;
                        setPdfUrl(URL.createObjectURL(blob));
                        setPreviewGenerated(true);
                    })
                    .catch((err) => {
                        if (!active) return;
                        if (err instanceof ApiError && err.status === 401) {
                            window.location.assign("/auth/login");
                            return;
                        }
                        setError(err instanceof ApiError ? err.message : "A PDF-előnézet betöltése sikertelen.");
                    });
            }
        } else {
            const emptyFields: DraftFields = {
                clientName: "",
                additionalNotes: "",
                includeAdditionalNotes: true,
                lineItems: [],
            };
            setDraftId(null);
            setBody("");
            setPdfUrl(null);
            setPreviewGenerated(false);
            setClientName(emptyFields.clientName);
            setAdditionalNotes(emptyFields.additionalNotes);
            setIncludeAdditionalNotes(emptyFields.includeAdditionalNotes);
            setVatRegistered(false);
            setTaxRegime("AAM");
            setVatRate(null);
            setVatAmount(null);
            setGrossTotal(null);
            setTaxConfigurationValid(true);
            setDraftVersion(null);
            setQuoteSnapshot(null);
            setNetQuotePrice(null);
            setReviewWarnings([]);
            setCoverageItems([]);
            setCoverageWarnings([]);
            setAnalysisFresh(true);
            setGmailDraftCreated(false);
            setLineItems([]);
            setSavedSnapshot(snapshot(emptyFields));
        }
        setError(null);
        setSaveSuccess(null);
        return () => {
            active = false;
        };
    }, [email]);

    useEffect(() => () => {
        if (pdfUrl) URL.revokeObjectURL(pdfUrl);
    }, [pdfUrl]);

    useEffect(() => {
        onPdfPreviewChange?.(
            body && previewGenerated && pdfUrl && draftId
                ? {url: pdfUrl, draftId}
                : null,
        );
    }, [body, draftId, onPdfPreviewChange, pdfUrl, previewGenerated]);

    const total = useMemo(
        () => netQuotePrice,
        [netQuotePrice],
    );
    const isQuoteRequest = email?.category === "QUOTE_REQUEST";

    async function analyze() {
        if (draftId && isDirty && !window.confirm("Az új AI-elemzés felülírhatja a jelenlegi módosításokat. Folytatod?")) {
            return;
        }
        setBusyAction("analyze");
        setError(null);
        setSaveSuccess(null);
        try {
            const data = await apiFetch<AnalysisResponse>(`/api/drafts/analyze/${emailId}`, {
                method: "POST",
                body: {additionalNotes},
            });
            const items = normalizeLineItems(data.lineItems);
            const fields: DraftFields = {
                clientName: data.clientName ?? "",
                additionalNotes: data.additionalNotes ?? "",
                includeAdditionalNotes: data.includeAdditionalNotes ?? true,
                lineItems: items,
            };
            setDraftId(data.draftId);
            setClientName(fields.clientName);
            setAdditionalNotes(fields.additionalNotes);
            setVatRegistered(data.vatRegistered ?? false);
            setTaxRegime(data.taxRegime ?? "AAM");
            setVatRate(data.vatRate ?? null);
            setVatAmount(data.vatAmount ?? null);
            setGrossTotal(data.grossQuotePrice ?? null);
            setTaxConfigurationValid(data.taxConfigurationValid ?? true);
            setDraftVersion(data.draftVersion ?? null);
            setQuoteSnapshot(data.quoteSnapshot ?? null);
            setNetQuotePrice(data.netQuotePrice ?? null);
            setReviewWarnings(data.reviewWarnings ?? []);
            setCoverageItems(data.coverageItems ?? []);
            setCoverageWarnings(data.coverageWarnings ?? []);
            setAnalysisFresh(data.analysisFresh);
            setGmailDraftCreated(false);
            setLineItems(items);
            setBody("");
            setPdfUrl(null);
            setPreviewGenerated(false);
            setSavedSnapshot(snapshot(fields));
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az elemzés sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    function buildPayload(items = lineItems) {
        return {
            clientName: clientName || email?.fromAddress || "Érdeklődő",
            additionalNotes,
            includeAdditionalNotes,
            lineItems: items.map((item) => ({
                ...item,
                quantity: item.quantity == null ? null : Number(item.quantity),
                unitPrice: Number(item.unitPrice || 0),
                subtotal: item.quantity == null
                    ? null
                    : Math.round(Number(item.quantity) * Number(item.unitPrice || 0)),
            })),
            expectedVersion: draftVersion,
        };
    }

    function applySavedDraft(data: GenerateDraftResponse) {
        const fields: DraftFields = {
            clientName: data.clientName ?? "",
            additionalNotes: data.additionalNotes ?? "",
            includeAdditionalNotes: data.includeAdditionalNotes ?? true,
            lineItems: normalizeLineItems(data.lineItems),
        };
        setClientName(fields.clientName);
        setAdditionalNotes(fields.additionalNotes);
        setIncludeAdditionalNotes(fields.includeAdditionalNotes);
        setVatRegistered(data.vatRegistered ?? false);
        setTaxRegime(data.taxRegime ?? "AAM");
        setVatRate(data.vatRate ?? null);
        setVatAmount(data.vatAmount ?? null);
        setGrossTotal(data.grossQuotePrice ?? null);
        setTaxConfigurationValid(data.taxConfigurationValid ?? true);
        setDraftVersion(data.draftVersion ?? null);
        setQuoteSnapshot(data.quoteSnapshot ?? null);
        setNetQuotePrice(data.netQuotePrice ?? null);
        setLineItems(fields.lineItems);
        setCoverageItems(data.coverageItems ?? []);
        setCoverageWarnings(data.coverageWarnings ?? []);
        setAnalysisFresh(data.analysisFresh ?? true);
        setGmailDraftCreated(data.gmailDraftCreated ?? false);
        setSavedSnapshot(snapshot(fields));
    }

    async function save(): Promise<GenerateDraftResponse | null> {
        if (!draftId) return null;
        const payload = buildPayload();
        setBusyAction("save");
        setError(null);
        setSaveSuccess(null);

        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/save`, {
                method: "POST",
                body: payload,
            });
            applySavedDraft(data);
            setSaveSuccess("A módosítások mentésre kerültek.");
            return data;
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "A mentés sikertelen");
            return null;
        } finally {
            setBusyAction(null);
        }
    }

    async function addCatalogLine(
        workId: string, catalogItemId: number, unmappedId: string | null,
        parameters: {id: string; value: number; unit: string}[]
    ) {
        if (!draftId || busyAction !== null) return;
        setBusyAction("save");
        setError(null);
        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/work-mappings`, {
                method: "POST",
                body: {
                    workId, catalogItemId, expectedVersion: draftVersion, unmappedId,
                    parameters,
                },
            });
            applySavedDraft(data);
            setSaveSuccess("A katalógustétel hozzáadva, a lefedettséget ellenőrizd újra.");
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "A katalógustétel hozzáadása sikertelen.");
        } finally {
            setBusyAction(null);
        }
    }

    function updateLineItem(index: number, value: string | number) {
        setPreviewGenerated(false);
        const currentItem = lineItems[index];
        if (currentItem && String(currentItem.quantity ?? "") !== String(value)) {
            setCoverageItems((current) => current.map((work) =>
                work.status === "INCLUDED" && work.linkedLineItemId === currentItem.lineItemId
                    ? {...work, status: "NEEDS_REVIEW", linkedLineItemId: null, userConfirmed: false}
                    : work));
        }
        setLineItems((current) => {
            const next = [...current];
            const row = {...next[index]};
            row.quantity = value === "" ? null : Number(value);
            row.subtotal = null;
            next[index] = row;
            return next;
        });
    }

    function updateUnitPrice(index: number, value: string) {
        setPreviewGenerated(false);
        setLineItems((current) => current.map((item, currentIndex) => currentIndex === index
            ? {
                ...item,
                unitPrice: Number(value) || 0,
                priceOverridden: value !== "" && Number(value) !== item.catalogUnitPrice,
                priceOverrideReason: value !== "" && Number(value) !== item.catalogUnitPrice
                    ? item.priceOverrideReason ?? "" : null,
                subtotal: null,
            }
            : item));
    }

    function updateCalculationParameter(lineIndex: number, parameterIndex: number, value: string) {
        setPreviewGenerated(false);
        setLineItems((current) => current.map((item, index) => {
            if (index !== lineIndex) return item;
            const parameters = [...(item.calculationParameters ?? [])];
            const parameter = parameters[parameterIndex];
            if (parameter) parameters[parameterIndex] = {
                ...parameter,
                value: value === "" ? null : Number(value),
                source: parameter.source === "AI_ASSUMPTION" && value !== ""
                    ? "USER_OVERRIDE" : parameter.source,
                approved: parameter.source === "AI_ASSUMPTION" && value !== ""
                    ? true : parameter.approved,
                rejected: false,
            };
            return {
                ...item,
                assumptionsApproved: parameters.some((candidate) => candidate.source === "AI_ASSUMPTION")
                    ? false : item.assumptionsApproved,
                calculationParameters: parameters,
            };
        }));
    }

    function rejectAssumption(lineIndex: number, parameterIndex: number) {
        setPreviewGenerated(false);
        setLineItems((current) => current.map((item, index) => {
            if (index !== lineIndex) return item;
            const parameters = [...(item.calculationParameters ?? [])];
            const parameter = parameters[parameterIndex];
            if (parameter?.source === "AI_ASSUMPTION") {
                parameters[parameterIndex] = {
                    ...parameter, value: null, approved: false, rejected: true,
                };
            }
            return {...item, assumptionsApproved: false, calculationParameters: parameters};
        }));
    }

    function updatePriceOverrideReason(index: number, reason: string) {
        setLineItems((current) => current.map((item, currentIndex) => currentIndex === index
            ? {...item, priceOverrideReason: reason}
            : item));
    }

    function approveAssumptions(index: number, approved: boolean) {
        setPreviewGenerated(false);
        setLineItems((current) => current.map((item, currentIndex) => currentIndex === index
            ? {
                ...item,
                assumptionsApproved: approved,
                calculationParameters: (item.calculationParameters ?? []).map((parameter) =>
                    parameter.source === "AI_ASSUMPTION"
                        ? {...parameter, approved}
                        : parameter),
            }
            : item));
    }

    async function saveCoverageDecision(
        issueId: string,
        status: CoverageStatus,
        lineItemId?: string,
        items = lineItems,
    ) {
        if (!draftId || busyAction !== null) return;
        setBusyAction("save");
        setError(null);
        setSaveSuccess(null);
        setPreviewGenerated(false);
        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/coverage/${encodeURIComponent(issueId)}`, {
                method: "PUT",
                body: {
                    status,
                    lineItemId: lineItemId ?? null,
                    draft: buildPayload(items),
                },
            });
            applySavedDraft(data);
            setSaveSuccess("Az ellenőrzési döntés mentésre került.");
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "Az ellenőrzési döntés mentése sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    async function preview(): Promise<boolean> {
        if (!draftId) return false;
        const payload = buildPayload();
        setBusyAction("preview");
        setError(null);
        setSaveSuccess(null);

        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/preview`, {
                method: "POST",
                body: payload,
            });
            setBody(data.draftBody ?? "");
            applySavedDraft(data);
            const pdf = await apiFetchBlob(`/api/drafts/${draftId}/pdf`);
            setPdfUrl(URL.createObjectURL(pdf));
            setPreviewGenerated(true);
            return true;
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "Az előnézet generálása sikertelen");
            return false;
        } finally {
            setBusyAction(null);
        }
    }

    async function finalizeGmailDraft(
        confirmIncomplete: boolean,
        expectedFingerprint = confirmationFingerprint,
    ) {
        if (!draftId) return;
        const payload = buildPayload();
        setBusyAction("finalize");
        setError(null);
        setSaveSuccess(null);

        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/finalize`, {
                method: "POST",
                body: {
                    ...payload,
                    confirmIncomplete,
                    confirmedFingerprint: expectedFingerprint,
                    expectedVersion: draftVersion,
                },
            });
            applySavedDraft(data);
            setConfirmationIssues(null);
            setConfirmationFingerprint(null);
            setSaveSuccess("A Gmail-piszkozat elkészült. Ellenőrizd a Gmailben, mielőtt elküldöd.");
            onSent();
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "A Gmail-piszkozat létrehozása sikertelen");
        } finally {
            setBusyAction(null);
        }
    }

    async function addManualLine(input: {
        name: string; quantity: number; unit: string; netUnitPrice: number;
        itemType: NonNullable<DraftLineItem["itemType"]>; workId?: string;
    }) {
        if (!draftId || draftVersion == null || busyAction !== null) return;
        setBusyAction("save");
        setError(null);
        try {
            const data = await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}/manual-lines`, {
                method: "POST",
                body: {...input, expectedVersion: draftVersion},
            });
            applySavedDraft(data);
            setSaveSuccess("A kézi ajánlati tétel hozzáadva.");
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "A kézi tétel hozzáadása sikertelen.");
        } finally {
            setBusyAction(null);
        }
    }

    async function updateManualLine(lineItemId: string, input: {
        name: string; quantity: number; unit: string; netUnitPrice: number;
        itemType: NonNullable<DraftLineItem["itemType"]>;
    }) {
        if (!draftId || draftVersion == null || busyAction !== null) return;
        setBusyAction("save");
        setError(null);
        try {
            const data = await apiFetch<GenerateDraftResponse>(
                `/api/drafts/${draftId}/manual-lines/${encodeURIComponent(lineItemId)}`, {
                    method: "PUT",
                    body: {...input, expectedVersion: draftVersion},
                });
            applySavedDraft(data);
            setSaveSuccess("A kézi ajánlati tétel módosításai mentésre kerültek.");
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "A kézi tétel módosítása sikertelen.");
        } finally {
            setBusyAction(null);
        }
    }

    async function deleteLine(lineItemId: string) {
        if (!draftId || draftVersion == null || busyAction !== null) return;
        setBusyAction("save");
        setError(null);
        try {
            const data = await apiFetch<GenerateDraftResponse>(
                `/api/drafts/${draftId}/lines/${encodeURIComponent(lineItemId)}?expectedVersion=${draftVersion}`,
                {method: "DELETE"},
            );
            applySavedDraft(data);
            setSaveSuccess("Az ajánlati tétel törölve.");
        } catch (err) {
            setVersionConflict(err instanceof ApiError && err.status === 409);
            setError(err instanceof ApiError ? err.message : "Az ajánlati tétel törlése sikertelen.");
        } finally {
            setBusyAction(null);
        }
    }

    async function reloadDraft() {
        if (!draftId || busyAction !== null) return;
        setBusyAction("save");
        setError(null);
        try {
            applySavedDraft(await apiFetch<GenerateDraftResponse>(`/api/drafts/${draftId}`));
            setVersionConflict(false);
            setSaveSuccess("A legfrissebb mentett ajánlat betöltve.");
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az ajánlat újratöltése sikertelen.");
        } finally {
            setBusyAction(null);
        }
    }

    async function createGmailDraft() {
        if (!draftId || busyAction !== null) return;
        if (isDirty && !window.confirm("A Gmail-piszkozat készítése elmenti az aktuális ajánlatot. Folytatod?")) {
            return;
        }
        const saved = await save();
        if (!saved) return;
        if (!body && !(await preview())) return;
        setBusyAction("save");
        setError(null);
        let shouldFinalize = false;
        let currentQuoteFingerprint: string | null = null;
        try {
            const coverage = await apiFetch<CoverageResponse>(`/api/drafts/${draftId}/coverage`);
            currentQuoteFingerprint = coverage.quoteFingerprint;
            setCoverageItems(coverage.workItems);
            setCoverageWarnings(coverage.warnings);
            setAnalysisFresh(coverage.analysisFresh);
            setConfirmationFingerprint(coverage.quoteFingerprint);
            if (coverage.unresolvedIssues.length > 0) {
                setConfirmationIssues(coverage.unresolvedIssues);
            } else {
                shouldFinalize = true;
            }
        } catch (err) {
            setError(err instanceof ApiError ? err.message : "Az ajánlat ellenőrzése sikertelen");
        } finally {
            setBusyAction(null);
        }
        if (shouldFinalize) await finalizeGmailDraft(false, currentQuoteFingerprint);
    }

    if (!isQuoteRequest) {
        return (
            <section className="rounded-2xl border border-amber-200 bg-amber-50 p-5">
                <p className="text-sm font-medium text-amber-900">
                    Az AI-ajánlatkészítés csak ajánlatkérés kategóriájú levelekhez érhető el.
                </p>
            </section>
        );
    }

    return (
        <section className="min-w-0 space-y-4 pb-64 sm:pb-48 xl:pb-32">
            {error && (
                <div role="alert"
                     className="flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
                    <span className="mt-0.5 font-bold" aria-hidden="true">!</span>
                    <p className="min-w-0 break-words">{error}</p>
                </div>
            )}
            {versionConflict && (
                <button type="button" onClick={() => void reloadDraft()} disabled={busyAction !== null}
                        className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm font-semibold text-amber-950 disabled:opacity-50">
                    Legfrissebb ajánlat betöltése
                </button>
            )}

            {busyAction && (
                <p role="status"
                   className="rounded-xl border border-brand-100 bg-brand-50 px-4 py-2.5 text-sm font-medium text-brand-800">
                    {busyAction === "analyze" && "Mira elemzi a levelet és összeállítja a javasolt tételeket…"}
                    {busyAction === "save" && "Az ajánlat módosításainak mentése folyamatban…"}
                    {busyAction === "preview" && "A PDF-előnézet készítése folyamatban…"}
                    {busyAction === "finalize" && "A Gmail-piszkozat készítése folyamatban…"}
                </p>
            )}

            <DraftAnalysisPanel
                emailId={emailId}
                draftId={draftId}
                clientName={clientName}
                additionalNotes={additionalNotes}
                includeAdditionalNotes={includeAdditionalNotes}
                reviewWarnings={reviewWarnings}
                coverageItems={coverageItems}
                coverageWarnings={coverageWarnings}
                lineItems={lineItems}
                quoteSnapshot={quoteSnapshot}
                catalogItems={catalogItems}
                onAddCatalogLine={addCatalogLine}
                analysisFresh={analysisFresh}
                busy={busyAction !== null}
                onAnalyze={analyze}
                onClientNameChange={(value) => {
                    setPreviewGenerated(false);
                    setClientName(value);
                }}
                onAdditionalNotesChange={(value) => {
                    setPreviewGenerated(false);
                    if (value !== additionalNotes) setAnalysisFresh(false);
                    setAdditionalNotes(value);
                }}
                onIncludeAdditionalNotesChange={(value) => {
                    setPreviewGenerated(false);
                    setIncludeAdditionalNotes(value);
                }}
                onSetWorkDecision={(workId, status, lineItemId) => {
                    void saveCoverageDecision(workId, status, lineItemId);
                }}
                onAcknowledgeWarning={(warningId, acknowledged) => {
                    void saveCoverageDecision(
                        warningId,
                        acknowledged ? "INCLUDED" : "NEEDS_REVIEW",
                    );
                }}
            />

            {draftId && (
                <>
                    <QuoteLineItemsEditor
                        lineItems={lineItems}
                        quoteSnapshot={quoteSnapshot}
                        busy={busyAction !== null}
                        onUpdateQuantity={updateLineItem}
                        onUpdateUnitPrice={updateUnitPrice}
                        onUpdatePriceReason={updatePriceOverrideReason}
                        onUpdateParameter={updateCalculationParameter}
                        onApproveAssumptions={approveAssumptions}
                        onRejectAssumption={rejectAssumption}
                        onAddManualLine={addManualLine}
                        onUpdateManualLine={updateManualLine}
                        onDeleteLine={deleteLine}
                    />
                    <DraftActions
                        draftId={draftId}
                        lineItemCount={lineItems.length}
                        busyAction={busyAction}
                        isDirty={isDirty}
                        gmailDraftCreated={gmailDraftCreated}
                        saveSuccess={saveSuccess}
                        total={total}
                        taxRegime={taxRegime ?? "AAM"}
                        vatRate={vatRate}
                        vatAmount={vatAmount}
                        grossTotal={grossTotal}
                        taxConfigurationValid={taxConfigurationValid}
                        onSave={save}
                        onPreview={preview}
                        onCreateGmailDraft={createGmailDraft}
                    />
                </>
            )}

            {confirmationIssues && (
                <CoverageConfirmationDialog
                    unresolvedIssues={confirmationIssues}
                    busy={busyAction === "finalize"}
                    onReturn={() => {
                        setConfirmationIssues(null);
                        setConfirmationFingerprint(null);
                        document.getElementById("quote-coverage")?.scrollIntoView({
                            behavior: "smooth",
                            block: "center",
                        });
                    }}
                    onContinue={() => void finalizeGmailDraft(true)}
                />
            )}

        </section>
    );
}
