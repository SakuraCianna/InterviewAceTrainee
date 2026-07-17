function randomPart() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const values = crypto.getRandomValues(new Uint32Array(4));
    return Array.from(values, (value) => value.toString(36)).join("-");
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** 为一次明确的业务操作创建幂等键；调用方负责在该操作的网络重试期间复用。 */
export function createIdempotencyKey() {
  return `idem-${randomPart()}`;
}
