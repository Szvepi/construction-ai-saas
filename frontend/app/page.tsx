"use client";

import Image from "next/image";
import {API_BASE_URL} from "@/lib/config";
import {Button} from "@/components/ui/Button";

export default function HomePage() {
    return (
        <main className="flex min-h-screen flex-col items-center justify-center px-4">
            <Image
                src="/logo.png"
                alt="MIRA logó"
                width={360}
                height={360}
                className="mb-4 h-80 w-80 object-contain"
                priority
            />
            <h1 className="text-3xl font-bold text-brand-900">MIRA</h1>
            <p className="mt-2 max-w-md text-center text-slate-600">
                Az okos asszisztens az építkezésen
            </p>
            <div className="mt-8">
                <Button
                    onClick={() => {
                        window.location.href = `${API_BASE_URL}/oauth2/authorization/google`;
                    }}
                    className="rounded-lg bg-brand-600 px-5 py-2.5 text-white hover:bg-brand-700"
                >
                    Bejelentkezés Google‑lal
                </Button>
            </div>
        </main>
    );
}
