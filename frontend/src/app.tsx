import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { SiteShell, Today } from "./site";
import { Wardrobe, WardrobeEditor } from "./wardrobe";
import { OutfitDetail, OutfitEditor, Outfits } from "./outfits";
import { Access, AccountCreator, Accounts, Clients, OwnerWorkspace } from "./people";
import { Preferences } from "./preferences";
import { Calendar, CalendarEditor } from "./calendar";
import { StylistRequestDetail, StylistRequestEditor, StylistRequests } from "./stylist-requests";
import { CapsuleDetail, CapsuleEditor, Capsules } from "./capsules";
import { AiCapsuleEditor } from "./ai-capsule";
import { ApiError } from "./api-error";
import { getSession, getSessionContext, needsSessionRecovery, subscribeSession, refreshSession, logout } from "./auth";
import { Login } from "./login";
import type { Actor } from "./api";
import styles from "./site.module.css";

const landing = (actor: Actor) => actor.role === "USER" ? "/today" : actor.role === "STYLIST" ? "/clients" : "/accounts";

export function AppRoutes({ account, onLogout, onPending, pending }: {
  account: Actor;
  onLogout: () => void;
  onPending: (pending: boolean) => void;
  pending: boolean;
}) {
  const location = useLocation();
  function exitAccount() {
    if (pending) return;
    onLogout();
  }
  const path = location.pathname;
  const identity = `${account.id}:${path}`;
  const props = { actor: account, onPending, pending };
  const active = path.startsWith("/clients") ? "clients" : path.startsWith("/accounts") ? "accounts"
    : path.startsWith("/preferences") ? "preferences" : path.startsWith("/access") ? "access"
      : path.startsWith("/requests") ? "requests" : path.startsWith("/calendar") ? "calendar"
        : path.startsWith("/capsules") ? "capsules" : path.startsWith("/outfits") ? "outfits" : "wardrobe";
  if (path === "/today" && account.role === "USER") {
    return <Today key={identity} account={account} onLogout={exitAccount} onPending={onPending} pending={pending} />;
  }
  return <SiteShell account={account} onLogout={exitAccount} pending={pending} active={active}>
    <Routes>
      <Route path="/wardrobe" element={<Wardrobe key={identity} {...props} />} />
      <Route path="/wardrobe/new" element={<WardrobeEditor key={identity} {...props} />} />
      <Route path="/wardrobe/:itemId" element={<WardrobeEditor key={identity} {...props} />} />
      <Route path="/outfits" element={<Outfits key={identity} {...props} />} />
      <Route path="/outfits/new" element={<OutfitEditor key={identity} {...props} />} />
      <Route path="/outfits/:outfitId" element={<OutfitDetail key={identity} {...props} />} />
      {account.role === "USER" && <Route path="/access" element={<Access key={identity} {...props} />} />}
      {account.role === "USER" && <Route path="/preferences" element={<Preferences key={identity} {...props} />} />}
      {account.role === "USER" && <>
        <Route path="/capsules" element={<Capsules key={identity} actor={account} pending={pending} />} />
        <Route path="/capsules/new" element={<CapsuleEditor key={identity} {...props} />} />
        <Route path="/capsules/ai" element={<AiCapsuleEditor key={identity} {...props} />} />
        <Route path="/capsules/:capsuleId" element={<CapsuleDetail key={identity} {...props} />} />
        <Route path="/calendar" element={<Calendar key={identity} actor={account} pending={pending} />} />
        <Route path="/calendar/new" element={<CalendarEditor key={identity} {...props} />} />
        <Route path="/calendar/:entryId" element={<CalendarEditor key={identity} {...props} />} />
        <Route path="/requests" element={<StylistRequests key={identity} {...props} />} />
        <Route path="/requests/new" element={<StylistRequestEditor key={identity} {...props} />} />
        <Route path="/requests/:requestId" element={<StylistRequestDetail key={identity} {...props} />} />
      </>}
      {account.role === "STYLIST" && <>
        <Route path="/clients" element={<Clients {...props} />} />
        <Route path="/clients/:ownerId/*" element={<OwnerWorkspace key={identity} kind="client" {...props} />} />
      </>}
      {account.role === "ADMIN" && <>
        <Route path="/accounts" element={<Accounts {...props} />} />
        <Route path="/accounts/new" element={<AccountCreator key={identity} {...props} />} />
        <Route path="/accounts/:ownerId/*" element={<OwnerWorkspace key={identity} kind="admin" {...props} />} />
      </>}
      <Route path="*" element={<Navigate to={landing(account)} replace />} />
    </Routes>
  </SiteShell>;
}

