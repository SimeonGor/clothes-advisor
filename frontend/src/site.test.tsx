import { authenticatedActor } from "./auth-test-fixture";
import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor, act } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Account, Outfit, Photo, WardrobeItem } from "./api";
import { Today } from "./site";
import { App } from "./app";

const accounts: Account[] = [
  { id: 1, login: "гардероб-один", role: "USER", status: "ACTIVE" },
  { id: 2, login: "гардероб-два", role: "ADMIN", status: "ACTIVE" },
  { id: 3, login: "гардероб-три", role: "USER", status: "ACTIVE" },
];
const wardrobeItem = (id: number): WardrobeItem => ({
  id,
  name: `Вещь ${id}`,
  categoryId: 1,
  color: "оливковый",
  material: "хлопок",
  version: 1,
  createdAt: "2026-01-01T00:00:00Z",
  modifiedAt: "2026-01-01T00:00:00Z",
});
const photo = (id: number): Photo => ({
  id: id + 100, itemId: id, contentType: "image/jpeg", sizeBytes: 3,
  createdAt: "2026-01-01T00:00:00Z",
});
const originalCreateObjectUrl = URL.createObjectURL;
const originalRevokeObjectUrl = URL.revokeObjectURL;

function json(data: unknown, headers: Record<string, string> = {}) {
  return {
    ok: true,
    status: 200,
    headers: new Headers(headers),
    json: async () => data,
  } as Response;
}

function failed(status: number) {
  return { ok: false, status, headers: new Headers(), json: async () => ({}) } as Response;
}

function mountToday(actor = accounts[0], pendingInitially = false) {
  authenticatedActor(actor);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function App() {
    const [pending, setPending] = React.useState(pendingInitially);
    return <Today account={actor} onLogout={vi.fn()} onPending={setPending} pending={pending} />;
  }
  const view = render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);
  return { ...view, client };
}

async function readyToSubmit() {
  const button = await screen.findByRole("button", { name: "Подобрать образ на сегодня" });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  return button;
}

async function openCandidates() {
  fireEvent.click(screen.getByRole("button", { name: /Выбрать вещи/ }));
  await screen.findByRole("dialog", { name: "Выбрать вещи" });
}

async function readyPrecipitationSelect() {
  const select = screen.getByLabelText("ОСАДКИ") as HTMLSelectElement;
  await waitFor(() => expect(select.disabled).toBe(false));
  return select;
}

function apiFetch(
  wardrobe: WardrobeItem[],
  create: (body: Record<string, unknown>) => Promise<Response> = async () => json({
    id: 91,
    name: "Новое утро",
    itemIds: [],
    weather: {},
  }),
  readItem: (id: number) => Promise<Response> = async (id) => json(wardrobe.find((entry) => entry.id === id) ?? wardrobeItem(id)),
) {
  let savedOutfit: Outfit | undefined;
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.startsWith("/api/v2/me/ai-jobs?page=")) return json([], { "X-Total-Count": "0" });
    if (url === "/api/v2/outfits/ai") {
      const response = await create(JSON.parse(String(init?.body)) as Record<string, unknown>);
      if (response.status >= 400) return response;
      savedOutfit = await response.json() as Outfit;
      return {
        ok: true, status: 202,
        headers: new Headers({ Location: "/api/v2/me/ai-jobs/550e8400-e29b-41d4-a716-446655440000" }),
        json: async () => ({ jobId: "550e8400-e29b-41d4-a716-446655440000", status: "QUEUED" }),
      } as Response;
    }
    if (url === "/api/v2/me/ai-jobs/550e8400-e29b-41d4-a716-446655440000") {
      return json({ jobId: "550e8400-e29b-41d4-a716-446655440000", type: "OUTFIT_SELECTION", status: "SUCCEEDED",
        result: { type: "OUTFIT", id: savedOutfit?.id ?? 42, url: "https://untrusted.invalid/ignored" }, error: null });
    }
    if (url.startsWith("/api/outfits/") && savedOutfit && Number(url.split("/").pop()) === savedOutfit.id) return json(savedOutfit);
    if (url.startsWith("/api/weather/precipitation-types")) {
      return json([{ id: 7, name: "Ясно", code: "CLEAR" }]);
    }
    if (url.startsWith("/api/wardrobe/categories")) {
      return json([{ id: 1, name: "Верхняя одежда" }]);
    }
    if (url.startsWith("/api/wardrobe/items?page=")) {
      const page = Number(new URL(url, "http://local").searchParams.get("page"));
      return json(wardrobe.slice(page * 12, (page + 1) * 12));
    }
    if (url.startsWith("/api/wardrobe/items/") && url.endsWith("/photos")) {
      return json([photo(Number(url.split("/")[4]))]);
    }
    if (/^\/api\/wardrobe\/items\/\d+$/.test(url)) {
      const id = Number(url.split("/").pop());
      return readItem(id);
    }
    return failed(404);
  });
}

