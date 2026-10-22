"use client";

import Image from "next/image";
import Link from "next/link";
import {usePathname} from "next/navigation";

type Props = {
    onNavigate?: () => void;
};

const links = [
    {
        href: "/dashboard",
        label: "Áttekintés",
        icon: "M3 10.5 12 3l9 7.5M5.5 9v11h13V9M9 20v-6h6v6",
    },
    {
        href: "/dashboard/emails",
        label: "Ajánlatkérések",
        icon: "M4 6.5h16v11H4zM4.5 7l7.5 6 7.5-6",
    },
    {
        href: "/dashboard/settings/catalog",
        label: "Árjegyzék és munkafázisok",
        icon: "m12 3 8.5 4.5v9L12 21l-8.5-4.5v-9L12 3ZM3.8 7.7 12 12l8.2-4.3M12 12v9",
    },
    {
        href: "/dashboard/settings/company",
        label: "Céges beállítások",
        icon: "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7ZM19 13.5a7.4 7.4 0 0 0 0-.9l2-1.5-2-3.5-2.4 1a7.5 7.5 0 0 0-1.6-.9L14.6 5h-4l-.4 2.7a7.5 7.5 0 0 0-1.6.9l-2.4-1-2 3.5 2 1.5a7.4 7.4 0 0 0 0 .9l-2 1.5 2 3.5 2.4-1a7.5 7.5 0 0 0 1.6.9l.4 2.6h4l.4-2.6a7.5 7.5 0 0 0 1.6-.9l2.4 1 2-3.5-2-1.5Z",
    },
];

export function DashboardNav({onNavigate}: Props) {
    const pathname = usePathname();

    return (
        <div className="flex h-full flex-col bg-white">
            <Link
                href="/dashboard"
                onClick={onNavigate}
                className="flex h-20 shrink-0 items-center gap-3 border-b border-slate-100 px-6"
            >
                <Image src="/logo.png" alt="MIRA" width={48} height={48} priority className="h-11 w-11 object-contain"/>
                <span className="text-lg font-bold tracking-tight text-brand-950">MIRA</span>
            </Link>
            <nav aria-label="Fő navigáció" className="flex-1 px-3 py-6">
                <p className="px-3 pb-3 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
                    Munkaterület
                </p>
                <div className="space-y-1">
                {links.map((link) => (
                    <Link
                        key={link.href}
                        href={link.href}
                        onClick={onNavigate}
                        aria-current={
                            pathname === link.href || (link.href !== "/dashboard" && pathname.startsWith(`${link.href}/`))
                                ? "page"
                                : undefined
                        }
                        className={`group flex min-h-11 items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors ${
                            pathname === link.href || (link.href !== "/dashboard" && pathname.startsWith(`${link.href}/`))
                                ? "bg-brand-50 text-brand-700 shadow-sm shadow-brand-900/[0.03]"
                                : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                        }`}
                    >
                        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" className="h-[18px] w-[18px] shrink-0">
                            <path d={link.icon} stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        <span>{link.label}</span>
                    </Link>
                ))}
                </div>
            </nav>
            <div className="border-t border-slate-100 px-6 py-4">
                <p className="text-xs font-medium text-slate-500">MIRA</p>
                <p className="mt-1 text-[11px] text-slate-400">AI-ajánlatkészítő munkaterület</p>
            </div>
        </div>
    );
}
