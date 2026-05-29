import { useCallback, useEffect, useState } from "react";
import { emailCodeCooldownKey, normalizeEmail, secondsUntil } from "../lib/emailCooldown";

type EmailCodeCooldownOptions = {
  email: string;
  storagePrefix: string;
  defaultSeconds: number;
};

export function useEmailCodeCooldown({ email, storagePrefix, defaultSeconds }: EmailCodeCooldownOptions) {
  const [cooldownSeconds, setCooldownSeconds] = useState(0);
  const normalizedEmail = normalizeEmail(email);
  const storageKey = normalizedEmail ? emailCodeCooldownKey(storagePrefix, normalizedEmail) : "";

  const refreshCooldown = useCallback(() => {
    if (!normalizedEmail) {
      setCooldownSeconds(0);
      return 0;
    }
    const nextAllowedAt = Number.parseInt(window.localStorage.getItem(storageKey) ?? "0", 10);
    const seconds = secondsUntil(nextAllowedAt);
    setCooldownSeconds(seconds);
    return seconds;
  }, [normalizedEmail, storageKey]);

  const startCooldown = useCallback(
    (seconds = defaultSeconds) => {
      if (!normalizedEmail) {
        return;
      }
      const safeSeconds = Math.max(1, seconds);
      window.localStorage.setItem(storageKey, String(Date.now() + safeSeconds * 1000));
      setCooldownSeconds(safeSeconds);
    },
    [defaultSeconds, normalizedEmail, storageKey],
  );

  useEffect(() => {
    refreshCooldown();
  }, [refreshCooldown]);

  useEffect(() => {
    if (cooldownSeconds <= 0 || !normalizedEmail) {
      return;
    }
    const timer = window.setInterval(refreshCooldown, 1000);
    return () => window.clearInterval(timer);
  }, [cooldownSeconds, normalizedEmail, refreshCooldown]);

  return { cooldownSeconds, startCooldown };
}
