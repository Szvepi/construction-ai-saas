export type AuthResponse = { token: string; email: string };

export type EmailSummary = {
  id: number;
  subject: string;
  fromAddress: string;
  receivedAt: string;
  replied: boolean;
};

export type EmailDetail = EmailSummary & { bodyText: string };

export type GmailStatus = { connected: boolean; gmailAddress: string | null };

export type GenerateDraftResponse = { draftId: number; draftBody: string };
