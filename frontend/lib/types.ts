export type EmailSummary = {
  id: number;
  subject: string;
  fromAddress: string;
  receivedAt: string;
  replied: boolean;
  category: "QUOTE_REQUEST" | "SPAM" | "OTHER";
  draftStatus: "DRAFT" | "PENDING_REVIEW" | "READY" | "SENT" | null;
};

export type EmailSearchResponse = {
  content: EmailSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  categoryCounts: Record<EmailSummary["category"], number>;
  reviewCount: number;
};

export type EmailDetail = EmailSummary & { bodyText: string; draftEmail?: GenerateDraftResponse | null };

export type GmailStatus = { connected: boolean; gmailAddress: string | null };

export type CalculationStatus = "VALID" | "NEEDS_INPUT" | "NEEDS_REVIEW" | "INVALID";
export type WorkRecognitionStatus = "MAPPED" | "PARTIALLY_MAPPED" | "UNMAPPED" | "NEEDS_REVIEW";
export type CalculationInputSource =
  | "CUSTOMER_EMAIL"
  | "CUSTOMER_SUPPLEMENT"
  | "CATALOG_DEFAULT"
  | "CONTRACTOR_PROFILE"
  | "AI_ASSUMPTION"
  | "USER_OVERRIDE";

export type SnapshotQuantity = {value: number; unit: string};

export type DraftQuoteSnapshot = {
  schemaVersion: number;
  fingerprint: string;
  workItems: {
    workId: string;
    originalRequest: string;
    source: CalculationInputSource;
    sourceEvidence: string | null;
    customerRequestedQuantity: SnapshotQuantity | null;
    customerRequestedUnit: string | null;
    settlementMode: "MATERIAL_AND_LABOR" | "LABOR_ONLY" | "CUSTOMER_SUPPLIED_MATERIAL";
    recognitionStatus: WorkRecognitionStatus;
    suggestedCatalogItemId: number | null;
    explanation: string | null;
    explicitlyRequested: boolean;
    assumptions: string[];
    missingParameters: string[];
    unmappedLines: {explanation: string | null; reason: string; assumptions: string[]}[];
  }[];
  lineItems: {
    lineItemId: string;
    workId: string;
    catalogItemId: number | null;
    catalogItem: {
      name: string;
      unit: string;
      itemType: CatalogItemType;
      netSellingUnitPrice: number;
      netProcurementUnitCost: number | null;
      estimatedOwnLaborUnitCost: number | null;
      calculationConfig: CatalogCalculationConfig | null;
    } | null;
    settlementMode: "MATERIAL_AND_LABOR" | "LABOR_ONLY" | "CUSTOMER_SUPPLIED_MATERIAL";
    requirements: {
      requirementId: string;
      component: "MATERIAL" | "LABOR" | "OTHER";
      requiredQuantity: SnapshotQuantity | null;
      allowanceAdjustedQuantity: SnapshotQuantity | null;
      procurementQuantity: SnapshotQuantity | null;
      customerChargeQuantity: SnapshotQuantity | null;
      netUnitCost: number | null;
      directCost: number | null;
    }[];
    calculationPlan: {
      inputs: {
        id: string | null;
        value: number | null;
        unit: string | null;
        source: CalculationInputSource;
        approved: boolean;
        evidence: string | null;
      }[];
      steps: {
        id: string | null;
        operation: string | null;
        operands: string[] | null;
        outputUnit: string | null;
        scale: number | null;
        roundingMode: string | null;
      }[];
      outputReference: string | null;
      reviewReasons: string[];
    } | null;
    calculationResult: {
      status: CalculationStatus;
      quantity: SnapshotQuantity | null;
      issues: {code: string; message: string; reference: string | null}[];
      trace: {stepId: string; operation: string; operands: string[]; result: SnapshotQuantity}[];
    };
    requiredQuantity: SnapshotQuantity | null;
    procurementQuantity: SnapshotQuantity | null;
    customerChargeQuantity: SnapshotQuantity | null;
    userOverrideQuantity: SnapshotQuantity | null;
    assumptionsApproved: boolean;
    approvedFingerprint: string | null;
    explanation: string | null;
    netLineTotal: number | null;
    chargeable: boolean;
    procurementPackageCount: SnapshotQuantity | null;
    assumptions: string[];
    procurementOutputReference: string | null;
    procurementPackageCountReference: string | null;
  }[];
};

