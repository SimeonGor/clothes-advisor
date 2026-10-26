import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { AccountPicker, SiteShell, Today } from "./site";
import { Wardrobe, WardrobeEditor } from "./wardrobe";
import { OutfitDetail, OutfitEditor, Outfits } from "./outfits";
import { Access, Accounts, Clients, OwnerWorkspace } from "./people";
import { Preferences } from "./preferences";
import { StylistRequestDetail, StylistRequestEditor, StylistRequests } from "./stylist-requests";
import type { Actor } from "./api";
import styles from "./site.module.css";

const landing = (actor: Actor) => actor.role === "USER" ? "/today" : actor.role === "STYLIST" ? "/clients" : "/accounts";

export function AppRoutes({ account, onChoose, onSwitch, onPending, pending }: {
  account: Actor | null;
  onChoose: (actor: Actor) => void;
  onSwitch: () => void;
  onPending: (pending: boolean) => void;
  pending: boolean;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  if (!account) return <AccountPicker onChoose={(actor) => {
    onChoose(actor);
    navigate(location.pathname === "/" ? landing(actor) : location.pathname, { replace: true });
  }} />;
  function switchAccount() {
    if (pending) return;
    onSwitch();
    navigate("/", { replace: true });
  }
  const path = location.pathname;
  const identity = `${account.id}:${path}`;
  const props = { actor: account, onPending, pending };
  const active = path.startsWith("/clients") ? "clients" : path.startsWith("/accounts") ? "accounts"
    : path.startsWith("/preferences") ? "preferences" : path.startsWith("/access") ? "access"
      : path.startsWith("/requests") ? "requests" : path.startsWith("/outfits") ? "outfits" : "wardrobe";
  if (path === "/today" && account.role === "USER") {
    return <Today key={identity} account={account} onSwitch={switchAccount} onPending={onPending} pending={pending} />;
  }
  return <SiteShell account={account} onSwitch={switchAccount} pending={pending} active={active}>
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
        <Route path="/accounts/:ownerId/*" element={<OwnerWorkspace key={identity} kind="admin" {...props} />} />
      </>}
      <Route path="*" element={<Navigate to={landing(account)} replace />} />
    </Routes>
  </SiteShell>;
}

export function App() {
  const [account, setAccount] = React.useState<Actor | null>(null);
  const [client, setClient] = React.useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  }));
  const [pending, setPending] = React.useState(false);
  React.useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pending]);
  function leaveAccount() {
    if (pending) return;
    client.clear();
    setClient(new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } }));
    setAccount(null);
  }
  return <QueryClientProvider client={client}>
    <AppRoutes account={account} onChoose={setAccount} onSwitch={leaveAccount} onPending={setPending} pending={pending} />
    <a className={styles.skipLink} href="#main">К основному содержимому</a>
  </QueryClientProvider>;
}
