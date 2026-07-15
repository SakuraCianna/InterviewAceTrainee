export const REFUND_CREDIT_FIELD_LABEL = "拟补偿次数（仅记录）";

export const REFUND_ACCOUNTING_BOUNDARY_ITEMS = [
  "本工单不会自动退款或修改用户余额。",
  "退款继续通过官方微信人工联系处理。",
  "实际补次必须使用“次数调整”，完成后核对次数流水。",
] as const;

export const REFUND_RESOLVED_NOTE = "已完成人工核查并关闭工单（未自动退款或调整余额）";
export const REFUND_CREATED_BOUNDARY = "未执行退款或余额变更。";
export const REFUND_STATUS_BOUNDARY = "工单状态变更不会自动退款或调整余额。";

type RefundConfirmationInput = {
  email: string;
  reasonLabel: string;
  amountLabel: string;
  proposedCreditAdjustment: number;
};

/** 构造退款工单二次确认文案，始终明确该操作只记录诉求，不执行账务变更。 */
export function buildRefundCaseConfirmation({
  email,
  reasonLabel,
  amountLabel,
  proposedCreditAdjustment,
}: RefundConfirmationInput) {
  return `确认为 ${email} 创建退款纠纷记录？\n原因：${reasonLabel} · 金额：${amountLabel} · ${REFUND_CREDIT_FIELD_LABEL}：${proposedCreditAdjustment}\n此操作仅创建工单，不会自动退款或修改用户余额。`;
}
