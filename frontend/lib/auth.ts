const TOKEN_KEY = "buildassist_token";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(TOKEN_KEY, token);
  try {
    // Set a non-HttpOnly cookie so middleware can read it on subsequent requests
    // 1 day expiry (86400 seconds). Adjust flags as needed for production (Secure, SameSite, HttpOnly server-set).
    document.cookie = `${TOKEN_KEY}=${encodeURIComponent(token)}; Path=/; Max-Age=86400; SameSite=Lax`;
  } catch (e) {
    // ignore cookie failures
    console.warn("Failed to set token cookie", e);
  }
}

export function clearToken(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(TOKEN_KEY);
  try {
    // Remove cookie by setting max-age=0
    document.cookie = `${TOKEN_KEY}=; Path=/; Max-Age=0; SameSite=Lax`;
  } catch (e) {
    console.warn("Failed to clear token cookie", e);
  }
}

export function isAuthenticated(): boolean {
  return Boolean(getToken());
}
