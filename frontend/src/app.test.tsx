import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./app";

const accounts = [
  { id: 22, login: "stylist", role: "STYLIST", status: "ACTIVE" },
  { id: 33, login: "admin", role: "ADMIN", status: "ACTIVE" },
];
const ok = (data: unknown) => ({ ok: true, status: 200, headers: new Headers(), json: async () => data } as Response);

afterEach(() => {
  cleanup(); vi.unstubAllGlobals();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
});

describe("App account switching", () => {
  it("keeps the active workspace on cancel and clears account query state on confirmation", async () => {
    const calls: Array<{ url: string; userId: string | null }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, userId: new Headers(init?.headers).get("X-User-Id") });
      if (url.startsWith("/api/admin/users?")) return ok(accounts);
      if (url === "/api/admin/users/22" || url === "/api/admin/users/33") return ok(accounts.find((row) => url.endsWith(String(row.id))));
      if (url.startsWith("/api/stylist/clients?")) return ok([]);
      return ok([]);
    }));
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
    Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });

    render(<MemoryRouter initialEntries={["/app"]}><App /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: /stylist/ }));
    await screen.findByRole("heading", { name: "Клиенты" });

    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));
    const dialog = screen.getByRole("dialog", { name: "Выберите аккаунт" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Закрыть" }));
    expect(await screen.findByRole("heading", { name: "Клиенты" })).toBeTruthy();
    expect(screen.getAllByText("stylist").length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "Выберите аккаунт" })).getByRole("button", { name: /admin/ }));
    await screen.findByRole("heading", { name: "Аккаунты" });
    expect(await screen.findByText("Администратор")).toBeTruthy();
    await waitFor(() => expect(calls.filter((call) => call.url.startsWith("/api/admin/users?")).length).toBeGreaterThanOrEqual(2));
    expect(calls.some((call) => call.url.startsWith("/api/stylist/clients?") && call.userId === "22")).toBe(true);
  });

  it("ignores a delayed account validation after the switch dialog is closed", async () => {
    let resolveAdmin!: (response: Response) => void;
    let clientsReads = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/admin/users?")) return ok(accounts);
      if (url === "/api/admin/users/22") return ok(accounts[0]);
      if (url === "/api/admin/users/33") return new Promise<Response>((resolve) => { resolveAdmin = resolve; });
      if (url.startsWith("/api/stylist/clients?")) { clientsReads += 1; return ok([]); }
      return ok([]);
    }));
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
    Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });

    render(<MemoryRouter initialEntries={["/app"]}><App /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "stylist Стилист" }));
    await screen.findByRole("heading", { name: "Клиенты" });
    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));
    const dialog = screen.getByRole("dialog", { name: "Выберите аккаунт" });
    fireEvent.click(within(dialog).getByRole("button", { name: "admin Администратор" }));
    await waitFor(() => expect(resolveAdmin).toBeDefined());

    fireEvent.click(within(dialog).getByRole("button", { name: "Закрыть" }));
    resolveAdmin(ok(accounts[1]));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Клиенты" })).toBeTruthy());
    expect(screen.getByText("stylist")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Аккаунты" })).toBeNull();
    expect(clientsReads).toBe(1);
  });
});