beforeEach(() => {
  window.history.replaceState({}, "", "/app/today");
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
  Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
  vi.unstubAllGlobals();
  if (originalCreateObjectUrl) {
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: originalCreateObjectUrl });
  } else {
    Reflect.deleteProperty(URL, "createObjectURL");
  }
  if (originalRevokeObjectUrl) {
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: originalRevokeObjectUrl });
  } else {
    Reflect.deleteProperty(URL, "revokeObjectURL");
  }
});

describe("Экран «Сегодня»", () => {
  it("сериализует дробную погоду, опускает пустой список кандидатов и показывает результат", async () => {
    const fetchMock = apiFetch([]);
    vi.stubGlobal("fetch", fetchMock);
    mountToday();

    // given: погода задана дробными значениями, а выбор вещей пуст.

    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "  Ясное утро  " } });
    fireEvent.change(screen.getByLabelText(/ТЕМПЕРАТУРА/), { target: { value: "-2.5" } });
    fireEvent.change(screen.getByLabelText(/ВЕТЕР/), { target: { value: "1.75" } });
    fireEvent.change(screen.getByLabelText("ПОВОД (НЕОБЯЗАТЕЛЬНО)"), { target: { value: "  " } });
    fireEvent.change(screen.getByLabelText("ЖЕЛАЕМЫЙ СТИЛЬ (НЕОБЯЗАТЕЛЬНО)"), { target: { value: "  " } });
    const precipitation = await readyPrecipitationSelect();
    fireEvent.change(precipitation, { target: { value: "7" } });
    expect(precipitation.value).toBe("7");
    expect(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА").closest("form")?.checkValidity()).toBe(true);

    // when: пользователь создаёт образ.

    fireEvent.click(await readyToSubmit());

    // then: запрос сохраняет числа и не передаёт пустой список кандидатов.

    await screen.findByText(/ОБРАЗ СОХРАНЁН/);
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/v2/outfits/ai");
    const body = JSON.parse(String(sent?.[1]?.body));
    expect(body).toEqual({
      name: "Ясное утро",
      weather: { temperatureC: -2.5, windSpeedMps: 1.75, precipitationTypeId: 7 },
    });
    expect(new Headers(sent?.[1]?.headers).get("Idempotency-Key")).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith("/api/v2/me/ai-jobs/"))).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => String(url) === "/api/outfits/91")).toBe(true);
    expect(screen.getByText("Новое утро")).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Почему этот образ" })).toBeNull();
  });

  it("восстанавливает задание текущего пользователя после перезагрузки без нового POST", async () => {
    sessionStorage.setItem("ai-job:1:outfit:new", JSON.stringify({ actorId: 1, operation: "outfit", jobId: "550e8400-e29b-41d4-a716-446655440000", key: "saved-key" }));
    const baseFetch = apiFetch([wardrobeItem(1)]);
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push(`${init?.method ?? "GET"} ${url}`);
      if (url === "/api/v2/me/ai-jobs/550e8400-e29b-41d4-a716-446655440000") {
        return json({ jobId: "550e8400-e29b-41d4-a716-446655440000", type: "OUTFIT_SELECTION", status: "RUNNING", result: null, error: null });
      }
      return baseFetch(input, init);
    }));

    mountToday();

    expect(await screen.findByRole("status")).toBeTruthy();
    expect(calls.some((call) => call.includes("/api/v2/me/ai-jobs/"))).toBe(true);
    expect(calls.some((call) => call.includes("POST /api/v2/outfits/ai"))).toBe(false);
    expect(sessionStorage.getItem("ai-job:1:outfit:new")).toContain("saved-key");
  });

  it("пагинирует историю по 50 и открывает завершённую капсулу без POST", async () => {
    const calls: Array<{ url: string; method: string }> = [];
    const firstPage = Array.from({ length: 50 }, (_, index) => ({
      jobId: `job-${index}`, type: "OUTFIT_SELECTION", status: "FAILED", createdAt: "2026-01-01T00:00:00Z",
      modifiedAt: "2026-01-01T00:01:00Z", result: null, error: { code: "selection_unavailable", message: "Нет результата" },
    }));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); const method = init?.method ?? "GET"; calls.push({ url, method });
      if (url === "/api/v2/me/ai-jobs?page=0&size=50") return json(firstPage, { "X-Total-Count": "51" });
      if (url === "/api/v2/me/ai-jobs?page=1&size=50") return json([{
        jobId: "completed-capsule", type: "CAPSULE_SELECTION", status: "SUCCEEDED", createdAt: "2026-10-01T00:00:00Z",
        modifiedAt: "2026-10-01T00:01:00Z", result: { type: "CAPSULE", id: 77, url: "https://untrusted.invalid/ignored" }, error: null,
      }], { "X-Total-Count": "51" });
      if (url === "/api/v2/me/ai-jobs/completed-capsule") return json({
        jobId: "completed-capsule", type: "CAPSULE_SELECTION", status: "SUCCEEDED", createdAt: "2026-10-01T00:00:00Z",
        modifiedAt: "2026-10-01T00:01:00Z", result: { type: "CAPSULE", id: 77, url: "https://untrusted.invalid/ignored" }, error: null,
      });
      return failed(404);
    }));
    mountToday();

    expect(await screen.findByRole("button", { name: "Далее →" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    const completedRow = await screen.findByRole("button", { name: /AI-капсула.*Готово/ });
    fireEvent.click(completedRow);

    const resultLink = await screen.findByRole("link", { name: "Открыть сохранённый результат ↗" });
    expect(resultLink.getAttribute("href")).toBe("/capsules/77");
    expect(calls.filter((call) => call.url.startsWith("/api/v2/me/ai-jobs?page=")).map((call) => call.url)).toEqual([
      "/api/v2/me/ai-jobs?page=0&size=50", "/api/v2/me/ai-jobs?page=1&size=50",
    ]);
    expect(calls.some((call) => call.url.includes("untrusted.invalid"))).toBe(false);
    expect(calls.some((call) => call.method === "POST")).toBe(false);
  });

  it("advances history row status from selected detail without reverting another row", async () => {
    const jobs = ["job-a", "job-b"].map((jobId) => ({
      jobId, type: "OUTFIT_SELECTION", status: "QUEUED", createdAt: "2026-11-01T00:00:00Z",
      modifiedAt: "2026-11-01T00:00:00Z", result: null, error: null,
    }));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v2/me/ai-jobs?page=0&size=50") return json(jobs, { "X-Total-Count": "2" });
      if (url === "/api/v2/me/ai-jobs/job-a") return json({
        ...jobs[0], status: "SUCCEEDED", modifiedAt: "2026-11-01T00:01:00Z",
        result: { type: "OUTFIT", id: 71, url: "/api/outfits/71" },
      });
      if (url === "/api/v2/me/ai-jobs/job-b") return json({
        ...jobs[1], status: "RUNNING", modifiedAt: "2026-11-01T00:01:00Z",
      });
      return failed(404);
    }));
    mountToday();

    const queuedRows = await screen.findAllByRole("button", { name: /Подбор образа.*В очереди/ });
    fireEvent.click(queuedRows[0]);
    await screen.findByRole("heading", { name: "Готово" });
    expect(await screen.findByRole("button", { name: /Подбор образа.*Готово/ })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Подбор образа.*В очереди/ }));
    await screen.findByRole("heading", { name: "Выполняется" });
    expect(screen.getByRole("button", { name: /Подбор образа.*Готово/ })).toBeTruthy();
    expect(await screen.findByRole("button", { name: /Подбор образа.*Выполняется/ })).toBeTruthy();
  });

  it("не показывает результат списка, пришедший после смены аккаунта", async () => {
    let resolveFirst!: (response: Response) => void;
    let firstRequested!: () => void;
    const firstRequest = new Promise<void>((resolve) => { firstRequested = resolve; });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith("/api/v2/me/ai-jobs?page=")) {
        if (new Headers(init?.headers).get("Authorization") === "Bearer test-token-1") {
          firstRequested();
          return new Promise<Response>((resolve) => { resolveFirst = resolve; });
        }
        return json([{ jobId: "new-user-job", type: "OUTFIT_SELECTION", status: "FAILED", createdAt: "2026-11-01T00:00:00Z",
          modifiedAt: "2026-11-01T00:01:00Z", result: null, error: { code: "selection_unavailable", message: "Нет результата" } }], { "X-Total-Count": "1" });
      }
      return failed(404);
    }));
    authenticatedActor(accounts[0]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const props = (account: Account) => <Today account={account} onLogout={vi.fn()} onPending={vi.fn()} pending={false} />;
    const view = render(<QueryClientProvider client={client}><MemoryRouter>{props(accounts[0])}</MemoryRouter></QueryClientProvider>);
    await firstRequest;
    authenticatedActor(accounts[2]);
    view.rerender(<QueryClientProvider client={client}><MemoryRouter>{props(accounts[2])}</MemoryRouter></QueryClientProvider>);

    expect(await screen.findByRole("button", { name: /Подбор образа.*Ошибка/ })).toBeTruthy();
    await act(async () => resolveFirst(json([{ jobId: "old-user-job", type: "OUTFIT_SELECTION", status: "FAILED", createdAt: "2026-01-01T00:00:00Z",
      modifiedAt: "2026-01-01T00:01:00Z", result: null, error: { code: "selection_unavailable", message: "Старый аккаунт" } }], { "X-Total-Count": "1" })));
    expect(screen.queryByText("Старый аккаунт")).toBeNull();
    expect(screen.queryByRole("button", { name: /old-user-job/ })).toBeNull();
  });

  it("сохраняет выбор между страницами и передаёт более 20 кандидатов без обрезки", async () => {
    const saved: Outfit = {
      id: 42, ownerId: 1, authorId: 1, source: "AI", likes: 0, dislikes: 0, createdAt: "2026-01-01T00:00:00Z",
      name: "Заметки о поле",
      itemIds: [1, 13],
      weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 7 },
    };
    const fetchMock = apiFetch(
      Array.from({ length: 24 }, (_, index) => wardrobeItem(index + 1)),
      async () => json(saved),
    );
    vi.stubGlobal("fetch", fetchMock);
    mountToday();

    // given: пользователь выбирает вещи на нескольких страницах гардероба.

    await openCandidates();
    await screen.findByLabelText("Выбрать: Вещь 1");
    for (let id = 1; id <= 12; id += 1) {
      fireEvent.click(screen.getByLabelText(`Выбрать: Вещь ${id}`));
    }
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    await screen.findByLabelText("Выбрать: Вещь 13");
    for (let id = 13; id <= 24; id += 1) {
      fireEvent.click(screen.getByLabelText(`Выбрать: Вещь ${id}`));
    }
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    await screen.findByText("В гардеробе пока нет вещей.");
    fireEvent.click(screen.getByRole("button", { name: "Готово" }));
    expect(screen.queryByText("В гардеробе пока нет вещей.")).toBeNull();
    expect(screen.getByText("Вещь 1")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "Заметки о поле" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });

    // when: он создаёт образ.

    fireEvent.click(await readyToSubmit());

    // then: все выбранные идентификаторы попадают в запрос.

    await screen.findByText(/ОБРАЗ СОХРАНЁН · №42/);
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/v2/outfits/ai");
    const body = JSON.parse(String(sent?.[1]?.body));
    expect(body.candidateItemIds).toEqual(Array.from({ length: 24 }, (_, index) => index + 1));
    expect(await screen.findByText("Сохранено вещей: 2.")).toBeTruthy();
  });

  it("не блокирует подбор после ошибки следующей страницы и закрытия выбора", async () => {
    const baseFetch = apiFetch(Array.from({ length: 12 }, (_, index) => wardrobeItem(index + 1)));
    vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) =>
      String(input).includes("items?page=1") ? Promise.resolve(failed(503)) : baseFetch(input, init));
    mountToday();
    await openCandidates();
    await screen.findByLabelText("Выбрать: Вещь 1");
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Готово" }));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText("Вещь 1")).toBeTruthy();
    expect((await readyToSubmit() as HTMLButtonElement).disabled).toBe(false);
  });

  it("показывает пустой выбор честно, очищает его и закрывает диалог отменой", async () => {
    vi.stubGlobal("fetch", apiFetch([wardrobeItem(1)]));
    mountToday();
    await openCandidates();
    const dialog = screen.getByRole("dialog", { name: "Выбрать вещи" });
    expect(screen.getByText("Выбор пуст — подойдут все доступные вещи.")).toBeTruthy();
    const item = await screen.findByLabelText("Выбрать: Вещь 1") as HTMLInputElement;
    fireEvent.click(item);
    expect(screen.getByText("Выбрано вещей: 1")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Весь гардероб" }));
    expect(screen.getByText("Выбор пуст — подойдут все доступные вещи.")).toBeTruthy();
    fireEvent(dialog, new Event("cancel", { bubbles: true, cancelable: true }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Выбрать вещи" })).toBeNull());
  });

  it("сохраняет последнее состояние выбранной вещи после смены страницы и блокирует устаревший выбор", async () => {
    const rows = Array.from({ length: 13 }, (_, index) => wardrobeItem(index + 1));
    const fetchMock = apiFetch(rows);
    vi.stubGlobal("fetch", fetchMock);
    const { client } = mountToday();
    await openCandidates();
    await screen.findByLabelText("Выбрать: Вещь 1");
    fireEvent.click(screen.getByLabelText("Выбрать: Вещь 1"));

    // given: выбранная вещь со страницы 1 обновляется до состояния «убрана».

    await act(async () => client.setQueryData(["items", 1, 0], [{ ...rows[0], availability: "STORED" }, ...rows.slice(1, 12)]));
    await waitFor(() => expect(screen.getAllByText("Убрана").length).toBeGreaterThan(0));
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    await screen.findByLabelText("Выбрать: Вещь 13");
    fireEvent.click(screen.getByRole("button", { name: "Готово" }));
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "Проверка выбора" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });

    // when: отправка с сохранённым выбором выполняется со страницы 2.

    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА").closest("form")!);

    // then: выбранный ID не исчезает молча и POST ждёт явного снятия выбора.

    expect((await screen.findByRole("alert")).textContent).toContain("Снимите выбор");
    expect(fetchMock.mock.calls.some(([url]) => url === "/api/v2/outfits/ai")).toBe(false);

    // when: пользователь возвращается к вещи, снимает её и отправляет пустой выбор.

    await openCandidates();
    fireEvent.click(screen.getByRole("button", { name: "← Назад" }));
    const unavailable = await screen.findByLabelText("Выбрать: Вещь 1") as HTMLInputElement;
    expect(unavailable.checked).toBe(true);
    expect(unavailable.disabled).toBe(false);
    fireEvent.click(unavailable);
    fireEvent.click(screen.getByRole("button", { name: "Готово" }));
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА").closest("form")!);

    // then: явное снятие возвращает прежнее поведение «весь гардероб».

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === "/api/v2/outfits/ai")).toBe(true));
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/v2/outfits/ai");
    expect(JSON.parse(String(sent?.[1]?.body))).not.toHaveProperty("candidateItemIds");
  });

  it("передаёт очищенный контекст, показывает сохранённые значения и сбрасывает поля", async () => {
    // given
    const hostileUrl = "https://untrusted.invalid/collect";
    const saved: Outfit = {
      id: 42, ownerId: 1, authorId: 1, source: "AI", likes: 0, dislikes: 0,
      createdAt: "2026-01-01T00:00:00Z", name: "Вечер", itemIds: [],
      weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 7 },
      occasion: "Сохранённый повод", preferredStyle: "Сохранённый стиль",
      explanation: [
        "Тёплый слой: <strong>пальто</strong> и **шарф**.",
        `<a href="${hostileUrl}">ссылка</a> <img src="${hostileUrl}">`,
        `[ссылка](${hostileUrl}) ![изображение](${hostileUrl})`,
        `<script>fetch("${hostileUrl}")</script> <iframe src="${hostileUrl}"></iframe>`,
        `<form action="${hostileUrl}"></form> Отправьте пароль на ${hostileUrl}.`,
      ].join(" "),
    };
    const fetchMock = apiFetch([], async () => json(saved));
    vi.stubGlobal("fetch", fetchMock);
    mountToday();
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "Вечер" } });
    const occasion = screen.getByLabelText("ПОВОД (НЕОБЯЗАТЕЛЬНО)") as HTMLInputElement;
    const style = screen.getByLabelText("ЖЕЛАЕМЫЙ СТИЛЬ (НЕОБЯЗАТЕЛЬНО)") as HTMLInputElement;
    fireEvent.change(occasion, { target: { value: "  Встреча  " } });
    fireEvent.change(style, { target: { value: "  Спокойный  " } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });

    // when
    fireEvent.click(await readyToSubmit());

    // then
    expect(await screen.findByText("Повод: Сохранённый повод")).toBeTruthy();
    expect(screen.getByText("Стиль: Сохранённый стиль")).toBeTruthy();
    const explanation = screen.getByRole("region", { name: "Почему этот образ" });
    expect(explanation.textContent).toContain(saved.explanation);
    expect(explanation.querySelector("a, img, script, iframe, form, strong")).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith(hostileUrl))).toBe(false);
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/v2/outfits/ai");
    expect(JSON.parse(String(sent?.[1]?.body))).toMatchObject({ occasion: "Встреча", preferredStyle: "Спокойный" });
    expect(occasion.disabled).toBe(true);
    expect(style.disabled).toBe(true);
    expect(occasion.maxLength).toBe(300);
    expect(style.maxLength).toBe(300);

    // when
    const reset = screen.getByRole("button", { name: "Составить ещё один образ" });
    await waitFor(() => expect((reset as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(reset);

    // then
    expect(occasion.value).toBe("");
    expect(style.value).toBe("");
    expect(occasion.disabled).toBe(false);
  });

  it("не дублирует POST, freezes его input после ошибки и требует подтверждения для нового body", async () => {
    let finish!: (response: Response) => void;
    let attempts = 0;
    const create = vi.fn((_body: Record<string, unknown>) => {
      attempts += 1;
      if (attempts === 1) return new Promise<Response>((resolve) => { finish = resolve; });
      return Promise.resolve(json({ id: 92, ownerId: 1, authorId: 1, source: "AI", likes: 0, dislikes: 0,
        createdAt: "2026-01-01T00:00:00Z", name: "Новый образ", itemIds: [], weather: {} }));
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const fetchMock = apiFetch([], create);
    vi.stubGlobal("fetch", fetchMock);
    mountToday();

    const name = screen.getByLabelText("НАЗВАНИЕ ОБРАЗА");
    fireEvent.change(name, { target: { value: "Оставить это" } });
    const occasion = screen.getByLabelText("ПОВОД (НЕОБЯЗАТЕЛЬНО)") as HTMLInputElement;
    const style = screen.getByLabelText("ЖЕЛАЕМЫЙ СТИЛЬ (НЕОБЯЗАТЕЛЬНО)") as HTMLInputElement;
    fireEvent.change(occasion, { target: { value: "  Работа  " } });
    fireEvent.change(style, { target: { value: "Минимализм" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });
    await readyToSubmit();

    // given: POST создания образа ещё не завершён.

    const form = name.closest("form")!;

    // when: форма отправлена повторно, затем сервер отвечает ошибкой.

    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));

    await act(async () => finish(failed(503)));

    // then: отправляется один запрос, а введённые данные остаются в форме.

    expect((await screen.findByRole("alert")).textContent).toContain("Сервис AI сейчас недоступен");
    expect((name as HTMLInputElement).value).toBe("Оставить это");
    expect(occasion.value).toBe("  Работа  ");
    expect(style.value).toBe("Минимализм");
    expect((name as HTMLInputElement).disabled).toBe(true);
    fireEvent.change(name, { target: { value: "Не менять" } });
    expect(create).toHaveBeenCalledTimes(1);

    fireEvent.click(await screen.findByRole("button", { name: "Новая попытка" }));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect((name as HTMLInputElement).disabled).toBe(false);
    fireEvent.change(name, { target: { value: "Новое имя" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });
    fireEvent.submit(form);
    await screen.findByText(/ОБРАЗ СОХРАНЁН/);
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[0][0].name).toBe("Оставить это");
    expect(create.mock.calls[1][0].name).toBe("Новое имя");
    const posts = fetchMock.mock.calls.filter(([url]) => url === "/api/v2/outfits/ai");
    expect(new Headers(posts[1][1]?.headers).get("Idempotency-Key")).not.toBe(new Headers(posts[0][1]?.headers).get("Idempotency-Key"));
  });

  it("входит заново и отзывает URL фото после выхода", async () => {

    const created = vi.fn(() => "blob:wardrobe-one");
    const revoked = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: created });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revoked });
    const firstItem = wardrobeItem(1);
    const secondItem = wardrobeItem(2);
    let finishLatePhoto!: (response: Response) => void;
    let active = accounts[0];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/auth/refresh") return failed(401);
      if (url === "/api/auth/login") {
        active = accounts.find((account) => account.login === JSON.parse(String(init?.body)).login)!;
        return json({ accessToken: `test-token-${active.id}` });
      }
      if (url === "/api/auth/me") return json(active);
      if (url === "/api/auth/logout") return new Response(null, { status: 204 });
      if (url.startsWith("/api/admin/users?")) return json(accounts);
      if (url.startsWith("/api/weather/precipitation-types") || url.startsWith("/api/wardrobe/categories")) return json([]);
      if (url.startsWith("/api/wardrobe/items?page=")) {
        const actorId = new Headers(init?.headers).get("Authorization");
        return json(actorId === "Bearer test-token-1" ? [firstItem, secondItem] : []);
      }
      if (url.endsWith("/photos")) return json([photo(Number(url.split("/")[4]))]);
      if (url.endsWith("/content")) {
        if (url.includes("/items/2/")) {
          return new Promise<Response>((resolve) => { finishLatePhoto = resolve; });
        }
        return { ok: true, status: 200, headers: new Headers(), blob: async () => new Blob(["photo"]) } as Response;
      }
      return failed(404);
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<MemoryRouter><App /></MemoryRouter>);

    // given: фотография первого аккаунта загружается с задержкой.

    fireEvent.change(await screen.findByLabelText("Логин"), { target: { value: accounts[0].login } });
    fireEvent.change(screen.getByLabelText("Пароль"), { target: { value: "password" } });
    fireEvent.click(screen.getByRole("button", { name: "Войти" }));
    await screen.findByRole("img", { name: "Вещь 1 · оливковый" });
    await waitFor(() => expect(finishLatePhoto).toBeTypeOf("function"));
    fireEvent.click(screen.getByRole("button", { name: "Выйти" }));

    // when: пользователь переключается на другой аккаунт до ответа.

    fireEvent.change(await screen.findByLabelText("Логин"), { target: { value: accounts[1].login } });
    fireEvent.change(screen.getByLabelText("Пароль"), { target: { value: "password" } });
    fireEvent.click(screen.getByRole("button", { name: "Войти" }));
    await screen.findByRole("heading", { name: "Аккаунты" });
    await act(async () => finishLatePhoto({
      ok: true,
      status: 200,
      headers: new Headers(),
      blob: async () => new Blob(["stale photo"]),
    } as Response));

    // then: поздний ответ не раскрывает данные первого аккаунта.

    expect(screen.getAllByText("гардероб-два").length).toBeGreaterThan(0);
    expect(screen.queryByText("Вещь 1")).toBeNull();
    expect(revoked).toHaveBeenCalledWith("blob:wardrobe-one");
    expect(created).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.some(([url, init]) => (
      String(url).endsWith("/content") && new Headers(init?.headers).get("Authorization") === "Bearer test-token-1"
    ))).toBe(true);
    expect(fetchMock.mock.calls.some(([url, init]) => (
      String(url).startsWith("/api/wardrobe/items?page=")
        && new Headers(init?.headers).get("Authorization") === "Bearer test-token-2"
    ))).toBe(false);
  });

  it("не сбрасывает форму до завершения первоначальной загрузки сохранённых вещей", async () => {
    let finishRead!: (response: Response) => void;
    const saved: Outfit = {
      id: 93, ownerId: 1, authorId: 1, source: "AI", likes: 0, dislikes: 0, createdAt: "2026-01-01T00:00:00Z",
      name: "Сохранённый образ",
      itemIds: [1],
      weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 7 },
    };
    const fetchMock = apiFetch(
      [wardrobeItem(1)],
      async () => json(saved),
      () => new Promise<Response>((resolve) => { finishRead = resolve; }),
    );
    vi.stubGlobal("fetch", fetchMock);
    mountToday();

    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "Мой новый образ" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });

    // given: POST сохраняет образ, а загрузка его вещи ещё выполняется.

    fireEvent.click(await readyToSubmit());
    const reset = await screen.findByRole("button", { name: "Составить ещё один образ" });
    await waitFor(() => expect((reset as HTMLButtonElement).disabled).toBe(true));
    await waitFor(() => expect(finishRead).toBeTypeOf("function"));

    // when: пользователь нажимает «Составить ещё один образ».

    fireEvent.click(reset);

    // then: сброс недоступен до ответа, а после загрузки удаляет сохранённый результат.

    expect(screen.getByText("Сохранённый образ")).toBeTruthy();

    await act(async () => finishRead(json(wardrobeItem(1))));
    await waitFor(() => expect((reset as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(reset);
    expect(screen.queryByText("Сохранённый образ")).toBeNull();
    expect((screen.getByLabelText("НАЗВАНИЕ ОБРАЗА") as HTMLInputElement).value).toBe("");
  });

  it("блокирует сброс при повторной загрузке вещей сохранённого образа", async () => {
    let failInitialRead!: (response: Response) => void;
    let finishRetry!: (response: Response) => void;
    let reads = 0;
    const saved: Outfit = {
      id: 94, ownerId: 1, authorId: 1, source: "AI", likes: 0, dislikes: 0, createdAt: "2026-01-01T00:00:00Z",
      name: "Образ для повтора",
      itemIds: [1],
      weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 7 },
    };
    const fetchMock = apiFetch([wardrobeItem(1)], async () => json(saved), () => {
      reads += 1;
      if (reads === 1) return new Promise<Response>((resolve) => { failInitialRead = resolve; });
      return new Promise<Response>((resolve) => { finishRetry = resolve; });
    });
    vi.stubGlobal("fetch", fetchMock);
    mountToday();

    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "Образ для повтора" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });
    fireEvent.click(await readyToSubmit());
    const reset = await screen.findByRole("button", { name: "Составить ещё один образ" });
    await waitFor(() => expect(failInitialRead).toBeTypeOf("function"));
    await act(async () => failInitialRead(failed(500)));
    await screen.findByRole("alert");
    const retry = screen.getByRole("button", { name: "Повторить загрузку вещей" });

    // given: первая загрузка вещи завершилась ошибкой.

    fireEvent.click(retry);
    await waitFor(() => expect((reset as HTMLButtonElement).disabled).toBe(true));
    await waitFor(() => expect(finishRetry).toBeTypeOf("function"));

    // when: пользователь пытается сбросить форму во время повтора.

    fireEvent.click(reset);

    // then: результат сохраняется до завершения повтора, после чего сброс доступен.

    expect(screen.getByText("Образ для повтора")).toBeTruthy();

    await act(async () => finishRetry(json({ ...wardrobeItem(1), availability: "IN_LAUNDRY" })));
    await waitFor(() => expect((reset as HTMLButtonElement).disabled).toBe(false));
    expect(screen.getByText("В стирке").closest("article")?.textContent).toContain("Вещь 1");
    fireEvent.click(reset);
    expect(screen.queryByText("Образ для повтора")).toBeNull();
  });
});
