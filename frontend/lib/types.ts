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
};

export type DraftLineItem = {
  name: string;
  unit: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
  lineItemId?: string;
  catalogItemId?: number | null;
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

export type CalculationStrategy =
  | "DIRECT"
  | "WALL_SURFACE_3X"
  | "ROOM_PERIMETER"
  | "VOLUME_BY_THICKNESS"
  | "WASTE_PERCENTAGE_10";

export type CatalogItem = {
  id: number;
  name: string;
  unitPrice: number;
  unit: string;
  calculationStrategy: CalculationStrategy | null;
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

export const STRATEGY_DESCRIPTIONS: Record<CalculationStrategy, string> = {
  DIRECT: "Nincs módosítás (pl. darabszám, közvetlen m2)",
  WALL_SURFACE_3X: "Falfelület becslés (alapterület x 3 - Festőknek)",
  ROOM_PERIMETER: "Szoba kerület számítás (Lábazat, szegélyléc)",
  VOLUME_BY_THICKNESS: "Köbméter számítás területből (Kőműves alapozás)",
  WASTE_PERCENTAGE_10: "+10% Anyagveszteség számolás (Burkolóknak)",
};
