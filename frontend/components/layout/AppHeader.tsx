"use client";

import {useRouter} from "next/navigation";
import {clearToken} from "@/lib/auth";
import {Button} from "@/components/ui/Button";

type Props = {
    title?: string;
    menuOpen?: boolean;
    onMenuToggle?: () => void;
};

export function AppHeader({
    title = "Ajánlatkészítő",
    menuOpen = false,
    onMenuToggle,
}: Props) {
    const router = useRouter();

    function logout() {
        clearToken();
        router.push("/auth/login");
    }

    return (
        <header className="sticky top-0 z-30 flex h-16 items-center justify-between border-b border-slate-200/80 bg-white/95 px-4 shadow-sm shadow-slate-900/[0.02] backdrop-blur sm:px-6">
            <div className="flex min-w-0 items-center gap-3">
                {onMenuToggle && (
                    <Button
                        type="button"
                        variant="ghost"
                        className="!h-10 !w-10 !px-0 lg:hidden"
                        onClick={onMenuToggle}
                        aria-label={menuOpen ? "Navigáció bezárása" : "Navigáció megnyitása"}
                        aria-expanded={menuOpen}
                        aria-controls="mobile-primary-navigation"
                    >
                        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" className="mx-auto h-5 w-5">
                            {menuOpen ? (
                                <path d="m6 6 12 12M18 6 6 18" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
                            ) : (
                                <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
                            )}
                        </svg>
                    </Button>
                )}
                <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-slate-900">{title}</p>
                    <p className="hidden text-xs text-slate-500 sm:block">MIRA · AI-ajánlatkészítés</p>
                </div>
            </div>
            <Button variant="ghost" onClick={logout} className="shrink-0">
                Kijelentkezés
            </Button>
        </header>
    );
}
