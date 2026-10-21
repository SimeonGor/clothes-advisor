import React from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AccountPicker, Today } from "./site";
import styles from "./site.module.css";

function App() {
  const [account, setAccount] = React.useState<{
    id: number;
    login: string;
    role: string;
  } | null>(null);
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
        <Routes>
          <Route path="/" element={<Navigate to="/today" replace />} />
          <Route
            path="/today"
            element={account
              ? <Today
                  account={account}
                  onSwitch={leaveAccount}
                  onPending={setPending}
                  pending={pending}
                />
              : <AccountPicker onChoose={setAccount} />}
          />
          <Route path="*" element={<Navigate to="/today" replace />} />
        </Routes>
      </BrowserRouter>
      <a className={styles.skipLink} href="#main">К основному содержимому</a>
    </QueryClientProvider>
  );
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
