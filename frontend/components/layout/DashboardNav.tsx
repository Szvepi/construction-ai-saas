"use client";

import Link from "next/link";
import {usePathname} from "next/navigation";

export function DashboardNav() {
    const pathname = usePathname();

    const links = [
        {href: "/dashboard", label: "Áttekintés"},
        {href: "/dashboard/emails", label: "Beérkezett levelek"},
        {href: "/dashboard/settings/catalog", label: "Katalógus beállítások"},
        {href: "/dashboard/settings/company", label: "Céges beállítások"},
    ];

    return (
        <nav className="border-b border-slate-200 bg-white px-4">
            <div className="mx-auto flex max-w-5xl gap-6 overflow-x-auto">
                {links.map((link) => (
                    <Link
                        key={link.href}
                        href={link.href}
                        className={`py-3 px-1 border-b-2 font-medium transition-colors ${
                            (pathname === link.href ||
                                (link.href !== "/dashboard" && pathname.startsWith(`${link.href}/`)))
                                ? "border-blue-600 text-blue-600"
                                : "border-transparent text-slate-600 hover:text-slate-900"
                        }`}
                    >
                        {link.label}
                    </Link>
                ))}
            </div>
        </nav>
    );
}