export type GenerateDraftResponse = {
  draftId: number;
  draftBody: string;
  unmappedRequests?: string[];
  reviewWarnings?: string[];
  lineItems?: DraftLineItem[];
  clientName?: string | null;
  additionalNotes?: string | null;
  includeAdditionalNotes: boolean;
  vatRegistered: boolean;
  coverageItems?: CoverageWorkItem[];
  coverageWarnings?: CoverageWarning[];
  analysisFresh?: boolean;
  gmailDraftCreated?: boolean;
  quoteSnapshot?: DraftQuoteSnapshot | null;
  calculationStatus?: CalculationStatus;
  netQuotePrice?: number | null;
};

export type DraftLineItem = {
  name: string;
  unit: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
  lineItemId?: string;
  catalogItemId?: number | null;
  priceBasis: "NET";
};

export type AnalysisResponse = {
  draftId: number;
  clientName: string | null;
  lineItems: DraftLineItem[];
  unmappedRequests: string[];
  reviewWarnings: string[];
  additionalNotes: string | null;
  includeAdditionalNotes: boolean;
  vatRegistered: boolean;
  coverageItems: CoverageWorkItem[];
  coverageWarnings: CoverageWarning[];
  analysisFresh: boolean;
  quoteSnapshot?: DraftQuoteSnapshot | null;
  calculationStatus?: CalculationStatus;
  netQuotePrice?: number | null;
};

export type CoverageStatus = "INCLUDED" | "NEEDS_REVIEW" | "MISSING" | "EXCLUDED";

export type CoverageWorkItem = {
  workId: string;
  name: string;
  explanation: string;
  source: "EMAIL" | "ADDITIONAL_INFO" | "UNKNOWN";
  quantity: number | null;
  unit: string | null;
  status: CoverageStatus;
  linkedLineItemId: string | null;
  userConfirmed: boolean;
  linkedLineItemIds?: string[];
  recognitionStatus?: WorkRecognitionStatus;
  calculationStatus?: CalculationStatus | null;
};

export type CoverageWarning = {
  warningId: string;
  text: string;
  acknowledged: boolean;
};

export type CoverageResponse = {
  workItems: CoverageWorkItem[];
  warnings: CoverageWarning[];
  analysisFresh: boolean;
  unresolvedIssues: string[];
  quoteFingerprint: string;
};

export type FinalizeDraftRequest = {
  clientName: string | null;
  lineItems: DraftLineItem[];
  additionalNotes?: string | null;
  includeAdditionalNotes?: boolean;
  confirmIncomplete?: boolean;
  confirmedFingerprint?: string | null;
};

export type SaveDraftRequest = {
  clientName: string | null;
  lineItems: DraftLineItem[];
  additionalNotes?: string | null;
  includeAdditionalNotes?: boolean;
};

export type CatalogItemType = "MATERIAL" | "LABOR" | "COMBINED" | "OTHER";

export type CatalogCalculationConfig = {
  allowedCalculations: string[];
  defaultParameters: Record<string, {value: number; unit: string; source: string}>;
  parameterConstraints: Record<string, {
    unit: string;
    required: boolean;
    minimum: number | null;
    maximum: number | null;
  }>;
  materialAllowancePercent: number | null;
  roundingPolicy: {scale: number; mode: string} | null;
};

export type CatalogItem = {
  id: number;
  name: string;
  netSellingUnitPrice: number;
  unit: string;
  itemType: CatalogItemType;
  netProcurementUnitCost: number | null;
  estimatedOwnLaborUnitCost: number | null;
  calculationConfig: CatalogCalculationConfig | null;
  createdAt: string;
};

export type CompanyProfile = {
  companyName: string;
  legalForm: string;
  taxNumber: string;
  euTaxNumber: string | null;
  offerorName: string | null;
  offerorPhone: string | null;
  country: string;
  postalCode: string;
  city: string;
  streetName: string;
  streetType: string;
  houseNumber: string;
  building: string | null;
  staircase: string | null;
  floor: string | null;
  door: string | null;
  vatRegistered: boolean;
  offerTerms: string | null;
};

export type SaveCompanyProfileRequest = Omit<CompanyProfile, "country">;
