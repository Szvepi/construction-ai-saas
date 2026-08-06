export type EmailSummary = {
  id: number;
  subject: string;
  fromAddress: string;
  receivedAt: string;
  replied: boolean;
  category: "QUOTE_REQUEST" | "SPAM" | "OTHER";
};

export type EmailDetail = EmailSummary & { bodyText: string; draftEmail?: GenerateDraftResponse | null };

export type GmailStatus = { connected: boolean; gmailAddress: string | null };

export type GenerateDraftResponse = { draftId: number; draftBody: string };

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

export const STRATEGY_DESCRIPTIONS: Record<CalculationStrategy, string> = {
  DIRECT: "Nincs módosítás (pl. darabszám, közvetlen m2)",
  WALL_SURFACE_3X: "Falfelület becslés (alapterület x 3 - Festőknek)",
  ROOM_PERIMETER: "Szoba kerület számítás (Lábazat, szegélyléc)",
  VOLUME_BY_THICKNESS: "Köbméter számítás területből (Kőműves alapozás)",
  WASTE_PERCENTAGE_10: "+10% Anyagveszteség számolás (Burkolóknak)",
};

