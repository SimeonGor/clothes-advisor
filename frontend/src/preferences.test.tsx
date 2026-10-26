import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRoutes } from "./app";
import type { Actor, StylePreferences } from "./api";

const user: Actor = { id: 11, login: "owner", role: "USER" };
const stylist: Actor = { id: 22, login: "stylist", role: "STYLIST" };
const admin: Actor = { id: 33, login: "admin", role: "ADMIN" };
const ownPath = "/api/me/style-preferences";
const clientPath = "/api/stylist/clients/11/style-preferences";
const colors = "Любимые цвета и материалы";
const saveName = "Сохранить предпочтения";
const profile = (version = 4, text = "Синий и хлопок"): StylePreferences => ({
  preferredColorsAndMaterials: text, avoidances: "Шерсть", additionalWishes: "Свободный крой",
  version, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-02T00:00:00Z",
});
const ok = (data: unknown) => new Response(JSON.stringify(data), { status: 200 });
const fail = (status: number) => new Response(null, { status });
type Call = { url: string; init?: RequestInit };
const clients: QueryClient[] = [];

function mount(initialActor: Actor = user, path = "/preferences") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  let navigate!: ReturnType<typeof useNavigate>;
  let setActor!: React.Dispatch<React.SetStateAction<Actor>>;
  const switched = vi.fn();
  function Harness() {
    navigate = useNavigate();
    const [actor, changeActor] = React.useState(initialActor);
    setActor = changeActor;
    const [pending, setPending] = React.useState(false);
    return <AppRoutes account={actor} pending={pending} onPending={setPending} onChoose={vi.fn()} onSwitch={switched} />;
  }
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Harness /></MemoryRouter></QueryClientProvider>);
  return { client, switched, go: (next: string) => act(() => navigate(next)),
    changeActor: (actor: Actor) => act(() => setActor(actor)) };
}

function fixture(handler: (call: Call) => Response | Promise<Response>) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), init };
    calls.push(call);
    return handler(call);
  }));
  return calls;
}

const writes = (calls: Call[]) => calls.filter(({ init }) => init?.method === "PUT");
const field = (label = colors) => screen.getByLabelText(label) as HTMLTextAreaElement;
const save = () => screen.getByRole("button", { name: saveName }) as HTMLButtonElement;

beforeEach(() => vi.stubGlobal("fetch", vi.fn()));
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); vi.unstubAllGlobals(); });

