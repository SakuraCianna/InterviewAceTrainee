import { describe, expect, it } from "vitest";
import { createIdempotencyKey } from "./idempotency";

describe("createIdempotencyKey", () => {
  it("每次明确业务操作都生成新键", () => {
    const first = createIdempotencyKey();
    const second = createIdempotencyKey();

    expect(first).toMatch(/^idem-/);
    expect(second).not.toBe(first);
  });
});
