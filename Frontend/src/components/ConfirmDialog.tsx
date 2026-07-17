import { AppIcon } from "./AppIcon";
import styles from "./ConfirmDialog.module.css";

type ConfirmDialogProps = {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: "danger" | "info";
  onConfirm: () => void;
  onCancel: () => void;
};

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = "确认",
  cancelLabel = "取消",
  variant = "danger",
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null;
  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-labelledby="confirm-title">
      <div className={styles.dialog}>
        <div className={`${styles.icon} ${variant === "info" ? styles.info : ""}`}>
          <AppIcon icon={variant === "info" ? "lucide:info" : "lucide:alert-triangle"} size={22} />
        </div>
        <h2 className={styles.title} id="confirm-title">{title}</h2>
        <p className={styles.message}>{message}</p>
        <div className={styles.actions}>
          <button type="button" className={styles.cancelButton} onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`${styles.confirmButton} ${variant === "info" ? styles.info : ""}`}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
