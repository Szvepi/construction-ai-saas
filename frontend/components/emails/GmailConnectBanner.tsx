"use client";

import { Button } from "@/components/ui/Button";
import type { GmailStatus } from "@/lib/types";

type Props = {
  status: GmailStatus | null;
  onConnect: () => void;
  loading?: boolean;
};

export function GmailConnectBanner({ status, onConnect, loading }: Props) {
  if (status?.connected) {
    return (
      <p className="rounded-lg bg-green-50 px-4 py-3 text-sm text-green-800">
        Gmail csatlakoztatva: {status.gmailAddress}
      </p>
    );
  }

  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
      <p className="text-sm text-amber-900">Kapcsold össze Gmail fiókodat az e-mailek lekéréséhez.</p>
      <Button className="mt-2" onClick={onConnect} disabled={loading}>
        Gmail csatlakoztatása
      </Button>
    </div>
  );
}
