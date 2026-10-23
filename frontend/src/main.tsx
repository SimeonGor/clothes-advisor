import React from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { AccountPicker, SiteShell, Today } from "./site";
import { Wardrobe, WardrobeEditor } from "./wardrobe";
import { OutfitDetail, OutfitEditor, Outfits } from "./outfits";
import styles from "./site.module.css";
import type { Actor } from "./api";

function AppRoutes({ account, onChoose, onSwitch, onPending, pending }: {
  account: Actor | null;
  onChoose: (actor: Actor) => void;
  onSwitch: () => void;
  onPending: (pending: boolean) => void;
  pending: boolean;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  function choose(actor: Actor, path: string) {
    onChoose(actor);
    navigate(path === "/today" && actor.role !== "USER" ? "/wardrobe" : path);
  }
  return (
    <Routes>
        <Route path="/" element={<Navigate to="/today" replace />} />
        <Route path="/today" element={account
          ? account.role === "USER"
            ? <Today account={account} onSwitch={onSwitch} onPending={onPending} pending={pending} />
            : <Navigate to="/wardrobe" replace />
          : <AccountPicker onChoose={(picked) => choose(picked, "/today")} />} />
        <Route path="/wardrobe" element={account
          ? <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="wardrobe">
              <Wardrobe actor={account} pending={pending} />
            </SiteShell>
          : <AccountPicker onChoose={(picked) => choose(picked, "/wardrobe")} />} />
        <Route path="/wardrobe/new" element={account
          ? <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="wardrobe">
              <WardrobeEditor key={`${account.id}:${location.pathname}`} actor={account} onPending={onPending} pending={pending} />
            </SiteShell>
          : <AccountPicker onChoose={(picked) => choose(picked, "/wardrobe/new")} />} />
        <Route path="/wardrobe/:itemId" element={account
          ? <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="wardrobe">
              <WardrobeEditor key={`${account.id}:${location.pathname}`} actor={account} onPending={onPending} pending={pending} />
            </SiteShell>
          : <AccountPicker onChoose={(picked) => choose(picked, location.pathname)} />} />
        <Route path="/outfits" element={account
          ? <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="outfits"><Outfits actor={account} pending={pending} /></SiteShell>
          : <AccountPicker onChoose={(picked) => choose(picked, "/outfits")} />} />
        <Route path="/outfits/new" element={account
          ? <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="outfits"><OutfitEditor key={`${account.id}:${location.pathname}`} actor={account} onPending={onPending} pending={pending} /></SiteShell>
          : <AccountPicker onChoose={(picked) => choose(picked, "/outfits/new")} />} />
        <Route path="/outfits/:outfitId" element={account
          ? <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="outfits"><OutfitDetail key={`${account.id}:${location.pathname}`} actor={account} onPending={onPending} pending={pending} /></SiteShell>
          : <AccountPicker onChoose={(picked) => choose(picked, location.pathname)} />} />
        <Route path="*" element={<Navigate to="/wardrobe" replace />} />
      </Routes>
  );
}

function App() {
  const [account, setAccount] = React.useState<Actor | null>(null);
  const [client, setClient] = React.useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  }));
  const [pending, setPending] = React.useState(false);

  React.useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pending]);

  function leaveAccount() {
    if (pending) return;
    client.clear();
    setClient(new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } }));
    setAccount(null);
  }

  return (
    <QueryClientProvider client={client}>
      <BrowserRouter basename="/app">
        <AppRoutes
          account={account}
          onChoose={(actor) => setAccount(actor)}
          onSwitch={leaveAccount}
          onPending={setPending}
          pending={pending}
        />
      </BrowserRouter>
      <a className={styles.skipLink} href="#main">К основному содержимому</a>
    </QueryClientProvider>
  );
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
