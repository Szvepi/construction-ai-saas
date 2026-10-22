import { getSessionTimeoutMinutes } from "./config";

const TOKEN_KEY = "buildassist_token";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(TOKEN_KEY, token);
  try {
    const timeoutSeconds = Math.max(1, Math.round(getSessionTimeoutMinutes() * 60));
    document.cookie = `${TOKEN_KEY}=${encodeURIComponent(token)}; Path=/; Max-Age=${timeoutSeconds}; SameSite=Lax`;
  } catch (e) {
    console.warn("Failed to set token cookie", e);
  }
}

export function clearToken(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(TOKEN_KEY);
  try {
    document.cookie = `${TOKEN_KEY}=; Path=/; Max-Age=0; SameSite=Lax`;
  } catch (e) {
    console.warn("Failed to clear token cookie", e);
  }
}

export function isAuthenticated(): boolean {
  return Boolean(getToken());
}
