import { describe, expect, it } from "vitest";
import {
  buildRefundCaseConfirmation,
  REFUND_ACCOUNTING_BOUNDARY_ITEMS,
  REFUND_CREDIT_FIELD_LABEL,
  REFUND_RESOLVED_NOTE,
} from "./refundAccounting";

describe("退款工单账务边界文案", () => {
  it("明确拟补偿次数仅记录，实际补次必须走次数调整", () => {
    expect(REFUND_CREDIT_FIELD_LABEL).toBe("拟补偿次数（仅记录）");
    expect(REFUND_ACCOUNTING_BOUNDARY_ITEMS).toContain("实际补次必须使用“次数调整”，完成后核对次数流水。");
  });

  it("确认和关闭文案不暗示已自动退款或补次", () => {
    const confirmation = buildRefundCaseConfirmation({
      email: "user@example.com",
      reasonLabel: "退款申请",
      amountLabel: "¥19.90",
      proposedCreditAdjustment: 1,
    });

    expect(confirmation).toContain("仅创建工单，不会自动退款或修改用户余额");
    expect(confirmation).toContain("拟补偿次数（仅记录）：1");
    expect(confirmation).not.toContain("已补偿");
    expect(REFUND_RESOLVED_NOTE).toContain("未自动退款或调整余额");
  });
});
