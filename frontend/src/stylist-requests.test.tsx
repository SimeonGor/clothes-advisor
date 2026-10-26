import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Actor, StylistRequest } from "./api";
import { AppRoutes } from "./app";
import { StylistRequestDetail, StylistRequestEditor, StylistRequests } from "./stylist-requests";

const user: Actor = { id: 11, login: "user", role: "USER" };
const row = (extra: Partial<StylistRequest> = {}): StylistRequest => ({
  id: 5, ownerId: 11, stylistId: 22, wishes: "Пожелания", weather: { temperatureC: -2.5, windSpeedMps: 1.25, precipitationTypeId: 7 },
  status: "PENDING", version: 3, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-01T00:00:00Z", ...extra,
});
const ok = (data: unknown, status = 200) => ({ ok: true, status, headers: new Headers(), json: async () => data } as Response);
const fail = (status: number) => ({ ok: false, status, headers: new Headers(), json: async () => ({ error: "conflict" }) } as Response);

function mount(path: string, seed?: Array<[readonly unknown[], unknown]>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  seed?.forEach(([key, data]) => client.setQueryData(key, data));
  const onPending = vi.fn();
  const props = { actor: user, pending: false, onPending };
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/requests" element={<StylistRequests {...props} />} />
    <Route path="/requests/new" element={<StylistRequestEditor {...props} />} />
    <Route path="/requests/:requestId" element={<StylistRequestDetail {...props} />} />
    <Route path="/outfits/:outfitId" element={<h1>Образ</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { ...view, client, onPending };
}

beforeEach(() => { sessionStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Запросы стилисту владельца", () => {
  it("запоздавшее чтение не возвращает прежнее состояние после отмены", async () => {
    // given
    let reads = 0;
    let finishRead!: (value: Response) => void;
    let finishCancel!: (value: Response) => void;
    vi.stubGlobal("confirm", vi.fn(() => true));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/cancellation")) return new Promise<Response>((resolve) => { finishCancel = resolve; });
      if (path === "/api/me/stylist-requests/5") return ++reads === 1 ? ok(row())
        : new Promise<Response>((resolve) => { finishRead = resolve; });
      return ok([]);
    }));
    const { client } = mount("/requests/5");
    fireEvent.click(await screen.findByRole("button", { name: "Отменить запрос" }));
    await waitFor(() => expect(finishCancel).toBeDefined());

    // when
    let read!: Promise<void>;
    act(() => { read = client.invalidateQueries({ queryKey: ["stylist-request", user.id, 5] }); });
    await waitFor(() => expect(finishRead).toBeDefined());
    await act(async () => { finishCancel(ok(row({ status: "CANCELLED", version: 4 }))); });
    await screen.findByText(/Отменён · версия 4/);
    await act(async () => { finishRead(ok(row())); await read; });

    // then
    expect(screen.getByText(/Отменён · версия 4/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Отменить запрос" })).toBeNull();
    expect(client.getQueryData<StylistRequest>(["stylist-request", user.id, 5])?.version).toBe(4);
  });

  it("во время отправки блокирует переходы и смену аккаунта в приложении", async () => {
    // given
    let finish!: (value: Response) => void;
    const switched = vi.fn();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (init?.method === "POST") return new Promise<Response>((resolve) => { finish = resolve; });
      if (path.startsWith("/api/me/stylist-access")) return ok([{ id: 22, login: "stylist" }]);
      if (path.startsWith("/api/weather/precipitation-types")) return ok([{ id: 7, name: "Ясно" }]);
      return fail(404);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    function Harness() {
      const [pending, setPending] = React.useState(false);
      return <AppRoutes account={user} pending={pending} onPending={setPending} onChoose={vi.fn()} onSwitch={switched} />;
    }
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/requests/new"]}><Harness /></MemoryRouter></QueryClientProvider>);
    await screen.findByRole("option", { name: "stylist · №22" });
    fireEvent.change(screen.getByLabelText("СТИЛИСТ"), { target: { value: "22" } });
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "7" } });

    // when
    fireEvent.click(screen.getByRole("button", { name: "Отправить запрос" }));
    await waitFor(() => expect(finish).toBeDefined());
    fireEvent.click(screen.getByRole("link", { name: "← К запросам" }));
    fireEvent.click(screen.getByRole("link", { name: "Предпочтения ↗" }));
    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));

    // then
    expect(screen.getByRole("heading", { name: "Новый запрос" })).toBeTruthy();
    expect(switched).not.toHaveBeenCalled();
    expect((screen.getByRole("button", { name: "Отправить запрос" }) as HTMLButtonElement).disabled).toBe(true);
    await act(async () => { finish(fail(503)); });
    await screen.findByRole("alert");
    expect((screen.getByRole("button", { name: "Сменить аккаунт" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it.each(["STYLIST", "ADMIN"])("не открывает собственные запросы для %s", async (role) => {
    // given
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => ok([]));
    vi.stubGlobal("fetch", fetchMock);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    // when
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/requests/new"]}>
      <AppRoutes account={{ ...user, role }} pending={false} onPending={vi.fn()} onChoose={vi.fn()} onSwitch={vi.fn()} />
    </MemoryRouter></QueryClientProvider>);

    // then
    await screen.findByRole("heading", { name: role === "STYLIST" ? "Клиенты" : "Аккаунты" });
    expect(screen.queryByRole("link", { name: "Запросы стилисту ↗" })).toBeNull();
    expect(fetchMock.mock.calls.some((call) => String(call[0]).startsWith("/api/me/stylist-requests"))).toBe(false);
  });

  it("создаёт запрос выбранному стилисту с нормализованными пожеланиями и погодой", async () => {
    // given
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, init });
      if (url === "/api/me/stylist-access?page=0&size=50") return ok([{ id: 22, login: "stylist" }]);
      if (url.startsWith("/api/weather/precipitation-types")) return ok([{ id: 7, name: "Ясно", code: "CLEAR" }]);
      if (url === "/api/me/stylist-requests" && init?.method === "POST") return ok(row({ id: 91 }), 201);
      if (url === "/api/me/stylist-requests/91") return ok(row({ id: 91 }));
      return fail(404);
    }));
    mount("/requests/new");
    const stylist = await screen.findByLabelText("СТИЛИСТ") as HTMLSelectElement;
    await waitFor(() => expect(stylist.disabled).toBe(false));
    fireEvent.change(stylist, { target: { value: "22" } });
    fireEvent.change(screen.getByLabelText("ТЕМПЕРАТУРА °C"), { target: { value: "-2.5" } });
    fireEvent.change(screen.getByLabelText("ВЕТЕР м/с"), { target: { value: "1.25" } });
    const precipitation = await screen.findByLabelText("ОСАДКИ") as HTMLSelectElement;
    await waitFor(() => expect(precipitation.disabled).toBe(false));
    fireEvent.change(precipitation, { target: { value: "7" } });
    fireEvent.change(screen.getByLabelText("ПОЖЕЛАНИЯ (НЕОБЯЗАТЕЛЬНО)"), { target: { value: "  Нужен шарф  " } });

    // when
    const submit = screen.getByRole("button", { name: "Отправить запрос" }) as HTMLButtonElement;
    await waitFor(() => expect(submit.disabled).toBe(false));
    fireEvent.click(submit);

    // then
    expect(await screen.findByRole("heading", { name: "Запрос №91" })).toBeTruthy();
    const post = calls.find((call) => call.url === "/api/me/stylist-requests" && call.init?.method === "POST");
    expect(JSON.parse(String(post?.init?.body))).toEqual({ stylistId: 22, weather: { temperatureC: -2.5, windSpeedMps: 1.25, precipitationTypeId: 7 }, wishes: "Нужен шарф" });
    expect(new Headers(post?.init?.headers).get("Idempotency-Key")).toBeTruthy();
  });

  it("сохраняет черновик и ключ после неизвестного результата, блокируя двойную отправку", async () => {
    // given
    let posts = 0;
    const keys: Array<string | null> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith("/api/me/stylist-access")) return ok([{ id: 22, login: "stylist" }]);
      if (url.startsWith("/api/weather/precipitation-types")) return ok([{ id: 7, name: "Ясно", code: "CLEAR" }]);
      if (url === "/api/me/stylist-requests" && init?.method === "POST") {
        posts += 1;
        keys.push(new Headers(init.headers).get("Idempotency-Key"));
        if (posts === 1) throw new TypeError("network unavailable");
        return ok(row({ id: 92 }), 201);
      }
      if (url === "/api/me/stylist-requests/92") return ok(row({ id: 92 }));
      return fail(404);
    }));
    mount("/requests/new");
    const stylist = await screen.findByLabelText("СТИЛИСТ");
    await waitFor(() => expect((stylist as HTMLSelectElement).disabled).toBe(false));
    fireEvent.change(stylist, { target: { value: "22" } });
    fireEvent.change(await screen.findByLabelText("ОСАДКИ"), { target: { value: "7" } });
    fireEvent.change(screen.getByLabelText("ПОЖЕЛАНИЯ (НЕОБЯЗАТЕЛЬНО)"), { target: { value: "Нужен шарф" } });

    // when
    const submit = screen.getByRole("button", { name: "Отправить запрос" });
    fireEvent.click(submit);
    fireEvent.click(submit);
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((screen.getByLabelText("ПОЖЕЛАНИЯ (НЕОБЯЗАТЕЛЬНО)") as HTMLTextAreaElement).value).toBe("Нужен шарф");
    fireEvent.click(submit);

    // then
    expect(await screen.findByRole("heading", { name: "Запрос №92" })).toBeTruthy();
    expect(posts).toBe(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it("не принимает ошибку загрузки доступов за пустой список", async () => {
    // given
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).startsWith("/api/me/stylist-access") ? fail(503) : ok([]));
    vi.stubGlobal("fetch", fetchMock);
    mount("/requests/new");

    // when
    expect(await screen.findByRole("alert")).toBeTruthy();

    // then
    expect(screen.queryByText("Нет доступных стилистов")).toBeNull();
    expect(screen.queryByRole("link", { name: "Открыть доступы ↗" })).toBeNull();
    expect((screen.getByRole("button", { name: "Отправить запрос" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("показывает страницу списка и ответ простым текстом без интерпретации разметки", async () => {
    // given
    const explanation = "<strong>Пальто</strong> и **шарф**";
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/me/stylist-requests?page=0&size=12") return ok([row({ status: "ANSWERED", answer: { outfitId: 77, explanation, createdAt: "2026-01-02T00:00:00Z" } })]);
      if (url === "/api/me/stylist-requests/5") return ok(row({ status: "ANSWERED", answer: { outfitId: 77, explanation, createdAt: "2026-01-02T00:00:00Z" } }));
      if (url.startsWith("/api/weather/precipitation-types")) return ok([{ id: 7, name: "Ясно", code: "CLEAR" }]);
      return fail(404);
    }));
    mount("/requests");

    // when
    fireEvent.click(await screen.findByRole("link", { name: /Запрос №5/ }));

    // then
    const answer = (await screen.findByRole("heading", { name: "Ответ стилиста" })).closest("section")!;
    expect(answer.textContent).toContain(explanation);
    expect(answer.querySelector("strong")).toBeNull();
    expect(screen.getByRole("link", { name: "Открыть образ №77 ↗" }).getAttribute("href")).toBe("/outfits/77");
    expect(screen.queryByRole("button", { name: "Отменить запрос" })).toBeNull();
  });

  it("после конфликта блокирует повторную отмену до перечитывания и отправляет актуальную версию", async () => {
    // given
    let reads = 0;
    const writes: Array<{ url: string; body: unknown }> = [];
    vi.stubGlobal("confirm", vi.fn(() => true));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/me/stylist-requests/5" && !init?.method) return ok(row({ version: ++reads === 1 ? 3 : 8 }));
      if (url.endsWith("/cancellation")) {
        writes.push({ url, body: JSON.parse(String(init?.body)) as unknown });
        return writes.length === 1 ? fail(409) : ok(row({ status: "CANCELLED", version: 9 }));
      }
      if (url.startsWith("/api/weather/precipitation-types")) return ok([]);
      return fail(404);
    }));
    mount("/requests/5");

    // when
    fireEvent.click(await screen.findByRole("button", { name: "Отменить запрос" }));
    expect(await screen.findByRole("button", { name: "Перечитать запрос" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Отменить запрос" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Перечитать запрос" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Отменить запрос" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Отменить запрос" }));

    // then
    await screen.findByText(/Отменён/);
    expect(writes.map((write) => write.body)).toEqual([{ version: 3 }, { version: 8 }]);
    expect(screen.queryByRole("button", { name: "Отменить запрос" })).toBeNull();
  });

  it("скрывает кэшированный список при ошибке и позволяет вернуться с пустой ошибочной страницы", async () => {
    // given
    const firstPage = Array.from({ length: 12 }, (_, index) => row({ id: index + 1 }));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("page=0&size=12")) return ok(firstPage);
      if (url.endsWith("page=1&size=12")) return fail(503);
      return ok([]);
    }));
    const { client } = mount("/requests", [[ ["stylist-requests", user.id, 1], [row({ id: 99 })] ]]);

    // when
    expect(await screen.findByRole("link", { name: /^Запрос №1 · стилист/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));

    // then
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("link", { name: /Запрос №99/ })).toBeNull();
    expect(screen.getByRole("button", { name: "← Назад" })).toBeTruthy();
    client.clear();
  });

  it("не показывает отозванный доступ после ошибки обновления списка стилистов", async () => {
    // given
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/me/stylist-access")) return fail(503);
      if (url.startsWith("/api/weather/precipitation-types")) return ok([{ id: 7, name: "Ясно", code: "CLEAR" }]);
      return fail(404);
    }));
    mount("/requests/new", [[["stylist-access", user.id], [{ id: 22, login: "stylist" }]]]);

    // when
    await screen.findByRole("alert");

    // then
    expect(screen.queryByRole("option", { name: /stylist/ })).toBeNull();
    expect((screen.getByRole("button", { name: "Отправить запрос" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("после ошибки перечитывания скрывает устаревшую деталь и отмену", async () => {
    // given
    let detailReads = 0;
    vi.stubGlobal("confirm", vi.fn(() => true));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/me/stylist-requests/5" && !init?.method) return ++detailReads === 1 ? ok(row()) : fail(503);
      if (url.endsWith("/cancellation")) return fail(409);
      return ok([]);
    }));
    mount("/requests/5");

    // when
    fireEvent.click(await screen.findByRole("button", { name: "Отменить запрос" }));
    fireEvent.click(await screen.findByRole("button", { name: "Перечитать запрос" }));

    // then
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Запрос №5" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Отменить запрос" })).toBeNull();
    expect(screen.getByRole("link", { name: "← К запросам" })).toBeTruthy();
  });
});
