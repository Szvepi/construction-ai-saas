export type EmailSummary = {
  id: number;
  subject: string;
  fromAddress: string;
  receivedAt: string;
  replied: boolean;
  category: "QUOTE_REQUEST" | "SPAM" | "OTHER";
  draftStatus: "DRAFT" | "PENDING_REVIEW" | "READY" | "SENT" | null;
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
};

export type DraftLineItem = {
  name: string;
  unit: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
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
};

export type FinalizeDraftRequest = {
  clientName: string | null;
  lineItems: DraftLineItem[];
  additionalNotes?: string | null;
  includeAdditionalNotes?: boolean;
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
