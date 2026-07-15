import { describe, expect, it } from "vitest";
import { createSessionId, formatHistoryDate, statusLabel } from "./roomUtils";

describe("roomUtils", () => {
  it("creates a backend-compatible UUID session id", () => {
    expect(createSessionId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
  });

  it("keeps status labels and invalid dates user friendly", () => {
    expect(statusLabel("completed")).toBe("已完成");
    expect(statusLabel("active")).toBe("进行中");
    expect(formatHistoryDate("not-a-date")).toBe("刚刚");
  });
});
