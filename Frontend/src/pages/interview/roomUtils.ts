import { modules } from "./modules";
import type { InterviewType } from "./types";

export function createSessionId() {
  const random = Math.random().toString(36).slice(2, 8);
  return `session-${Date.now()}-${random}`;
}

export function moduleByType(type: InterviewType) {
  return modules.find((module) => module.type === type) ?? modules[0];
}

export function microphoneLabel(device: MediaDeviceInfo, index: number) {
  return device.label || `麦克风 ${index + 1}`;
}

export function statusLabel(status: string) {
  return status === "completed" ? "已完成" : "进行中";
}

export function formatHistoryDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "刚刚";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
