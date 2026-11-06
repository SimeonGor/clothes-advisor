import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { App } from "./app";
import { Login } from "./login";
import { getSession } from "./auth";
import { authenticatedActor } from "./auth-test-fixture";
import { request } from "./api";

const user = { id: 11, login: "user", role: "USER" };
const stylist = { id: 22, login: "stylist", role: "STYLIST" };
const json = (data: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(data), { status });
const token = () => json({ accessToken: "access", tokenType: "Bearer", expiresIn: 900 });
const mount = () => render(<MemoryRouter><App /></MemoryRouter>);
async function signIn() {
  fireEvent.change(await screen.findByLabelText("Логин"), { target: { value: " user " } });
  fireEvent.change(screen.getByLabelText("Пароль"), { target: { value: " password " } });
  fireEvent.click(screen.getByRole("button", { name: "Войти" }));
}
beforeEach(() => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
  Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });
});
afterEach(() => {
  cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal"); Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
});

it.each([
  { actor: user, path: "/accounts/new", landing: "Сегодня" },
  { actor: user, path: "/accounts/33", landing: "Сегодня" },
  { actor: stylist, path: "/accounts/new", landing: "Клиенты" },
  { actor: stylist, path: "/accounts/33", landing: "Клиенты" },
])("does not expose account forms or call admin endpoints for $actor.role at $path", async ({ actor, path, landing }) => {
  // given: a non-admin session opens a direct creation or password-reset address.
  authenticatedActor(actor);
  const fetch = vi.fn(async () => json([]));
  vi.stubGlobal("fetch", fetch);
  // when
  render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole("link", { name: landing }).getAttribute("aria-current")).toBe("page"));
  // then: route gating redirects without fetching administrative data.
  expect(screen.queryByRole("link", { name: "Создать аккаунт ↗" })).toBeNull();
  expect(screen.queryByLabelText("ПАРОЛЬ")).toBeNull();
  expect(screen.queryByLabelText("НОВЫЙ ПАРОЛЬ")).toBeNull();
  expect(screen.queryByRole("button", { name: "Сохранить роль и статус" })).toBeNull();
  expect(vi.mocked(globalThis.fetch).mock.calls.some(([url]) => String(url).startsWith("/api/admin/"))).toBe(false);
});

it("restores the cookie session on reload and resets scroll only for navigation", async () => {
  // given
  const fetch = vi.fn(async (path: string) => path === "/api/auth/refresh" ? token()
    : path === "/api/auth/me" ? json(user) : json([]));
  vi.stubGlobal("fetch", fetch);
  // when
  mount();
  await waitFor(() => expect(screen.getByRole("link", { name: "Сегодня" }).getAttribute("aria-current")).toBe("page"));
  const scroll = vi.mocked(window.scrollTo); scroll.mockClear();
  fireEvent.click(screen.getByRole("link", { name: "Гардероб" }));
  await waitFor(() => expect(scroll).toHaveBeenCalledWith(0, 0)); scroll.mockClear();
  fireEvent.click(screen.getByRole("button", { name: /Ещё/ }));
  fireEvent.click(within(screen.getByRole("dialog", { name: "Ещё" })).getByRole("button", { name: "Закрыть" }));
  // then
  expect(scroll).not.toHaveBeenCalled();
  expect(fetch.mock.calls.filter(([path]) => path === "/api/auth/refresh")).toHaveLength(1);
  expect(fetch.mock.calls.some(([path]) => path.startsWith("/api/admin/users"))).toBe(false);
});

it.each([401, 403, 503])("shows login errors without loading an anonymous directory: %s", async (status) => {
  // given
  const fetch = vi.fn(async (path: string) => json({}, path === "/api/auth/refresh" ? 401 : status));
  vi.stubGlobal("fetch", fetch); mount();
  // when
  await signIn();
  // then
  await screen.findByRole("alert");
  expect(getSession()).toBeNull();
  expect((screen.getByLabelText("Пароль") as HTMLInputElement).value).toBe("");
  expect(fetch.mock.calls.map(([path]) => path)).toEqual(["/api/auth/refresh", "/api/auth/login"]);
  const init = vi.mocked(globalThis.fetch).mock.calls[1][1];
  expect(JSON.parse(String(init?.body))).toEqual({ login: " user ", password: " password " });
});

it("permits explicit restore retry after an unavailable response", async () => {
  // given
  let unavailable = true;
  const fetch = vi.fn(async (path: string) => path === "/api/auth/refresh" ? unavailable ? json({}, 503) : token()
    : path === "/api/auth/me" ? json(stylist) : json([]));
  vi.stubGlobal("fetch", fetch); mount();
  await screen.findByRole("alert");
  expect(fetch).toHaveBeenCalledTimes(1);
  // when
  unavailable = false; fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
  // then
  await screen.findByRole("heading", { name: "Клиенты" });
  expect(fetch.mock.calls.filter(([path]) => path === "/api/auth/refresh")).toHaveLength(2);
});

