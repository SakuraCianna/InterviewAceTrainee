export function normalizeEmail(email: string) {
  return email.trim().toLowerCase();
}

export function emailCodeCooldownKey(prefix: string, email: string) {
  return `${prefix}${normalizeEmail(email)}`;
}

export function secondsUntil(timestamp: number) {
  return Math.max(0, Math.ceil((timestamp - Date.now()) / 1000));
}

export function retryAfterSeconds(response: Response, fallbackSeconds: number) {
  const rawValue = response.headers.get("Retry-After");
  const parsedValue = rawValue ? Number.parseInt(rawValue, 10) : fallbackSeconds;
  return Number.isFinite(parsedValue) && parsedValue > 0 ? parsedValue : fallbackSeconds;
}
