import { modules } from "./modules";
import type { InterviewType } from "./types";

export function createSessionId() {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }

  // 会话 ID 由服务端继续做归属校验；此回退只负责生成符合 UUID v4 语法的客户端幂等标识。
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === "function") {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
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
