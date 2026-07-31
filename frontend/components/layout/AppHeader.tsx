"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { clearToken } from "@/lib/auth";
import { Button } from "@/components/ui/Button";

type Props = { title?: string };

export function AppHeader({ title = "BuildAssist" }: Props) {
  const router = useRouter();

  function logout() {
    clearToken();
    router.push("/auth/login");
  }

  return (
    <header className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3">
      <Link href="/dashboard" className="font-semibold text-brand-900">
        {title}
      </Link>
      <Button variant="ghost" onClick={logout}>
        Kijelentkezés
      </Button>
    </header>
  );
}