describe("Предпочтения владельца", () => {
  it("создаёт профиль после 404 и обновляет его с версией ответа, блокируя двойную отправку и навигацию", async () => {
    // given
    let finish!: (response: Response) => void;
    const calls = fixture(({ init }) => init?.method === "PUT"
      ? new Promise<Response>((resolve) => { finish = resolve; }) : fail(404));
    const view = mount();
    await screen.findByLabelText(colors);
    expect(field().value).toBe("");
    expect(screen.getByRole("link", { name: "Предпочтения ↗" }).getAttribute("aria-current")).toBe("page");
    fireEvent.change(field(), { target: { value: "Зелёный" } });
    fireEvent.change(field("Чего избегать"), { target: { value: "Синтетика" } });

    // when
    fireEvent.click(save());
    fireEvent.submit(field().closest("form")!);
    fireEvent.click(screen.getByRole("link", { name: "Гардероб ↗" }));
    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));

    // then
    expect(writes(calls)).toHaveLength(1);
    expect(screen.getByRole("heading", { name: "Предпочтения" })).toBeTruthy();
    expect(view.switched).not.toHaveBeenCalled();
    expect(field().disabled).toBe(true);
    expect(JSON.parse(String(writes(calls)[0].init?.body))).toEqual({
      preferredColorsAndMaterials: "Зелёный", avoidances: "Синтетика", additionalWishes: "", version: null,
    });
    const headers = new Headers(writes(calls)[0].init?.headers);
    expect(headers.get("X-User-Id")).toBe("11");
    expect(headers.has("Idempotency-Key")).toBe(false);
    await act(async () => finish(ok(profile(7, "Зелёный с сервера"))));
    await screen.findByText("Предпочтения сохранены.");
    expect(field().value).toBe("Зелёный с сервера");
    fireEvent.change(field("Дополнительные пожелания"), { target: { value: "Новое пожелание" } });
    fireEvent.click(save());
    expect(JSON.parse(String(writes(calls)[1].init?.body))).toEqual({
      preferredColorsAndMaterials: "Зелёный с сервера", avoidances: "Шерсть", additionalWishes: "Новое пожелание", version: 7,
    });
    await act(async () => finish(ok(profile(10))));
    await screen.findByText("Предпочтения сохранены.");
  });

  it("передаёт все пустые поля и ограничивает каждое поле 2000 символами", async () => {
    // given
    const calls = fixture(({ init }) => init?.method === "PUT" ? ok(profile(1, "")) : fail(404));
    mount();
    await screen.findByLabelText(colors);

    // when
    fireEvent.click(save());
    await screen.findByText("Предпочтения сохранены.");

    // then
    expect(JSON.parse(String(writes(calls)[0].init?.body))).toEqual({
      preferredColorsAndMaterials: "", avoidances: "", additionalWishes: "", version: null,
    });
    for (const label of [colors, "Чего избегать", "Дополнительные пожелания"]) {
      expect(field(label).maxLength).toBe(2000);
      expect(field(label).required).toBe(false);
      fireEvent.change(field(label), { target: { value: "я".repeat(2001) } });
      fireEvent.submit(field(label).closest("form")!);
      expect(writes(calls)).toHaveLength(1);
      fireEvent.change(field(label), { target: { value: "я".repeat(2000) } });
    }
    fireEvent.click(save());
    await screen.findByText("Предпочтения сохранены.");
    const body = JSON.parse(String(writes(calls)[1].init?.body));
    expect([body.preferredColorsAndMaterials.length, body.avoidances.length, body.additionalWishes.length]).toEqual([2000, 2000, 2000]);
  });

  it.each([403, 503, "network"])("не открывает пустую форму при ошибке GET %s до успешного повтора", async (status) => {
    // given
    let recovered = false;
    const calls = fixture(() => {
      if (recovered) return ok(profile());
      if (status === "network") return Promise.reject(new TypeError("network"));
      return fail(Number(status));
    });
    mount();

    // when
    await screen.findByRole("alert");

    // then
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: saveName })).toBeNull();
    expect(writes(calls)).toHaveLength(0);
    recovered = true;
    fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
    await screen.findByDisplayValue("Синий и хлопок");
    await waitFor(() => expect(save().disabled).toBe(false));
  });

  it.each([409, 503, "network"])("сохраняет черновик после PUT %s и требует явной перезагрузки", async (status) => {
    // given
    let latest = profile(4);
    let writeFails = true;
    let finishReload: ((response: Response) => void) | undefined;
    const calls = fixture(({ init }) => {
      if (init?.method === "PUT") {
        if (!writeFails) return ok(profile(15));
        return status === "network" ? Promise.reject(new TypeError("network")) : fail(Number(status));
      }
      if (finishReload) return new Promise<Response>((resolve) => { finishReload = resolve; });
      return ok(latest);
    });
    mount();
    await screen.findByLabelText(colors);
    fireEvent.change(field(), { target: { value: "Мой черновик" } });

    // when
    fireEvent.click(save());
    await screen.findByRole("alert");

    // then
    expect(field().value).toBe("Мой черновик");
    expect(save().disabled).toBe(true);
    expect(screen.getByText(/Обновление заменит введённые значения формы/)).toBeTruthy();
    fireEvent.submit(field().closest("form")!);
    expect(writes(calls)).toHaveLength(1);
    latest = profile(12, "Новый профиль сервера");
    finishReload = () => {};
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    fireEvent.click(screen.getByRole("link", { name: "Гардероб ↗" }));
    expect(screen.getByRole("heading", { name: "Предпочтения" })).toBeTruthy();
    expect((screen.getByRole("button", { name: "Сменить аккаунт" }) as HTMLButtonElement).disabled).toBe(true);
    expect(calls.filter(({ init }) => !init?.method)).toHaveLength(2);
    await act(async () => finishReload?.(ok(latest)));
    await screen.findByText("Данные обновлены.");
    expect(field().value).toBe("Новый профиль сервера");
    writeFails = false;
    fireEvent.click(save());
    await screen.findByText("Предпочтения сохранены.");
    expect(JSON.parse(String(writes(calls)[1].init?.body)).version).toBe(12);
  });

  it("оставляет исправляемый черновик и версию после 400 без автоматического повтора", async () => {
    // given
    let invalid = true;
    const calls = fixture(({ init }) => init?.method === "PUT" ? invalid ? fail(400) : ok(profile(8)) : ok(profile(4)));
    mount();
    await screen.findByLabelText(colors);
    fireEvent.change(field(), { target: { value: "Черновик" } });

    // when
    fireEvent.click(save());
    await screen.findByRole("alert");

    // then
    expect(field().value).toBe("Черновик");
    expect(field().disabled).toBe(false);
    expect(save().disabled).toBe(false);
    expect(screen.queryByRole("button", { name: "Обновить данные" })).toBeNull();
    expect(writes(calls)).toHaveLength(1);
    invalid = false;
    fireEvent.change(field(), { target: { value: "Исправлено" } });
    fireEvent.click(save());
    await screen.findByText("Предпочтения сохранены.");
    expect(JSON.parse(String(writes(calls)[1].init?.body)).version).toBe(4);
  });

  it("не теряет черновик после фонового обновления и не разрешает запись после неудачной перезагрузки", async () => {
    // given
    let latest = profile(4);
    let readFails = false;
    const calls = fixture(({ init }) => init?.method === "PUT" ? fail(409) : readFails ? fail(503) : ok(latest));
    const view = mount();
    await screen.findByLabelText(colors);
    fireEvent.change(field(), { target: { value: "Мой текст" } });
    latest = profile(8, "Чужое изменение");

    // when
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["style-preferences", 11, 11] }); });
    fireEvent.click(save());
    await screen.findByRole("alert");

    // then
    expect(field().value).toBe("Мой текст");
    expect(JSON.parse(String(writes(calls)[0].init?.body)).version).toBe(4);
    readFails = true;
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    await waitFor(() => expect(screen.getAllByRole("alert").some((node) => node.textContent?.includes("Сервис временно недоступен"))).toBe(true));
    expect(field().value).toBe("Мой текст");
    expect(save().disabled).toBe(true);
    fireEvent.submit(field().closest("form")!);
    expect(writes(calls)).toHaveLength(1);
    readFails = false;
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    await screen.findByText("Данные обновлены.");
    expect(field().value).toBe("Чужое изменение");
  });

  it("блокирует запись после фонового отказа GET и сохраняет черновик до явной загрузки", async () => {
    // given
    let readFails = false;
    const calls = fixture(() => readFails ? fail(503) : ok(profile()));
    const view = mount();
    await screen.findByLabelText(colors);
    fireEvent.change(field(), { target: { value: "Черновик" } });

    // when
    readFails = true;
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["style-preferences", 11, 11] }); });

    // then
    await screen.findByRole("alert");
    expect(field().value).toBe("Черновик");
    expect(save().disabled).toBe(true);
    fireEvent.submit(field().closest("form")!);
    expect(writes(calls)).toHaveLength(0);
  });

  it("не показывает черновик предыдущего аккаунта при загрузке другого пользователя", async () => {
    // given
    let finish!: (response: Response) => void;
    const calls = fixture(({ init }) => new Headers(init?.headers).get("X-User-Id") === "11"
      ? ok(profile(4, "Личный текст первого")) : new Promise<Response>((resolve) => { finish = resolve; }));
    const view = mount();
    await screen.findByDisplayValue("Личный текст первого");
    fireEvent.change(field(), { target: { value: "Несохранённый текст" } });

    // when
    view.changeActor({ id: 12, login: "second", role: "USER" });

    // then
    expect(screen.queryByDisplayValue("Несохранённый текст")).toBeNull();
    expect(screen.queryByDisplayValue("Личный текст первого")).toBeNull();
    await act(async () => finish(ok(profile(2, "Текст второго"))));
    await screen.findByDisplayValue("Текст второго");
    expect(calls.map(({ init }) => new Headers(init?.headers).get("X-User-Id"))).toEqual(["11", "12"]);
  });
});

