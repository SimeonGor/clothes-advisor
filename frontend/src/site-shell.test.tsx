import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Actor } from "./api";
import { SiteShell } from "./site";

const user: Actor = { id: 1, login: "user-one", role: "USER" };
const stylist: Actor = { id: 2, login: "stylist-one", role: "STYLIST" };
const admin: Actor = { id: 3, login: "admin-one", role: "ADMIN" };

afterEach(() => {
  cleanup(); vi.restoreAllMocks();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
});

function renderShell(account = user, pending = false, onSwitch = vi.fn()) {
  return render(<MemoryRouter><SiteShell account={account} onSwitch={onSwitch} pending={pending} active="wardrobe">
    <main id="main"><a href="/external">Child action</a></main>
  </SiteShell></MemoryRouter>);
}

describe("SiteShell", () => {
  it("shows the right primary routes for each role", () => {
    const { rerender } = renderShell();
    expect(screen.getByRole("link", { name: "Сегодня" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Гардероб" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Мой стилист" }).getAttribute("href")).toBe("/access");
    rerender(<MemoryRouter><SiteShell account={stylist} onSwitch={vi.fn()} pending={false} active="clients"><main /></SiteShell></MemoryRouter>);
    expect(screen.getByRole("link", { name: "Клиенты" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Сегодня" })).toBeNull();
    rerender(<MemoryRouter><SiteShell account={admin} onSwitch={vi.fn()} pending={false} active="accounts"><main /></SiteShell></MemoryRouter>);
    expect(screen.getByRole("link", { name: "Аккаунты" })).toBeTruthy();
  });

  it("blocks navigation and the More control while an action is pending", () => {
    renderShell(user, true);
    const wardrobe = screen.getByRole("link", { name: "Гардероб" });
    expect(wardrobe.getAttribute("aria-disabled")).toBe("true");
    const click = fireEvent.click(wardrobe);
    expect(click).toBe(false);
    expect((screen.getByRole("button", { name: /Ещё/ }) as HTMLButtonElement).disabled).toBe(true);
    const child = screen.getByRole("link", { name: "Child action" });
    expect(fireEvent.click(child)).toBe(false);
  });

  it("opens More, follows a remaining route, and invokes account switching", () => {
    const onSwitch = vi.fn();
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
    Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });
    renderShell(user, false, onSwitch);
    fireEvent.click(screen.getByRole("button", { name: /Ещё/ }));
    const dialog = screen.getByRole("dialog", { name: "Ещё" });
    fireEvent.click(within(dialog).getByRole("link", { name: "Предпочтения" }));
    expect(dialog.hasAttribute("open")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: /Ещё/ }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "Ещё" })).getByRole("button", { name: /Сменить аккаунт/ }));
    expect(onSwitch).toHaveBeenCalledOnce();
  });
});
