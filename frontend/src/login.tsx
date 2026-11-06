import React from "react";
import { ApiError } from "./api-error";
import { login } from "./auth";
import type { Actor } from "./api";
import styles from "./site.module.css";

export function Login({ onLogin, disabled = false }: { onLogin: (actor: Actor) => void; disabled?: boolean }) {
  const [name, setName] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const attempt = React.useRef<AbortController | null>(null);
  React.useEffect(() => () => attempt.current?.abort(), []);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (attempt.current || disabled) return;
    const controller = new AbortController();
    attempt.current = controller;
    setBusy(true);
    setError("");
    try {
      const actor = await login(name, password, controller.signal);
      if (!controller.signal.aborted) { setPassword(""); onLogin(actor); }
    } catch (failure) {
      if (controller.signal.aborted) return;
      setPassword("");
      setError(failure instanceof ApiError && failure.status === 401 ? "Неверный логин или пароль."
        : failure instanceof ApiError && failure.status === 403 ? "Вход недоступен. Обратитесь к администратору."
          : "Не удалось войти. Проверьте подключение и повторите попытку.");
    } finally {
      if (!controller.signal.aborted) { attempt.current = null; setBusy(false); }
    }
  }
  return <main className={styles.pick} id="main">
    <p className={styles.eyebrow}>ГАРДЕРОБ · ПЕРСОНАЛЬНЫЙ СТИЛЬ</p><h1>Вход</h1>
    <form className={styles.itemForm} onSubmit={(event) => void submit(event)}>
      <label className={styles.nameField}>Логин<input required maxLength={100} autoComplete="username"
        value={name} disabled={busy || disabled} onChange={(event) => setName(event.target.value)} /></label>
      <label className={styles.nameField}>Пароль<input required type="password" autoComplete="current-password"
        value={password} disabled={busy || disabled} onChange={(event) => setPassword(event.target.value)} /></label>
      {error && <p role="alert" className={styles.error}>{error}</p>}
      <button type="submit" className={styles.create} disabled={busy || disabled}>{busy ? "Входим…" : "Войти"}</button>
    </form>
    <p className={styles.muted}>Чтобы получить аккаунт или восстановить пароль, обратитесь к администратору.</p>
  </main>;
}
