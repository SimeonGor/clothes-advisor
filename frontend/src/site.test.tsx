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
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
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
    if (url === "/api/outfits/ai") {
      return create(JSON.parse(String(init?.body)) as Record<string, unknown>);
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
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/outfits/ai");
    const body = JSON.parse(String(sent?.[1]?.body));
    expect(body).toEqual({
      name: "Ясное утро",
      weather: { temperatureC: -2.5, windSpeedMps: 1.75, precipitationTypeId: 7 },
    });
    expect(screen.getByText("Новое утро")).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Почему этот образ" })).toBeNull();
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
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/outfits/ai");
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
    expect(fetchMock.mock.calls.some(([url]) => url === "/api/outfits/ai")).toBe(false);

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

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === "/api/outfits/ai")).toBe(true));
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/outfits/ai");
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
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/outfits/ai");
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

  it("не дублирует ожидающий POST и сохраняет форму после ошибки", async () => {
    let finish!: (response: Response) => void;
    const create = vi.fn(() => new Promise<Response>((resolve) => { finish = resolve; }));
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
    expect(create).toHaveBeenCalledTimes(1);
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
