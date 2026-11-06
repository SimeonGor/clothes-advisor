import React from "react";
import { Link } from "react-router-dom";
import type { Actor, WorkspaceContext } from "./api";
import styles from "./site.module.css";
import { Dialog } from "./dialog";
import { Icon } from "./icons";

type Active = "today" | "wardrobe" | "outfits" | "capsules" | "access" | "clients" | "accounts" | "preferences" | "calendar" | "requests";
type Entry = { key: Active; path: string; label: string };
const shared: Entry[] = [{ key: "wardrobe", path: "/wardrobe", label: "Гардероб" }, { key: "outfits", path: "/outfits", label: "Образы" }];
const user: Entry[] = [
  { key: "today", path: "/today", label: "Сегодня" }, shared[0], shared[1],
  { key: "calendar", path: "/calendar", label: "Календарь" }, { key: "capsules", path: "/capsules", label: "Капсулы" },
  { key: "access", path: "/access", label: "Мой стилист" }, { key: "requests", path: "/requests", label: "Запросы стилисту" },
  { key: "preferences", path: "/preferences", label: "Предпочтения" },
];

export function SiteShell({ account, onLogout, pending, active, children }: {
  account: Actor;
  onLogout: () => void;
  pending: boolean;
  active: Active;
  context?: WorkspaceContext;
  children: React.ReactNode;
}) {
  const [moreOpen, setMoreOpen] = React.useState(false);
  const links: Entry[] = account.role === "USER" ? user : account.role === "STYLIST"
    ? [{ key: "clients", path: "/clients", label: "Клиенты" }, ...shared]
    : [{ key: "accounts", path: "/accounts", label: "Аккаунты" }, ...shared];
  const mobilePrimary = account.role === "USER"
    ? links.filter((entry) => ["today", "wardrobe", "outfits", "calendar"].includes(entry.key))
    : links;
  const landing = account.role === "USER" ? "/today" : account.role === "STYLIST" ? "/clients" : "/accounts";
  const mobileMore = account.role === "USER" ? links.filter((entry) => !mobilePrimary.includes(entry)) : [];
  const guard = (event: React.MouseEvent<HTMLAnchorElement>) => { if (pending) event.preventDefault(); };
  const exitAccount = () => { setMoreOpen(false); onLogout(); };
  return <div className={styles.shell} id="main" onClickCapture={(event) => {
    if (pending && (event.target as HTMLElement).closest("a")) { event.preventDefault(); event.stopPropagation(); }
  }}>
    <aside className={styles.sidebar}>
      <Link className={styles.brand} to={landing} aria-disabled={pending} onClick={guard}><span className={styles.brandMark}>ca</span><span>clothes advisor</span></Link>
      <p className={styles.navLabel}>ВАШЕ ПРОСТРАНСТВО</p>
      <nav className={styles.navigation} aria-label="Основная навигация">
        {links.map((entry) => <Link key={entry.key} className={active === entry.key ? styles.activeNav : styles.navLink}
          to={entry.path} data-mobile={mobilePrimary.includes(entry) || undefined} aria-current={active === entry.key ? "page" : undefined} aria-disabled={pending} onClick={guard}>
          <Icon name={entry.key}/><span>{entry.label}</span>
        </Link>)}
        <button type="button" className={`${styles.navLink} ${styles.moreButton}`} disabled={pending} onClick={() => setMoreOpen(true)}>
          <Icon name="more"/><span>Ещё</span>
        </button>
      </nav>
      <div className={styles.sidebarBottom}><p className={styles.navLabel}>АККАУНТ</p><span className={styles.accountName}>{account.login}</span>
        <button className={styles.textButton} disabled={pending} onClick={exitAccount}>Выйти</button>
      </div>
    </aside>
    {children}
    <Dialog open={moreOpen} title="Ещё" onClose={() => setMoreOpen(false)}>
      <nav className={styles.dialogNav} aria-label="Дополнительная навигация">
        {mobileMore.map((entry) => <Link key={entry.key} className={styles.navLink} to={entry.path} aria-disabled={pending} onClick={(event) => { guard(event); if (!pending) setMoreOpen(false); }}><Icon name={entry.key}/><span>{entry.label}</span></Link>)}
        <button className={styles.dialogSwitch} type="button" disabled={pending} onClick={exitAccount}>Выйти · {account.login}</button>
      </nav>
    </Dialog>
  </div>;
}
