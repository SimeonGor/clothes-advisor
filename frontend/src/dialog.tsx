import React from "react";
import styles from "./site.module.css";

export function Dialog({ open, title, onClose, children, className }: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  className?: string;
}) {
  const ref = React.useRef<HTMLDialogElement>(null);
  React.useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const previousFocus = document.activeElement;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
    return () => {
      if (dialog.open) dialog.close();
      if (open && previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, [open]);
  return <dialog ref={ref} className={`${styles.dialog} ${className ?? ""}`} aria-label={title} onCancel={(event) => { event.preventDefault(); onClose(); }} onClick={(event) => { if (event.target === ref.current) onClose(); }}>
    <div className={styles.dialogHeader}><h2>{title}</h2><button type="button" onClick={onClose} aria-label="Закрыть">×</button></div>
    {children}
  </dialog>;
}
