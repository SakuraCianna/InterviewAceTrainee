import type { SystemConfig } from "./types";

export function parseOptionalInteger(rawValue: string) {
  const trimmed = rawValue.trim();
  if (!trimmed) {
    return undefined;
  }
  const value = Number.parseInt(trimmed, 10);
  return Number.isNaN(value) ? null : value;
}

export function parseOptionalAmountCents(rawValue: string) {
  const trimmed = rawValue.trim();
  if (!trimmed) {
    return undefined;
  }
  const value = Number.parseFloat(trimmed);
  return Number.isNaN(value) || value < 0 ? null : Math.round(value * 100);
}

export function configInputValue(value: SystemConfig["value"]) {
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return JSON.stringify(value);
}

export function parseConfigInput(currentValue: SystemConfig["value"], rawValue: string) {
  if (typeof currentValue === "boolean") {
    return rawValue === "true";
  }
  if (typeof currentValue === "number") {
    const parsed = Number(rawValue);
    if (Number.isNaN(parsed)) {
      throw new Error("invalid_number_config");
    }
    return parsed;
  }
  if (Array.isArray(currentValue) || (currentValue && typeof currentValue === "object")) {
    return JSON.parse(rawValue);
  }
  return rawValue;
}
