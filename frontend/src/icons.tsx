import React from "react";

const paths: Record<string, React.ReactNode> = {
  today: <path d="M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0"/>,
  wardrobe: <path d="M6 3h12v18H6zM12 3v18M9 10v3m6-3v3"/>,
  outfits: <><path d="m8 3 4 2 4-2 4 3-2 5-2-1v11H8V10l-2 1-2-5 4-3Z"/></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 10h18"/></>,
  capsules: <path d="m12 3 9 5-9 5-9-5 9-5Zm-9 5v10l9 4 9-4V8M12 13v9"/>,
  more: <><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></>,
  preferences: <path d="M4 7h16M4 17h16M8 4v6m8 4v6"/>,
  access: <path d="M16 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-3c0-5 16-5 16 0v3"/>,
  requests: <><path d="M4 4h16v13H9l-5 4V4Z"/><path d="M8 9h8m-8 4h5"/></>,
  clients: <><circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2m2-14a3 3 0 0 1 0 6m1 2a5 5 0 0 1 3 5v1"/></>,
  accounts: <><circle cx="12" cy="8" r="3"/><path d="M5 20v-2a7 7 0 0 1 14 0v2Z"/></>,
};

export function Icon({ name }: { name: string }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">{paths[name] ?? paths.more}</svg>;
}
