import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor, act } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Account, Outfit, Photo, WardrobeItem } from "./api";
import { AccountPicker, Today } from "./site";

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
});
const photo = (id: number): Photo => ({ id: id + 100, itemId: id });
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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function App() {
    const [pending, setPending] = React.useState(pendingInitially);
    return <Today account={actor} onSwitch={vi.fn()} onPending={setPending} pending={pending} />;
  }
  const view = render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);
  return { ...view, client };
}

async function readyToSubmit() {
  const button = await screen.findByRole("button", { name: /Создать образ на сегодня/ });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  return button;
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

beforeEach(() => { window.history.replaceState({}, "", "/app/today"); });
afterEach(() => {
  cleanup();
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
  });

  it("сохраняет выбор между страницами и передаёт более 20 кандидатов без обрезки", async () => {
    const saved: Outfit = {
      id: 42,
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

    await screen.findByLabelText("Выбрать: Вещь 1");
    for (let id = 1; id <= 12; id += 1) {
      fireEvent.click(screen.getByLabelText(`Выбрать: Вещь ${id}`));
    }
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    await screen.findByLabelText("Выбрать: Вещь 13");
    for (let id = 13; id <= 21; id += 1) {
      fireEvent.click(screen.getByLabelText(`Выбрать: Вещь ${id}`));
    }
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "Заметки о поле" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });

    // when: он создаёт образ.

    fireEvent.click(await readyToSubmit());

    // then: все выбранные идентификаторы попадают в запрос.

    await screen.findByText(/ОБРАЗ СОХРАНЁН · №42/);
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/outfits/ai");
    const body = JSON.parse(String(sent?.[1]?.body));
    expect(body.candidateItemIds).toEqual(Array.from({ length: 21 }, (_, index) => index + 1));
    expect(await screen.findByText("Сохранено вещей: 2.")).toBeTruthy();
  });

  it("не дублирует ожидающий POST и сохраняет форму после ошибки", async () => {
    let finish!: (response: Response) => void;
    const create = vi.fn(() => new Promise<Response>((resolve) => { finish = resolve; }));
    const fetchMock = apiFetch([], create);
    vi.stubGlobal("fetch", fetchMock);
    mountToday();

    const name = screen.getByLabelText("НАЗВАНИЕ ОБРАЗА");
    fireEvent.change(name, { target: { value: "Оставить это" } });
    fireEvent.change(await readyPrecipitationSelect(), { target: { value: "7" } });
    await readyToSubmit();

    // given: POST создания образа ещё не завершён.

    const form = name.closest("form")!;

    // when: форма отправлена повторно, затем сервер отвечает ошибкой.

    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(create).toHaveBeenCalledTimes(1);

    await act(async () => finish(failed(503)));

    // then: отправляется один запрос, а введённые данные остаются в форме.

    expect((await screen.findByRole("alert")).textContent).toContain("Сервис AI сейчас недоступен");
    expect((name as HTMLInputElement).value).toBe("Оставить это");
    expect(create).toHaveBeenCalledTimes(1);
  });

  it("перепроверяет аккаунт и отзывает URL фото при переключении", async () => {

    const created = vi.fn(() => "blob:wardrobe-one");
    const revoked = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: created });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revoked });
    const firstItem = wardrobeItem(1);
    const secondItem = wardrobeItem(2);
    let finishLatePhoto!: (response: Response) => void;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith("/api/admin/users?page=")) return json(accounts);
      if (url === "/api/admin/users/1") return json(accounts[0]);
      if (url === "/api/admin/users/2") return json(accounts[1]);
      if (url.startsWith("/api/weather/precipitation-types") || url.startsWith("/api/wardrobe/categories")) return json([]);
      if (url.startsWith("/api/wardrobe/items?page=")) {
        const actorId = new Headers(init?.headers).get("X-User-Id");
        return json(actorId === "1" ? [firstItem, secondItem] : []);
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
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    function App() {
      const [actor, setActor] = React.useState<Pick<Account, "id" | "login" | "role"> | null>(null);
      if (!actor) return <AccountPicker onChoose={setActor} />;
      return <Today account={actor} onSwitch={() => setActor(null)} onPending={() => undefined} pending={false} />;
    }

    render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);

    // given: фотография первого аккаунта загружается с задержкой.

    fireEvent.click(await screen.findByRole("button", { name: /гардероб-один/ }));
    await screen.findByRole("img", { name: "Вещь 1 · оливковый" });
    await waitFor(() => expect(finishLatePhoto).toBeTypeOf("function"));
    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));

    // when: пользователь переключается на другой аккаунт до ответа.

    fireEvent.click(await screen.findByRole("button", { name: /гардероб-два/ }));
    await act(async () => finishLatePhoto({
      ok: true,
      status: 200,
      headers: new Headers(),
      blob: async () => new Blob(["stale photo"]),
    } as Response));

    // then: поздний ответ не раскрывает данные первого аккаунта.

    expect(screen.getByText("гардероб-два")).toBeTruthy();
    expect(screen.queryByText("Вещь 1")).toBeNull();
    expect(revoked).toHaveBeenCalledWith("blob:wardrobe-one");
    expect(created).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.some(([url, init]) => (
      String(url).endsWith("/content") && new Headers(init?.headers).get("X-User-Id") === "1"
    ))).toBe(true);
    expect(fetchMock.mock.calls.some(([url, init]) => (
      String(url).startsWith("/api/wardrobe/items?page=")
        && new Headers(init?.headers).get("X-User-Id") === "2"
    ))).toBe(false);
  });

  it("не сбрасывает форму до завершения первоначальной загрузки сохранённых вещей", async () => {
    let finishRead!: (response: Response) => void;
    const saved: Outfit = {
      id: 93,
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
      id: 94,
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

    await act(async () => finishRetry(json(wardrobeItem(1))));
    await waitFor(() => expect((reset as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(reset);
    expect(screen.queryByText("Образ для повтора")).toBeNull();
  });
});
