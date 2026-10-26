import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRoutes } from "./app";
import type { Actor, CalendarEntry, Outfit } from "./api";

const user: Actor = { id: 11, login: "owner", role: "USER" };
const stylist: Actor = { id: 22, login: "stylist", role: "STYLIST" };
const admin: Actor = { id: 33, login: "admin", role: "ADMIN" };
const outfit: Outfit = { id: 7, ownerId: 11, authorId: 11, source: "USER", name: "Синий комплект", itemIds: [], likes: 0, dislikes: 0, createdAt: "", weather: { temperatureC: 14, windSpeedMps: 1, precipitationTypeId: 1 } };
const row = (id: number, date: string, version = 1, status: CalendarEntry["status"] = "PLANNED"): CalendarEntry => ({
  id, ownerId: 11, date, outfitId: 7, status, version, createdAt: "", modifiedAt: "",
});
const ok = (data: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(data), { status, headers: { "X-Total-Count": "1" } });
const fail = (status: number, body: unknown = null) => new Response(body === null ? null : JSON.stringify(body), { status });
type Call = { url: string; init?: RequestInit };
const clients: QueryClient[] = [];

function mount(actor = user, path = "/calendar") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } });
  clients.push(client);
  let navigate!: ReturnType<typeof useNavigate>;
  function Harness() {
    navigate = useNavigate();
    const [pending, setPending] = React.useState(false);
    return <AppRoutes account={actor} onChoose={vi.fn()} onSwitch={vi.fn()} onPending={setPending} pending={pending} />;
  }
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Harness /></MemoryRouter></QueryClientProvider>);
  return { client, go: (next: string) => act(() => navigate(next)) };
}
function fixture(handler: (call: Call) => Response | Promise<Response>) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), init }; calls.push(call); return handler(call);
  }));
  return calls;
}
const key = (call: Call) => new Headers(call.init?.headers).get("Idempotency-Key");

beforeEach(() => vi.stubGlobal("fetch", vi.fn()));
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); vi.unstubAllGlobals(); });

