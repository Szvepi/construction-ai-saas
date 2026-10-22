"use client";

import Image from "next/image";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {clearToken} from "@/lib/auth";
import {Button} from "@/components/ui/Button";

type Props = { title?: string };

export function AppHeader({title = "MIRA"}: Props) {
    const router = useRouter();

    function logout() {
        clearToken();
        router.push("/auth/login");
    }

    return (
        <header className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3">
            <Link href="/dashboard" className="flex items-center gap-2 font-semibold text-brand-900">
                <Image src="/logo.png" alt="" width={128} height={128} className="h-16 w-16 object-contain"/>
                {title}
            </Link>
            <Button variant="ghost" onClick={logout}>
                Kijelentkezés
            </Button>
        </header>
    );
}
