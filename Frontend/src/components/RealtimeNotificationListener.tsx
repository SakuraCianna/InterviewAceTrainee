import { useEffect, useState } from "react";
import { AppIcon } from "./AppIcon";
import { CSRF_COOKIE_NAME, getCookie } from "../lib/api";

export type RealtimeEventData = {
  newBalance?: number;
  changeAmount?: number;
  reason?: string;
  sessionId?: string;
  interviewType?: string;
};

type ToastNotification = {
  id: string;
  type: "credit" | "report" | "status" | "offline";
  title: string;
  message: string;
  data?: RealtimeEventData;
};

export function RealtimeNotificationListener() {
  const [toasts, setToasts] = useState<ToastNotification[]>([]);
  const [isOffline, setIsOffline] = useState(!navigator.onLine);

  // 网络状态探活
  useEffect(() => {
    const handleOnline = () => {
      setIsOffline(false);
      setToasts((prev) => prev.filter((t) => t.type !== "offline"));
    };
    const handleOffline = () => {
      setIsOffline(true);
      addToast({
        id: "offline-warning",
        type: "offline",
        title: "网络连接不稳定",
        message: "正在自动重连服务...",
      });
    };

    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  const addToast = (toast: ToastNotification) => {
    setToasts((prev) => [...prev.filter((t) => t.id !== toast.id), toast]);
    if (toast.type !== "offline") {
      setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== toast.id));
      }, 6000);
    }
  };

  // SSE 实时通道监听
  useEffect(() => {
    if (!getCookie(CSRF_COOKIE_NAME)) return;

    let eventSource: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    const connect = () => {
      try {
        eventSource = new EventSource("/api/notifications/stream", { withCredentials: true });

        eventSource.addEventListener("USER_CREDIT_UPDATED", (event) => {
          try {
            const data: RealtimeEventData = JSON.parse(event.data);
            const deltaStr = data.changeAmount && data.changeAmount > 0 ? `+${data.changeAmount}` : `${data.changeAmount}`;
            addToast({
              id: `credit-${Date.now()}`,
              type: "credit",
              title: "训练额度实时更新",
              message: `管理员已为您更新点数 (${deltaStr} 次)，当前可用 ${data.newBalance} 次。`,
              data,
            });

            // 触发全局点数更新事件
            window.dispatchEvent(
              new CustomEvent("mianba:credit_updated", { detail: data })
            );
          } catch (e) {
            console.error("Parse SSE credit update error", e);
          }
        });

        eventSource.addEventListener("INTERVIEW_REPORT_READY", (event) => {
          try {
            const data: RealtimeEventData = JSON.parse(event.data);
            addToast({
              id: `report-${Date.now()}`,
              type: "report",
              title: "评估报告已就绪",
              message: "您的面试复盘报告已后台生成完毕，点击跳转查看。",
              data,
            });
            window.dispatchEvent(
              new CustomEvent("mianba:report_ready", { detail: data })
            );
          } catch (e) {
            console.error("Parse SSE report ready error", e);
          }
        });

        eventSource.onerror = () => {
          if (eventSource) {
            eventSource.close();
            eventSource = null;
          }
          // 8 秒后自动尝试重连
          reconnectTimer = setTimeout(connect, 8000);
        };
      } catch (err) {
        console.error("SSE connection error", err);
      }
    };

    connect();

    return () => {
      if (eventSource) eventSource.close();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, []);

  if (toasts.length === 0 && !isOffline) return null;

  return (
    <div className="fixed top-5 right-5 z-[9999] flex flex-col gap-3 max-w-sm pointer-events-none">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`pointer-events-auto flex items-start gap-3 p-4 rounded-xl shadow-xl backdrop-blur-md transition-all duration-300 transform translate-y-0 ${
            toast.type === "credit"
              ? "bg-emerald-950/90 text-emerald-100 border border-emerald-500/40 shadow-emerald-900/30"
              : toast.type === "report"
              ? "bg-blue-950/90 text-blue-100 border border-blue-500/40 shadow-blue-900/30"
              : "bg-amber-950/90 text-amber-100 border border-amber-500/40 shadow-amber-900/30"
          }`}
          style={{
            animation: "slideInRight 0.3s cubic-bezier(0.16, 1, 0.3, 1) forwards",
          }}
        >
          <div className="p-2 rounded-lg bg-white/10 shrink-0">
            <AppIcon
              icon={
                toast.type === "credit"
                  ? "lucide:sparkles"
                  : toast.type === "report"
                  ? "lucide:file-text"
                  : "lucide:wifi-off"
              }
              className="w-5 h-5"
            />
          </div>
          <div className="flex-1 min-w-0">
            <h4 className="font-semibold text-sm leading-tight mb-1">{toast.title}</h4>
            <p className="text-xs text-white/80 leading-normal">{toast.message}</p>
          </div>
          <button
            onClick={() => setToasts((prev) => prev.filter((t) => t.id !== toast.id))}
            className="text-white/60 hover:text-white transition-colors"
          >
            <AppIcon icon="lucide:x" className="w-4 h-4" />
          </button>
        </div>
      ))}
    </div>
  );
}
