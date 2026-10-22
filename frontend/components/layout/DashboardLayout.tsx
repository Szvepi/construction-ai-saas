"use client";

import {useEffect, useState} from "react";
import {usePathname} from "next/navigation";
import {AppHeader} from "@/components/layout/AppHeader";
import {DashboardNav} from "@/components/layout/DashboardNav";

const PAGE_TITLES: Record<string, string> = {
    "/dashboard": "Áttekintés",
    "/dashboard/emails": "Ajánlatkérések",
    "/dashboard/settings/catalog": "Árjegyzék és munkafázisok",
    "/dashboard/settings/company": "Céges beállítások",
};

export function DashboardLayout({children}: Readonly<{children: React.ReactNode}>) {
    const pathname = usePathname();
    const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
    const pageTitle = PAGE_TITLES[pathname] ?? (
        pathname.startsWith("/emails/") ? "Ajánlatkérés részletei" : "Ajánlatkészítő"
    );

    useEffect(() => {
        if (!mobileMenuOpen) return;

        function closeOnEscape(event: KeyboardEvent) {
            if (event.key === "Escape") setMobileMenuOpen(false);
        }

        window.addEventListener("keydown", closeOnEscape);
        return () => window.removeEventListener("keydown", closeOnEscape);
    }, [mobileMenuOpen]);

    return (
        <div className="min-h-screen bg-slate-50">
            <aside className="fixed inset-y-0 left-0 z-20 hidden w-64 border-r border-slate-200/80 bg-white lg:block">
                <DashboardNav/>
            </aside>

            {mobileMenuOpen && (
                <div className="fixed inset-0 z-40 lg:hidden">
                    <button
                        type="button"
                        aria-label="Navigáció bezárása"
                        className="absolute inset-0 bg-slate-950/40 backdrop-blur-[2px]"
                        onClick={() => setMobileMenuOpen(false)}
                    />
                    <aside
                        id="mobile-primary-navigation"
                        aria-label="Mobil fő navigáció"
                        className="relative h-full w-[min(19rem,85vw)] border-r border-slate-200 bg-white shadow-2xl"
                    >
                        <DashboardNav onNavigate={() => setMobileMenuOpen(false)}/>
                    </aside>
                </div>
            )}

            <div className="min-h-screen lg:pl-64">
                <AppHeader
                    title={pageTitle}
                    menuOpen={mobileMenuOpen}
                    onMenuToggle={() => setMobileMenuOpen((open) => !open)}
                />
                <div className="min-w-0">{children}</div>
            </div>
        </div>
    );
}
