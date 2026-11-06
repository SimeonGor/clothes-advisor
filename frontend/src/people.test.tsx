import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App, AppRoutes } from "./app";
import { PhotoImage } from "./site";
import type { Actor, Photo, WardrobeItem } from "./api";

const user: Actor = { id: 11, login: "owner", role: "USER" };
const stylist: Actor = { id: 22, login: "stylist", role: "STYLIST" };
const admin: Actor = { id: 33, login: "admin", role: "ADMIN" };
const details = (actor: Actor, version = 1, status = "ACTIVE") => ({ ...actor, version, status, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-02T00:00:00Z" });
const item: WardrobeItem = { id: 1, name: "Рубашка", categoryId: 1, color: "белый", material: "хлопок", version: 1, createdAt: "", modifiedAt: "" };
const photo: Photo = { id: 9, itemId: 1, contentType: "image/png", sizeBytes: 10, createdAt: "" };
const ok = (data: unknown, status = 200) => ({ ok: true, status, headers: new Headers(), json: async () => data, blob: async () => new Blob(["image"]) } as Response);
const fail = (status: number) => new Response(null, { status });
type Call = { url: string; init?: RequestInit };

function mount(actor: Actor, path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  let navigate!: ReturnType<typeof useNavigate>;
  function Harness() {
    navigate = useNavigate();
    const [pending, setPending] = React.useState(false);
    return <AppRoutes account={actor} pending={pending} onPending={setPending} onChoose={vi.fn()} onSwitch={vi.fn()} />;
  }
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Harness /></MemoryRouter></QueryClientProvider>);
  return { client, go: (next: string) => act(() => navigate(next)) };
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:photo") });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Рабочие пространства ролей", () => {
  it("выдаёт доступ только активному стилисту и отзывает старый доступ после смены роли", async () => {
    // given: старый получатель заблокирован и сменил роль, новый стилист активен.
    const old = { id: 44, login: "бывший стилист", role: "USER" };
    let grants = [{ id: old.id, login: old.login }];
    let finishGrant!: (response: Response) => void;
    const calls: Call[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, init });
      if (url.startsWith("/api/admin/users?")) return ok([details(old, 2, "BLOCKED"), details(stylist), details({ id: 55, login: "blocked stylist", role: "STYLIST" }, 1, "BLOCKED"), details(user)]);
      if (url.startsWith("/api/me/stylist-access?")) return ok([...grants]);
      if (init?.method === "PUT") return new Promise<Response>((resolve) => { finishGrant = resolve; });
      if (init?.method === "DELETE") { grants = grants.filter((row) => row.id !== old.id); return ok(null, 204); }
      return fail(404);
    }));
    mount(user, "/access");
    const select = await screen.findByLabelText("СТИЛИСТ") as HTMLSelectElement;
    await screen.findByRole("option", { name: "stylist" });
    expect(Array.from(select.options).map((option) => option.text)).toEqual(["Выберите стилиста", "stylist"]);
    expect(screen.getByText("бывший стилист")).toBeTruthy();
    // when: двойное подтверждение ожидает сервер; навигация заблокирована.
    fireEvent.change(select, { target: { value: "22" } });
    fireEvent.click(screen.getByRole("button", { name: "Предоставить доступ" }));
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить доступ" }));
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить доступ" }));
    fireEvent.click(screen.getByRole("link", { name: /Гардероб/ }));
    expect(screen.getByRole("heading", { name: "Доступ стилистам" })).toBeTruthy();
    expect((screen.getByRole("button", { name: "Сменить аккаунт" }) as HTMLButtonElement).disabled).toBe(true);
    grants.push({ id: stylist.id, login: stylist.login });
    finishGrant(ok(null, 204));
    await screen.findByText("Доступ предоставлен.");
    await waitFor(() => expect(screen.queryByRole("option", { name: "stylist" })).toBeNull());
    const oldRow = screen.getByText("бывший стилист").closest("article")!;
    fireEvent.click(within(oldRow).getByRole("button", { name: "Отозвать доступ" }));
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить отзыв" }));
    // then: старый доступ удалён, запросы не дублируются и содержат действующего пользователя.
    await screen.findByText("Доступ отозван.");
    await waitFor(() => expect(screen.queryByText("бывший стилист")).toBeNull());
    const writes = calls.filter((call) => call.init?.method);
    expect(writes.map((call) => [call.url, call.init?.method])).toEqual([["/api/me/stylist-access/22", "PUT"], ["/api/me/stylist-access/44", "DELETE"]]);
    expect(writes.every((call) => new Headers(call.init?.headers).get("X-User-Id") === "11")).toBe(true);
  });

  it("открывает клиентские вещи только для чтения и изолирует фото при смене владельца", async () => {
    // given: у двух клиентов одинаковые идентификаторы вещей и фото.
    const calls: Call[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, init });
      if (url.startsWith("/api/stylist/clients?")) return ok([{ id: 11, login: "Анна" }, { id: 12, login: "Борис" }]);
      if (url.startsWith("/api/wardrobe/categories?")) return ok([{ id: 1, name: "Рубашки", code: "SHIRT" }]);
      if (url.includes("/content")) return ok(null);
      if (url.endsWith("/photos")) return ok([photo]);
      if (url.includes("/wardrobe/items")) return ok(url.includes("?page=") ? [item] : { ...item, name: url.includes("/12/") ? "Вещь Бориса" : item.name });
      return fail(404);
    }));
    const view = mount(stylist, "/clients/11/wardrobe");
    fireEvent.click(await screen.findByRole("link", { name: /Рубашка/ }));
    await screen.findByRole("heading", { name: "Рубашка" });
    expect(screen.queryByRole("button", { name: /Удалить|Сохранить/ })).toBeNull();
    expect(screen.queryByLabelText(/НАЗВАНИЕ/)).toBeNull();
    await screen.findByRole("img");
    // when: открывается другой владелец с тем же itemId.
    view.go("/clients/12/wardrobe/1");
    await screen.findByRole("heading", { name: "Вещь Бориса" });
    await waitFor(() => expect(calls.some((call) => call.url === "/api/stylist/clients/12/wardrobe/items/1/photos/9/content")).toBe(true));
    // then: оба запроса имеют клиентские пути, старое фото освобождено, своих вещей не запрашивали.
    expect(calls.some((call) => call.url === "/api/stylist/clients/11/wardrobe/items/1/photos/9/content")).toBe(true);
    expect(calls.some((call) => call.url.startsWith("/api/wardrobe/items"))).toBe(false);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:photo");
    expect(screen.getByLabelText("Владелец содержимого").textContent).toContain("Борис");
    fireEvent.click(screen.getByRole("link", { name: "Образы владельца" }));
    expect(await screen.findByRole("link", { name: /Создать образ/ })).toBeTruthy();
  });

  it("не открывает содержимое отсутствующего в списке клиента", async () => {
    // given: доступ отозван, список клиентов пуст.
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => { calls.push(String(input)); return ok([]); }));
    const view = mount(stylist, "/clients/99/wardrobe/1");
    // when: открыт прямой адрес бывшего клиента.
    expect((await screen.findByRole("alert")).textContent).toContain("доступ к ним отозван");
    // then: нет запросов чужих или собственных вещей; некорректный ID тоже не запрашивается.
    expect(calls.every((url) => url.startsWith("/api/stylist/clients?"))).toBe(true);
    view.go("/clients/0/wardrobe");
    expect(await screen.findByText("Некорректный номер владельца.")).toBeTruthy();
  });

  it("сохраняет версию черновика администратора при фоновом обновлении и требует обновления после 409", async () => {
    // given: форма версии 1; сервер изменяется до версии 8 до отправки.
    let saved = details(user);
    const writes: Call[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === "PUT") {
        writes.push({ url, init });
        if (writes.length === 1) return fail(409);
        saved = { ...saved, ...JSON.parse(String(init.body)), version: 9 };
        return ok(saved);
      }
      if (url === "/api/admin/users/11") return ok(saved);
      return ok([]);
    }));
    const view = mount(admin, "/accounts/11");
    const role = await screen.findByLabelText("РОЛЬ") as HTMLSelectElement;
    fireEvent.change(role, { target: { value: "STYLIST" } });
    saved = { ...saved, version: 8, status: "BLOCKED" };
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["account", 33, 11] }); });
    // when: старый черновик получает конфликт.
    fireEvent.click(screen.getByRole("button", { name: "Сохранить роль и статус" }));
    await screen.findByRole("alert");
    expect(role.value).toBe("STYLIST");
    expect((screen.getByLabelText("СТАТУС") as HTMLSelectElement).value).toBe("ACTIVE");
    expect(JSON.parse(String(writes[0].init?.body))).toEqual({ role: "STYLIST", status: "ACTIVE", version: 1 });
    expect((screen.getByRole("button", { name: "Сохранить роль и статус" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    await screen.findByText("Данные обновлены.");
    expect(role.value).toBe("USER");
    expect((screen.getByLabelText("СТАТУС") as HTMLSelectElement).value).toBe("BLOCKED");
    fireEvent.change(role, { target: { value: "STYLIST" } });
    fireEvent.click(screen.getByRole("button", { name: "Сохранить роль и статус" }));
    // then: только явная перезагрузка даёт новую версию и успешное сохранение.
    await screen.findByText("Роль и статус сохранены.");
    expect(JSON.parse(String(writes[1].init?.body))).toEqual({ role: "STYLIST", status: "BLOCKED", version: 8 });
    view.go("/accounts/11/wardrobe");
    view.go("/accounts/11");
    expect((await screen.findByLabelText("РОЛЬ") as HTMLSelectElement).value).toBe("STYLIST");
  });

  it("сохраняет заблокированные аккаунты в каталоге, запрещает самоблокировку и разрешает удаление фото при модерации", async () => {
    // given: каталог содержит администратора и заблокированного пользователя.
    let photos = [photo];
    const calls: Call[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, init });
      if (url.startsWith("/api/admin/users?")) return ok([details(admin), details(user, 1, "BLOCKED")]);
      if (url === "/api/admin/users/33") return ok(details(admin));
      if (url === "/api/admin/users/11") return ok(details(user, 1, "BLOCKED"));
      if (init?.method === "DELETE") { photos = []; return ok(null, 204); }
      if (url.endsWith("/content")) return ok(null);
      if (url.endsWith("/photos")) return ok([...photos]);
      if (url.endsWith("/wardrobe/items/1")) return ok(item);
      return ok([]);
    }));
    const view = mount(admin, "/accounts");
    const blockedRow = await screen.findByRole("row", { name: /owner.*Пользователь.*Заблокирован/ });
    expect(blockedRow).toBeTruthy();
    fireEvent.click(within(screen.getByRole("row", { name: /admin.*Администратор/ })).getByRole("link", { name: "admin" }));
    expect((await screen.findByLabelText("РОЛЬ") as HTMLSelectElement).disabled).toBe(true);
    expect((screen.getByLabelText("СТАТУС") as HTMLSelectElement).disabled).toBe(true);
    expect(screen.getByText(/Нельзя заблокировать собственный/)).toBeTruthy();
    // when: администратор открывает фото заблокированного пользователя и подтверждает удаление.
    view.go("/accounts/11/wardrobe/1");
    fireEvent.click(await screen.findByRole("button", { name: "Удалить фото №9" }));
    expect(screen.queryByRole("button", { name: "Удалить вещь" })).toBeNull();
    expect(screen.queryByLabelText(/НАЗВАНИЕ/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить удаление фото" }));
    // then: удаляется только фото в административном контексте.
    await screen.findByText("Фотография удалена.");
    await screen.findByText("Фотографий пока нет.");
    expect(calls.filter((call) => call.init?.method).map((call) => [call.url, call.init?.method])).toEqual([["/api/admin/users/11/wardrobe/items/1/photos/9", "DELETE"]]);
    view.go("/accounts/11/outfits");
    await screen.findByRole("heading", { name: "Образы" });
    expect(screen.queryByRole("link", { name: /Создать образ/ })).toBeNull();
  });

  it("сохраняет черновик и исходную версию администратора после фонового 503 и повторной загрузки", async () => {
    // given: черновик версии 1 изменён до временного сбоя фонового чтения.
    let readStatus = 200;
    let version = 1;
    const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") { writes.push(init); return fail(409); }
      return readStatus === 200 ? ok(details(user, version)) : fail(readStatus);
    }));
    const view = mount(admin, "/accounts/11");
    const role = await screen.findByLabelText("РОЛЬ") as HTMLSelectElement;
    fireEvent.change(role, { target: { value: "STYLIST" } });
    readStatus = 503;
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["account", 33, 11] }); });
    // when: фоновый запрос завершается ошибкой, запись блокируется без размонтирования формы.
    await screen.findByRole("alert");
    expect(screen.getByLabelText("РОЛЬ")).toBe(role);
    expect(role.value).toBe("STYLIST");
    expect(role.disabled).toBe(true);
    fireEvent.submit(role.closest("form")!);
    expect(writes).toHaveLength(0);
    readStatus = 200;
    version = 8;
    fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
    await waitFor(() => expect(role.disabled).toBe(false));
    fireEvent.submit(role.closest("form")!);
    // then: успешное чтение не заменяет черновик и не подставляет версию 8.
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(role.value).toBe("STYLIST");
    expect(JSON.parse(String(writes[0].body))).toEqual({ role: "STYLIST", status: "ACTIVE", version: 1 });
  });

  it("сохраняет клиентский образ при сетевом сбое проверки доступа и блокирует запись до завершения повтора", async () => {
    // given: заполненная форма клиента и доступный гардероб.
    let networkFailure = false;
    let retryResolve: ((response: Response) => void) | undefined;
    const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === "POST") { writes.push(init); return fail(503); }
      if (url.startsWith("/api/stylist/clients?")) {
        if (networkFailure) throw new TypeError("network");
        if (retryResolve) return new Promise<Response>((resolve) => { retryResolve = resolve; });
        return ok([{ id: 11, login: "owner" }]);
      }
      if (url.startsWith("/api/weather/precipitation-types?")) return ok([{ id: 1, code: "CLEAR", name: "Ясно" }]);
      if (url.includes("/wardrobe/items?")) return ok([item]);
      return ok([]);
    }));
    const view = mount(stylist, "/clients/11/outfits/new");
    const name = await screen.findByLabelText("НАЗВАНИЕ ОБРАЗА") as HTMLInputElement;
    fireEvent.change(name, { target: { value: "Черновик клиента" } });
    await screen.findByRole("option", { name: "Ясно" });
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "1" } });
    fireEvent.click(await screen.findByLabelText("Выбрать: Рубашка"));
    networkFailure = true;
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["clients", 22] }); });
    // when: сеть недоступна, затем повторная проверка ещё ожидает ответ.
    await screen.findByRole("alert");
    expect(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА")).toBe(name);
    expect(name.value).toBe("Черновик клиента");
    expect(name.disabled).toBe(true);
    fireEvent.submit(name.closest("form")!);
    expect(writes).toHaveLength(0);
    networkFailure = false;
    retryResolve = () => {};
    fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
    expect(name.disabled).toBe(true);
    fireEvent.submit(name.closest("form")!);
    expect(writes).toHaveLength(0);
    await act(async () => { retryResolve?.(ok([{ id: 11, login: "owner" }])); });
    await waitFor(() => expect(name.disabled).toBe(false));
    fireEvent.submit(name.closest("form")!);
    // then: значения и выбранная вещь сохранены; запись разрешается только после успешной проверки.
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(JSON.parse(String(writes[0].body))).toEqual({ name: "Черновик клиента", itemIds: [1], weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 1 } });
  });

  it.each([403, 404, 200])("закрывает клиентскую форму после окончательного отказа доступа: %s", async (status) => {
    // given: форма была доступна, затем сервер отказал или убрал клиента из списка.
    let revoked = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).startsWith("/api/stylist/clients?")) return revoked
        ? status === 200 ? ok([]) : fail(status)
        : ok([{ id: 11, login: "owner" }]);
      return ok([]);
    }));
    const view = mount(stylist, "/clients/11/outfits/new");
    await screen.findByLabelText("НАЗВАНИЕ ОБРАЗА");
    // when: авторитетный ответ заменяет ранее разрешённый доступ.
    revoked = true;
    await act(async () => { await view.client.invalidateQueries({ queryKey: ["clients", 22] }); });
    // then: кеш не сохраняет доступ к форме записи.
    await screen.findByRole("alert");
    expect(screen.queryByLabelText("НАЗВАНИЕ ОБРАЗА")).toBeNull();
  });

  it("после смены аккаунта открывает стартовую страницу новой роли", async () => {
    // given: стилист и администратор доступны в переключателе.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/admin/users?")) return ok([details(stylist), details(admin)]);
      if (url === "/api/admin/users/22") return ok(details(stylist));
      if (url === "/api/admin/users/33") return ok(details(admin));
      return ok([]);
    }));
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
    Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });
    render(<MemoryRouter initialEntries={["/"]}><App /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "stylist Стилист" }));
    await screen.findByRole("heading", { name: "Клиенты" });
    // when: активный аккаунт меняется на администратора.
    fireEvent.click(screen.getByRole("button", { name: "Сменить аккаунт" }));
    fireEvent.click(await screen.findByRole("button", { name: "admin Администратор" }));
    // then: доступен административный каталог, клиентских вкладок больше нет.
    expect(await screen.findByRole("heading", { name: "Аккаунты" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Клиенты ↗" })).toBeNull();
  });

  it("убирает старый blob URL до загрузки фото из другого контекста", async () => {
    // given: собственное фото загружено, клиентское ещё ожидает ответ.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input).startsWith("/api/stylist") ? new Promise<Response>(() => {}) : ok(null)));
    const view = render(<PhotoImage actor={stylist} item={item} photo={photo} />);
    await screen.findByRole("img");
    // when: меняется источник при тех же itemId/photoId.
    view.rerender(<PhotoImage actor={stylist} item={item} photo={photo} itemsBase="/api/stylist/clients/11/wardrobe/items" />);
    // then: старое изображение не отображается и blob освобождён.
    expect(screen.queryByRole("img")).toBeNull();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:photo");
    expect(screen.getByText("Загружаем фото…")).toBeTruthy();
  });

  it("сохраняет прямую ссылку на образ при первоначальном выборе аккаунта", async () => {
    // given: приложение открыто по ссылке на сохранённый образ.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/admin/users?")) return ok([details(stylist)]);
      if (url === "/api/admin/users/22") return ok(details(stylist));
      if (url === "/api/outfits/5") return ok({ id: 5, name: "По прямой ссылке", ownerId: 22, authorId: 22, source: "USER", itemIds: [], likes: 0, dislikes: 0,
        weather: { temperatureC: 10, windSpeedMps: 0, precipitationTypeId: 1 }, createdAt: "2026-01-01T00:00:00Z" });
      return ok([]);
    }));
    render(<MemoryRouter initialEntries={["/outfits/5"]}><App /></MemoryRouter>);
    // when: выбран подходящий аккаунт.
    fireEvent.click(await screen.findByRole("button", { name: "stylist Стилист" }));
    // then: сохранена исходная страница, стартовый список клиентов не открыт.
    expect(await screen.findByRole("heading", { name: "По прямой ссылке" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Клиенты" })).toBeNull();
  });
});