describe("Календарь пользователя", () => {
  it("показывает записи по датам и использует локальный месяц без UTC-сдвига", async () => {
    // given
    const calls = fixture(({ url }) => url.startsWith("/api/me/calendar?")
      ? ok([row(1, "2026-03-01"), row(2, "2026-03-01", 1, "WORN"), row(3, "2026-03-31")]) : fail(404));
    mount();

    // when
    await screen.findAllByRole("link", { name: "Образ №7" });
    fireEvent.change(screen.getByLabelText("Месяц"), { target: { value: "2026-03" } });
    await waitFor(() => expect(calls.some((call) => call.url === "/api/me/calendar?from=2026-03-01&to=2026-03-31&page=0&size=12")).toBe(true));
    await screen.findAllByText("Запланирован ·");

    // then
    expect(screen.getAllByRole("link", { name: "Образ №7" })).toHaveLength(3);
    expect(screen.getAllByRole("link", { name: "Изменить" })).toHaveLength(3);
    expect(screen.getAllByText("Запланирован ·")[0].textContent).toContain("Образ №7");
  });

  it.each([stylist, admin])("прячет пункт и перенаправляет чужую роль с /calendar без calendar API: $role", async (actor) => {
    // given
    const calls = fixture(() => fail(404));

    // when
    mount(actor, "/calendar/new");

    // then
    await screen.findByRole("heading", { name: actor.role === "STYLIST" ? "Клиенты" : "Аккаунты" });
    expect(screen.queryByRole("link", { name: /Календарь/ })).toBeNull();
    expect(calls.some((call) => call.url.includes("/api/me/calendar"))).toBe(false);
    expect(calls.some((call) => call.url.includes("/api/outfits"))).toBe(false);
  });

  it("повторяет создание с тем же ключом и блокирует навигацию на время запроса", async () => {
    // given
    let attempt = 0;
    let finish!: (response: Response) => void;
    const calls = fixture(({ url, init }) => {
      if (url.startsWith("/api/outfits?")) return ok([outfit]);
      if (url.startsWith("/api/me/calendar") && init?.method === "POST") {
        attempt += 1;
        if (attempt === 1) return fail(503);
        return new Promise<Response>((resolve) => { finish = resolve; });
      }
      if (url.startsWith("/api/me/calendar?")) return ok([]);
      return fail(404);
    });
    mount(user, "/calendar/new");
    await screen.findByRole("option", { name: /Синий комплект/ });
    fireEvent.change(screen.getByLabelText("Дата"), { target: { value: "2026-02-28" } });
    fireEvent.change(screen.getByLabelText("Образ"), { target: { value: "7" } });

    // when
    fireEvent.click(screen.getByRole("button", { name: "Добавить в календарь" }));
    await screen.findByRole("alert");
    const first = calls.find((call) => call.init?.method === "POST")!;
    fireEvent.click(screen.getByRole("button", { name: "Добавить в календарь" }));
    await waitFor(() => expect(calls.filter((call) => call.init?.method === "POST")).toHaveLength(2));
    fireEvent.click(screen.getByRole("link", { name: "Гардероб ↗" }));

    // then
    const writes = calls.filter((call) => call.init?.method === "POST");
    expect(screen.getByRole("heading", { name: "Добавить образ" })).toBeTruthy();
    expect(key(writes[1])).toBe(key(first));
    expect(JSON.parse(String(writes[1].init?.body))).toEqual({ date: "2026-02-28", outfitId: 7, status: "PLANNED" });
    await act(async () => finish(ok(row(9, "2026-02-28"), 201)));
    await screen.findByRole("heading", { name: "Календарь" });
  });

  it("обновляет только явным reload после конфликта и удаляет по версии после inline confirmation", async () => {
    // given
    const calls = fixture(({ url, init }) => {
      if (url.startsWith("/api/outfits?")) return ok([outfit]);
      if (url === "/api/me/calendar/9" && (init?.method === undefined || init.method === "GET")) return ok(row(9, "2026-02-28", 4));
      if (url === "/api/me/calendar/9" && init?.method === "PUT") return fail(409);
      if (url === "/api/me/calendar/9?version=4" && init?.method === "DELETE") return ok(null, 204);
      if (url.startsWith("/api/me/calendar?")) return ok([]);
      return fail(404);
    });
    mount(user, "/calendar/9");
    await screen.findByDisplayValue("2026-02-28");
    fireEvent.change(screen.getByLabelText("Дата"), { target: { value: "2026-03-01" } });

    // when
    fireEvent.click(screen.getByRole("button", { name: "Сохранить изменения" }));
    await screen.findByRole("button", { name: "Обновить данные" });
    expect(calls.filter((call) => call.init?.method === "PUT")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    await screen.findByText(/Черновик заменён/);
    fireEvent.click(screen.getByRole("button", { name: "Удалить запись" }));
    expect(calls.filter((call) => call.init?.method === "DELETE")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить удаление" }));

    // then
    await screen.findByRole("heading", { name: "Календарь" });
    const put = calls.find((call) => call.init?.method === "PUT")!;
    expect(JSON.parse(String(put.init?.body))).toEqual({ version: 4, date: "2026-03-01", outfitId: 7, status: "PLANNED" });
    expect(calls.some((call) => call.url === "/api/me/calendar/9?version=4" && call.init?.method === "DELETE")).toBe(true);
  });

  it("не принимает версию из успешного фонового refetch вместо версии черновика", async () => {
    // given
    let reads = 0;
    const calls = fixture(({ url, init }) => {
      if (url.startsWith("/api/outfits?")) return ok([outfit]);
      if (url === "/api/me/calendar/9" && init?.method === undefined) return ok(row(9, reads++ ? "2026-03-02" : "2026-02-28", reads > 1 ? 5 : 4));
      if (url === "/api/me/calendar/9" && init?.method === "PUT") return ok(row(9, "2026-03-01", 6));
      if (url.startsWith("/api/me/calendar?")) return ok([]);
      return fail(404);
    });
    const { client } = mount(user, "/calendar/9");
    await screen.findByDisplayValue("2026-02-28");

    // when
    await act(async () => { await client.refetchQueries({ queryKey: ["calendar-entry", user.id, 9], exact: true }); });
    fireEvent.change(screen.getByLabelText("Дата"), { target: { value: "2026-03-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Сохранить изменения" }));
    await screen.findByRole("heading", { name: "Календарь" });

    // then
    const put = calls.find((call) => call.init?.method === "PUT")!;
    expect(JSON.parse(String(put.init?.body))).toEqual({ version: 4, date: "2026-03-01", outfitId: 7, status: "PLANNED" });
  });

  it("сохраняет черновик и скрывает старую запись после неудачной явной перезагрузки", async () => {
    // given
    let reads = 0;
    const calls = fixture(({ url, init }) => {
      if (url.startsWith("/api/outfits?")) return ok([outfit]);
      if (url === "/api/me/calendar/9" && init?.method === undefined) return ++reads === 1 ? ok(row(9, "2026-02-28", 4)) : fail(503);
      if (url === "/api/me/calendar/9" && init?.method === "PUT") return fail(409);
      return fail(404);
    });
    mount(user, "/calendar/9");
    await screen.findByDisplayValue("2026-02-28");
    fireEvent.change(screen.getByLabelText("Дата"), { target: { value: "2026-03-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Сохранить изменения" }));
    await screen.findByRole("button", { name: "Обновить данные" });

    // when
    expect(screen.getByText(/Несохранённые изменения будут заменены сохранённой записью/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));

    // then
    await screen.findByRole("alert");
    expect(screen.queryByLabelText("Дата")).toBeNull();
    expect(screen.queryByRole("button", { name: "Удалить запись" })).toBeNull();
    expect(screen.getByText(/При обновлении данных несохранённые изменения будут заменены сохранённой записью/)).toBeTruthy();
    expect(calls.filter((call) => call.init?.method === "PUT")).toHaveLength(1);
  });

  it("оставляет возврат на предыдущую страницу при ошибке загрузки страницы", async () => {
    // given
    const calls = fixture(({ url }) => {
      if (url.includes("page=1")) return fail(503);
      if (url.startsWith("/api/me/calendar?")) return ok(Array.from({ length: 12 }, (_, index) => row(index + 1, "2026-03-01")));
      return fail(404);
    });
    mount();
    await screen.findByRole("button", { name: "Далее →" });

    // when
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));

    // then
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "← Назад" })).toBeTruthy();
    expect(screen.queryByText("Планов на этот месяц пока нет")).toBeNull();
    expect(calls.some((call) => call.url.includes("page=1"))).toBe(true);
  });

  it("не запрашивает календарь для некорректного ID или очищенного месяца", async () => {
    // given
    const invalidCalls = fixture(() => fail(404));
    mount(user, "/calendar/not-a-number");

    // when / then
    await screen.findByText("Некорректный номер записи календаря.");
    expect(invalidCalls.some((call) => call.url.includes("/api/me/calendar/"))).toBe(false);

    cleanup();
    const monthCalls = fixture(({ url }) => url.startsWith("/api/me/calendar?") ? ok([]) : fail(404));
    mount();
    await screen.findByRole("heading", { name: "Календарь" });
    const beforeClear = monthCalls.filter((call) => call.url.startsWith("/api/me/calendar?")).length;
    fireEvent.change(screen.getByLabelText("Месяц"), { target: { value: "" } });
    expect(monthCalls.filter((call) => call.url.startsWith("/api/me/calendar?")).length).toBe(beforeClear);
  });

  it("игнорирует запоздавший detail GET после сохранения новой версии", async () => {
    // given
    let reads = 0;
    let finishOldRead!: (response: Response) => void;
    let finishSave!: (response: Response) => void;
    const calls = fixture(({ url, init }) => {
      if (url.startsWith("/api/outfits?")) return ok([outfit]);
      if (url === "/api/me/calendar/9" && init?.method === undefined) {
        reads += 1;
        return reads === 1 ? ok(row(9, "2026-02-28", 4)) : new Promise<Response>((resolve) => { finishOldRead = resolve; });
      }
      if (url === "/api/me/calendar/9" && init?.method === "PUT") return new Promise<Response>((resolve) => { finishSave = resolve; });
      if (url.startsWith("/api/me/calendar?")) return ok([]);
      return fail(404);
    });
    const { client } = mount(user, "/calendar/9");
    await screen.findByDisplayValue("2026-02-28");
    // when: a read starts during a pending save and resolves after the save.
    fireEvent.change(screen.getByLabelText("Дата"), { target: { value: "2026-03-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Сохранить изменения" }));
    await waitFor(() => expect(finishSave).toBeTypeOf("function"));
    let staleRead!: Promise<void>;
    await act(async () => {
      staleRead = client.refetchQueries({ queryKey: ["calendar-entry", user.id, 9], exact: true });
      await waitFor(() => expect(finishOldRead).toBeTypeOf("function"));
    });
    await act(async () => finishSave(ok(row(9, "2026-03-01", 5))));
    await screen.findByRole("heading", { name: "Календарь" });
    await act(async () => { finishOldRead(ok(row(9, "2026-02-28", 4))); await staleRead; });

    // then
    expect(client.getQueryData<CalendarEntry>(["calendar-entry", user.id, 9])?.version).toBe(5);
    expect(calls.filter((call) => call.init?.method === "PUT")).toHaveLength(1);
  });
});