function Workspace({ account, onLogout, loggingOut, logoutError }: {
  account: Actor; onLogout: () => void; loggingOut: boolean; logoutError: boolean;
}) {
  const [client] = React.useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  }));
  const [pending, setPending] = React.useState(false);
  const [recovering, setRecovering] = React.useState(false);
  const recoveryNeeded = React.useSyncExternalStore(subscribeSession, needsSessionRecovery);
  async function recover() {
    if (pending || loggingOut || recovering) return;
    const context = getSessionContext();
    setRecovering(true);
    try {
      await refreshSession(true);
      if (getSessionContext() === context) await client.invalidateQueries();
    } catch { /* The auth store keeps the visible recovery state until a confirmed result. */ }
    finally { setRecovering(false); }
  }
  React.useEffect(() => () => client.clear(), [client]);
  React.useEffect(() => {
    if (!pending && !loggingOut && !recovering) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pending, loggingOut, recovering]);
  return <QueryClientProvider client={client}>
    {logoutError && <div role="alert" className={`${styles.error} ${styles.authBanner}`}>
      <p>Не удалось завершить сессию. Повторите выход.</p>
      <button disabled={pending || loggingOut || recovering} onClick={() => { if (!pending && !loggingOut && !recovering) onLogout(); }}>Повторить выход</button>
    </div>}
    {recoveryNeeded && <div role="alert" className={`${styles.error} ${styles.authBanner}`}>
      <p>Не удалось подтвердить вход. Повторите восстановление.</p>
      <button disabled={pending || loggingOut || recovering} onClick={() => void recover()}>{recovering ? "Восстанавливаем вход…" : "Восстановить вход"}</button>
    </div>}
    <AppRoutes account={account} onLogout={onLogout} onPending={setPending} pending={pending || loggingOut || recovering} />
  </QueryClientProvider>;
}

export function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const current = React.useSyncExternalStore(subscribeSession, getSession);
  const [restoring, setRestoring] = React.useState(!current);
  const [restoreError, setRestoreError] = React.useState(false);
  const [logoutError, setLogoutError] = React.useState(false);
  const [loggingOut, setLoggingOut] = React.useState(false);
  React.useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  React.useEffect(() => {
    if (getSession()) return;
    let active = true;
    void refreshSession().catch((error: unknown) => {
      if (active) setRestoreError(!(error instanceof ApiError && error.status === 401));
    }).finally(() => { if (active) setRestoring(false); });
    return () => { active = false; };
  }, []);
  async function restore() {
    setRestoring(true); setRestoreError(false);
    try { await refreshSession(true); }
    catch (error) { setRestoreError(!(error instanceof ApiError && error.status === 401)); }
    finally { setRestoring(false); }
  }
  async function exit() {
    if (loggingOut) return;
    setLoggingOut(true); setLogoutError(false);
    try { await logout(); navigate("/", { replace: true }); }
    catch { setLogoutError(true); }
    finally { setLoggingOut(false); }
  }
  return <>
    {restoreError && !current && <div role="alert" className={styles.error}>
      <p>Не удалось восстановить вход. Повторите загрузку или войдите заново.</p>
      <button disabled={restoring} onClick={() => void restore()}>Повторить загрузку</button>
    </div>}
    {current ? <Workspace key={getSessionContext()} account={current.actor} onLogout={() => void exit()} loggingOut={loggingOut} logoutError={logoutError} />
      : restoring ? <main className={styles.pick}><p role="status">Восстанавливаем вход…</p></main>
        : <Login disabled={loggingOut} onLogin={(actor) => {
          setRestoreError(false); setLogoutError(false);
          navigate(location.pathname === "/" || location.pathname === "/app" ? landing(actor) : location.pathname, { replace: true });
        }} />}
    <a className={styles.skipLink} href="#main">К основному содержимому</a>
  </>;
}
