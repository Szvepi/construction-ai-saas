"use client";

import {useEffect, useState} from "react";
import {getToken} from "@/lib/auth";

const STORAGE_KEY_PREFIX = "mira-catalog-info-dismissed";

function getStorageKey(): string | null {
    const token = getToken();
    const payloadSegment = token?.split(".")[1];
    if (!payloadSegment) {
        return null;
    }

    try {
        const base64 = payloadSegment.replace(/-/g, "+").replace(/_/g, "/");
        const payload = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="))) as {
            sub?: unknown;
        };
        const userId = payload.sub;
        if ((typeof userId !== "string" && typeof userId !== "number") || !userId) {
            throw new Error("The authentication token does not contain a user ID.");
        }

        return `${STORAGE_KEY_PREFIX}:${userId}`;
    } catch (error) {
        console.error("Unable to determine the catalog banner storage key.", error);
        return null;
    }
}

export function CatalogInfoBanner() {
    const [storageKey, setStorageKey] = useState<string | null>(null);
    const [isVisible, setIsVisible] = useState(false);
    const [isReady, setIsReady] = useState(false);

    useEffect(() => {
        const key = getStorageKey();
        setStorageKey(key);

        if (!key) {
            setIsVisible(true);
            setIsReady(true);
            return;
        }

        try {
            setIsVisible(window.localStorage.getItem(key) !== "dismissed");
        } catch (error) {
            console.error("Unable to read the catalog banner dismissal state.", error);
            setIsVisible(true);
        } finally {
            setIsReady(true);
        }
    }, []);

    const dismiss = () => {
        if (storageKey) {
            try {
                window.localStorage.setItem(storageKey, "dismissed");
            } catch (error) {
                console.error("Unable to save the catalog banner dismissal state.", error);
            }
        }
        setIsVisible(false);
    };

    if (!isReady || !isVisible) {
        return null;
    }

    return (
        <section
            aria-labelledby="catalog-info-title"
            className="relative rounded-xl border border-blue-200 bg-blue-50 px-5 py-4 pr-12 text-slate-700 shadow-sm"
        >
            <button
                type="button"
                onClick={dismiss}
                aria-label="Tájékoztató bezárása"
                className="absolute right-3 top-3 rounded-md p-1 text-slate-500 transition hover:bg-blue-100 hover:text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
                <span aria-hidden="true" className="text-xl leading-none">×</span>
            </button>
            <h2 id="catalog-info-title" className="text-lg font-semibold text-slate-900">
                💡 Hogyan működik a MIRA Katalógus?
            </h2>
            <p className="mt-1 text-sm text-slate-600">
                A MIRA ebből az árjegyzékből dolgozik, amikor a beérkező levelekből automatikusan ajánlatot generál.
            </p>
            <ol className="mt-3 list-decimal space-y-1 pl-5 text-sm text-slate-700">
                <li>Vedd fel a saját munkafázisaidat és alapértelmezett áraidat.</li>
                <li>Rendelj hozzájuk AI számítási szabályt (pl. vágási veszteség burkolásnál, vagy területről köbméter
                    számítás).
                </li>
                <li>Mira ezen szabályok alapján fogja kiszámolni a pontos tételeket az ügyfelek leveleiből.</li>
            </ol>
        </section>
    );
}