describe("Предпочтения клиента и маршруты ролей", () => {
  it("читает предпочтения клиента обычным текстом без элементов редактирования", async () => {
    // given
    const text = '<img src=x onerror="alert(1)">\n**Без Markdown**';
    const calls = fixture(({ url }) => url === clientPath ? ok(profile(4, text))
      : ok([{ id: 11, login: "owner" }]));
    mount(stylist, "/clients/11/preferences");

    // when
    const value = await screen.findByText(/Без Markdown/);

    // then
    expect(value.textContent).toBe(text);
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: saveName })).toBeNull();
    expect(screen.getByRole("link", { name: "Предпочтения клиента" })).toBeTruthy();
    expect(calls.every(({ init }) => new Headers(init?.headers).get("X-User-Id") === "22")).toBe(true);
    expect(calls.some(({ url }) => url === ownPath)).toBe(false);
  });

  it.each([404, 403, 503, "network"])("скрывает кеш клиента после отказа GET %s и не предлагает создание", async (status) => {
    // given
    let denied = false;
    fixture(({ url }) => {
      if (url !== clientPath) return ok([{ id: 11, login: "owner" }]);
      if (!denied) return ok(profile(4, "Закрытые пожелания"));
      return status === "network" ? Promise.reject(new TypeError("network")) : fail(Number(status));
    });
    const view = mount(stylist, "/clients/11/preferences");
    await screen.findByText("Закрытые пожелания");

    // when
    denied = true;
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["style-preferences", 22, 11] }); });

    // then
    await screen.findByRole("alert");
    expect(screen.queryByText("Закрытые пожелания")).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: saveName })).toBeNull();
    if (status === 404) expect(screen.getByRole("alert").textContent).toContain("Данные не найдены или доступ к ним отозван");
  });

  it.each([200, 403, 503])("скрывает предпочтения после неуспешной проверки списка клиентов: %s", async (status) => {
    // given
    let denied = false;
    fixture(({ url }) => url === clientPath ? ok(profile(4, "Пожелания клиента"))
      : denied ? status === 200 ? ok([]) : fail(status) : ok([{ id: 11, login: "owner" }]));
    const view = mount(stylist, "/clients/11/preferences");
    await screen.findByText("Пожелания клиента");

    // when
    denied = true;
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["clients", 22] }); });

    // then
    await screen.findByRole("alert");
    expect(screen.queryByText("Пожелания клиента")).toBeNull();
  });

  it("изолирует данные между клиентами и стилистами, включая незавершённую загрузку", async () => {
    // given
    let finish!: (response: Response) => void;
    const calls = fixture(({ url, init }) => {
      if (url.startsWith("/api/stylist/clients?")) return ok([{ id: 11, login: "first" }, { id: 12, login: "second" }]);
      if (url === clientPath && new Headers(init?.headers).get("X-User-Id") === "22") return ok(profile(4, "Первый клиент"));
      return new Promise<Response>((resolve) => { finish = resolve; });
    });
    const view = mount(stylist, "/clients/11/preferences");
    await screen.findByText("Первый клиент");

    // when
    view.go("/clients/12/preferences");

    // then
    expect(screen.queryByText("Первый клиент")).toBeNull();
    await waitFor(() => expect(calls.some(({ url }) => url === "/api/stylist/clients/12/style-preferences")).toBe(true));
    await act(async () => finish(ok(profile(4, "Второй клиент"))));
    await screen.findByText("Второй клиент");
    view.changeActor({ id: 23, login: "another stylist", role: "STYLIST" });
    expect(screen.queryByText("Второй клиент")).toBeNull();
    await waitFor(() => expect(calls.some(({ url, init }) => url.endsWith("/12/style-preferences")
      && new Headers(init?.headers).get("X-User-Id") === "23")).toBe(true));
    await act(async () => finish(fail(404)));
    await screen.findByRole("alert");
    expect(screen.queryByText("Второй клиент")).toBeNull();
  });

  it("не запрашивает предпочтения без разрешения клиента", async () => {
    // given
    const calls = fixture(() => ok([]));
    mount(stylist, "/clients/11/preferences");

    // when
    await screen.findByRole("alert");

    // then
    expect(calls.every(({ url }) => url.startsWith("/api/stylist/clients?"))).toBe(true);
    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it.each([stylist, admin])("не открывает собственные предпочтения для $role", async (actor) => {
    // given
    const calls = fixture(() => ok([]));
    mount(actor);

    // when
    await screen.findByRole("heading", { name: actor.role === "STYLIST" ? "Клиенты" : "Аккаунты" });

    // then
    expect(screen.queryByRole("link", { name: "Предпочтения ↗" })).toBeNull();
    expect(calls.some(({ url }) => url.includes("style-preferences"))).toBe(false);
  });

  it("не добавляет вкладку или запрос предпочтений в административную карточку", async () => {
    // given
    const calls = fixture(() => ok({ ...user, status: "ACTIVE", version: 1, createdAt: "", modifiedAt: "" }));
    mount(admin, "/accounts/11/preferences");

    // when
    await screen.findByLabelText("РОЛЬ");

    // then
    expect(screen.queryByRole("link", { name: /Предпочтения/ })).toBeNull();
    expect(calls.some(({ url }) => url.includes("style-preferences"))).toBe(false);
  });
});
