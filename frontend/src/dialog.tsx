import React from "react";
import styles from "./site.module.css";

export function Dialog({ open, title, onClose, children }: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const ref = React.useRef<HTMLDialogElement>(null);
  React.useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);
  return <dialog ref={ref} className={styles.dialog} aria-label={title} onCancel={(event) => { event.preventDefault(); onClose(); }} onClick={(event) => { if (event.target === ref.current) onClose(); }}>
    <div className={styles.dialogHeader}><h2>{title}</h2><button type="button" onClick={onClose} aria-label="Закрыть">×</button></div>
    {children}
  </dialog>;
}