it("keeps the workspace on failed logout and clears its cache after confirmed logout", async () => {
  // given
  authenticatedActor(stylist); let failing = true; let reads = 0;
  const fetch = vi.fn(async (path: string) => {
    if (path === "/api/auth/logout") return json(null, failing ? 503 : 204);
    if (path === "/api/auth/login") return token();
    if (path === "/api/auth/me") return json(stylist);
    if (path.startsWith("/api/stylist/clients")) { reads++; return json([{ id: 11, login: reads === 1 ? "Первый клиент" : "Свежий клиент" }]); }
    return json([]);
  });
  vi.stubGlobal("fetch", fetch); mount(); await screen.findByText("Первый клиент");
  // when
  fireEvent.click(screen.getByRole("button", { name: "Выйти" }));
  await screen.findByRole("alert");
  expect(getSession()?.actor).toEqual(stylist); expect(screen.getByText("Первый клиент")).toBeTruthy();
  failing = false; fireEvent.click(screen.getByRole("button", { name: "Повторить выход" }));
  await screen.findByRole("heading", { name: "Вход" }); expect(getSession()).toBeNull();
  await signIn();
  // then
  await screen.findByText("Свежий клиент");
  expect(screen.queryByText("Первый клиент")).toBeNull(); expect(reads).toBe(2);
  expect(fetch.mock.calls.filter(([path]) => path === "/api/auth/logout")).toHaveLength(2);
});

it.each([503, "network"])("recovers an existing session only on explicit request after refresh %s", async (failure) => {
  // given: the access token expired, and refreshing it has an unknown outcome.
  authenticatedActor(stylist);
  let unavailable = true;
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === "/api/auth/refresh") {
      if (unavailable && failure === "network") throw new TypeError("offline");
      return unavailable ? json({}, 503) : token();
    }
    if (path === "/api/auth/me") return json(stylist);
    return new Headers(init?.headers).get("Authorization") === "Bearer access"
      ? json([{ id: 11, login: "Восстановленный клиент" }]) : json({}, 401);
  });
  vi.stubGlobal("fetch", fetch); mount();
  await screen.findByRole("button", { name: "Восстановить вход" });
  await screen.findByRole("button", { name: "Повторить загрузку" });
  await act(async () => {
    await expect(request("/api/outfits", stylist.id, { method: "POST", body: "{}" })).rejects.toThrow();
  });
  const refreshCount = () => fetch.mock.calls.filter(([path]) => path === "/api/auth/refresh").length;
  expect(refreshCount()).toBe(1);
  // when: a resource retry alone does not retry ambiguous refresh; recovery is explicit.
  unavailable = false;
  const reads = fetch.mock.calls.length;
  fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
  await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(reads));
  expect(refreshCount()).toBe(1);
  fireEvent.click(screen.getByRole("button", { name: "Восстановить вход" }));
  // then: reads resume, while the original mutation is never replayed.
  await screen.findByText("Восстановленный клиент");
  expect(screen.queryByRole("button", { name: "Восстановить вход" })).toBeNull();
  expect(refreshCount()).toBe(2);
  expect(fetch.mock.calls.filter(([path]) => path === "/api/outfits")).toHaveLength(1);
});

it("blocks session recovery during logout and blocks logout while recovery is pending", async () => {
  // given
  authenticatedActor(stylist);
  let finishLogout!: (response: Response) => void;
  let finishRefresh!: (response: Response) => void;
  let refreshes = 0;
  vi.stubGlobal("fetch", vi.fn(async (path: string) => {
    if (path === "/api/auth/refresh") {
      refreshes++;
      return refreshes === 1 ? json({}, 503) : new Promise<Response>((resolve) => { finishRefresh = resolve; });
    }
    if (path === "/api/auth/logout") return new Promise<Response>((resolve) => { finishLogout = resolve; });
    if (path === "/api/auth/me") return json(stylist);
    return json({}, 401);
  }));
  mount();
  await screen.findByRole("button", { name: "Восстановить вход" });
  // when
  fireEvent.click(screen.getByRole("button", { name: "Выйти" }));
  await waitFor(() => expect(finishLogout).toBeDefined());
  const recover = screen.getByRole("button", { name: "Восстановить вход" }) as HTMLButtonElement;
  expect(recover.disabled).toBe(true); fireEvent.click(recover);
  expect(refreshes).toBe(1);
  await act(async () => finishLogout(json({}, 503)));
  fireEvent.click(screen.getByRole("button", { name: "Восстановить вход" }));
  await waitFor(() => expect(finishRefresh).toBeDefined());
  // then
  expect((screen.getByRole("button", { name: "Выйти" }) as HTMLButtonElement).disabled).toBe(true);
  expect((screen.getByRole("button", { name: "Повторить выход" }) as HTMLButtonElement).disabled).toBe(true);
  await act(async () => finishRefresh(json({}, 503)));
  expect((screen.getByRole("button", { name: "Восстановить вход" }) as HTMLButtonElement).disabled).toBe(false);
});

it("does not install login credentials after the form unmounts", async () => {
  // given
  let finish!: (value: Response) => void;
  const fetch = vi.fn(async (path: string) => path === "/api/auth/login" ? token()
    : new Promise<Response>((resolve) => { finish = resolve; }));
  vi.stubGlobal("fetch", fetch);
  const chosen = vi.fn(); const view = render(<Login onLogin={chosen} />);
  await signIn(); await waitFor(() => expect(finish).toBeDefined());
  // when
  view.unmount(); await act(async () => finish(json(user)));
  // then
  expect(chosen).not.toHaveBeenCalled(); expect(getSession()).toBeNull();
});
