export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export const DEFAULT_SESSION_TIMEOUT_MINUTES = 30;

export function getSessionTimeoutMinutes(): number {
  const configuredValue = Number(
    process.env.NEXT_PUBLIC_SESSION_TIMEOUT_MINUTES ??
      process.env.NEXT_PUBLIC_IDLE_TIMEOUT_MINUTES ??
      DEFAULT_SESSION_TIMEOUT_MINUTES,
  );

  if (!Number.isFinite(configuredValue) || configuredValue <= 0) {
    return DEFAULT_SESSION_TIMEOUT_MINUTES;
  }

  return configuredValue;
}
