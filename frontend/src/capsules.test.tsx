import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Actor, Capsule, WardrobeItem } from "./api";
import { AppRoutes } from "./app";
import { CapsuleDetail, CapsuleEditor, Capsules } from "./capsules";
import { WardrobeItemPicker } from "./wardrobe-item-picker";

const user: Actor = { id: 11, login: "user", role: "USER" };
const stylist: Actor = { id: 22, login: "stylist", role: "STYLIST" };
const item = (id: number): WardrobeItem => ({ id, name: `Вещь ${id}`, categoryId: 1, color: "оливковый", material: "хлопок", version: 1, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-01T00:00:00Z" });
const capsule = (extra: Partial<Capsule> = {}): Capsule => ({ id: 5, ownerId: user.id, name: "Поездка", itemIds: [1], createdAt: "2026-01-01T00:00:00Z", ...extra });
const ok = (data: unknown, status = 200) => ({ ok: true, status, headers: new Headers(), json: async () => data } as Response);
const fail = (status: number) => ({ ok: false, status, headers: new Headers(), json: async () => ({}) } as Response);

function mount(path = "/capsules/new", seed?: Array<[readonly unknown[], unknown]>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  seed?.forEach(([key, data]) => client.setQueryData(key, data));
  const onPending = vi.fn();
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/capsules" element={<Capsules actor={user} pending={false} />} />
    <Route path="/capsules/new" element={<CapsuleEditor actor={user} onPending={onPending} pending={false} />} />
    <Route path="/capsules/:capsuleId" element={<CapsuleDetail actor={user} onPending={onPending} pending={false} />} />
    <Route path="/wardrobe/:itemId" element={<h1>Карточка вещи</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { ...view, client, onPending };
}

beforeEach(() => { sessionStorage.clear(); vi.stubGlobal("fetch", vi.fn()); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Ручные капсулы", () => {
  it("скрывает закэшированный список при ошибке чтения", async () => {
    // given
    vi.stubGlobal("fetch", vi.fn(async () => fail(503)));
    mount("/capsules", [[ ["capsules", user.id, 0], [capsule()] ]]);

    // when
    await screen.findByRole("alert");

    // then
    expect(screen.queryByText("Поездка")).toBeNull();
    expect(screen.getByRole("link", { name: /Капсулы|Новая капсула/ })).toBeTruthy();
  });

  it("повторяет неизвестный POST с тем же телом и ключом, пока черновик заблокирован", async () => {
    // given: первая запись могла выполниться, но подтверждение не пришло.
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const posts: Array<{ body: unknown; key: string | null }> = [];
    const urls: string[] = [];
    let postCount = 0;
    let finishFirst!: (response: Response) => void;
    const saved = capsule({ name: "Набор", itemIds: [1] });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      urls.push(url);
      if (url.startsWith("/api/wardrobe/items?page=")) return ok([item(1)]);
      if (url.endsWith("/photos")) return ok([]);
      if (url === "/api/me/capsules" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        return ++postCount === 1 ? new Promise<Response>((resolve) => { finishFirst = resolve; }) : ok(saved, 201);
      }
      if (url === "/api/me/capsules/5") return ok(saved);
      if (url === "/api/wardrobe/items/1") return ok(item(1));
      return fail(404);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const switched = vi.fn();
    function Harness() {
      const [pending, setPending] = React.useState(false);
      return <AppRoutes account={user} pending={pending} onPending={setPending} onChoose={vi.fn()} onSwitch={switched} />;
    }
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/capsules/new"]}><Harness /></MemoryRouter></QueryClientProvider>);
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "  Набор  " } });
    fireEvent.click(await screen.findByLabelText("Выбрать: Вещь 1"));

    // when: retry the same frozen draft after the ambiguous response.
    fireEvent.click(screen.getByRole("button", { name: /Сохранить капсулу/ }));
    await waitFor(() => expect(finishFirst).toBeDefined());
    expect((screen.getByRole("button", { name: "Сменить аккаунт" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("link", { name: "Капсулы ↗" }));
    expect(screen.getByRole("heading", { name: "Новая капсула" })).toBeTruthy();
    expect(switched).not.toHaveBeenCalled();
    await act(async () => { finishFirst(fail(503)); });
    await screen.findByRole("button", { name: "Новая попытка" });
    expect((screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ") as HTMLInputElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: /Повторить тот же запрос/ }));

    // then: unchanged payload/key reaches the API and success opens the saved detail.
    expect(await screen.findByRole("heading", { name: "Набор" })).toBeTruthy();
    expect(posts).toHaveLength(2);
    expect(posts[0]).toEqual(posts[1]);
    expect(posts[0].body).toEqual({ name: "Набор", itemIds: [1] });
    expect(posts[0].key).toBeTruthy();
    expect(urls.some((url) => url.includes("weather"))).toBe(false);
  });

  it("сохраняет снимок неизвестной попытки до явного NewAttempt после восстановления гардероба", async () => {
    // given
    const posts: Array<{ body: unknown; key: string | null }> = [];
    let wardrobeReads = 0;
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const saved = capsule({ name: "Набор", itemIds: [1] });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith("/api/wardrobe/items?page=")) {
        wardrobeReads += 1;
        if (wardrobeReads === 2) return fail(503);
        return ok(wardrobeReads < 2 ? [item(1), item(2)] : [item(1)]);
      }
      if (url === "/api/me/capsules" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        return posts.length < 3 ? fail(503) : ok(saved, 201);
      }
      if (url === "/api/me/capsules/5") return ok(saved);
      if (url.endsWith("/photos")) return ok([]);
      return fail(404);
    }));
    const { client } = mount();
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "Набор" } });
    fireEvent.click(await screen.findByLabelText("Выбрать: Вещь 1"));
    fireEvent.click(screen.getByLabelText("Выбрать: Вещь 2"));
    fireEvent.click(screen.getByRole("button", { name: /Сохранить капсулу/ }));
    await screen.findByRole("button", { name: "Новая попытка" });

    // when: recovery removes item 2 from current wardrobe data only.
    await act(async () => { await client.refetchQueries({ queryKey: ["capsule-items", user.id], exact: true }); });
    await waitFor(() => expect(wardrobeReads).toBe(2));
    fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
    await screen.findByRole("button", { name: "Новая попытка" });
    expect(screen.getByText(/Некоторых выбранных вещей больше нет/)).toBeTruthy();
    expect(screen.queryByLabelText("Выбрать: Вещь 2")).toBeNull();

    // Retry keeps the exact frozen request until NewAttempt is confirmed.
    fireEvent.click(screen.getByRole("button", { name: /Повторить тот же запрос/ }));
    await waitFor(() => expect(posts).toHaveLength(2));

    // then
    expect(posts[1]).toEqual(posts[0]);

    // when
    fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
    expect(await screen.findByLabelText("Выбрать: Вещь 1")).toBeTruthy();
    expect(screen.queryByLabelText("Выбрать: Вещь 2")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Сохранить капсулу/ }));
    await screen.findByRole("heading", { name: "Набор" });

    // then
    expect(posts).toHaveLength(3);
    expect(posts[2].body).toEqual({ name: "Набор", itemIds: [1] });
    expect(posts[2].key).not.toEqual(posts[0].key);
  });

  it("после конфликта требует свежий гардероб и явное подтверждение новой попытки", async () => {
    // given
    const posts: Array<{ body: unknown; key: string | null }> = [];
    let wardrobeReads = 0;
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith("/api/wardrobe/items?page=")) {
        wardrobeReads += 1;
        if (wardrobeReads === 2 || wardrobeReads === 4) return fail(503);
        return ok(wardrobeReads === 1 ? [item(1), item(2)] : [item(2)]);
      }
      if (url === "/api/me/capsules" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        return posts.length === 1 ? fail(409) : ok(capsule({ name: "Набор", itemIds: [1] }), 201);
      }
      if (url === "/api/me/capsules/5") return ok(capsule({ name: "Набор" }));
      if (url === "/api/wardrobe/items/1") return ok(item(1));
      if (url.endsWith("/photos")) return ok([]);
      return fail(404);
    }));
    const { client } = mount();
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "  Набор  " } });
    fireEvent.click(await screen.findByLabelText("Выбрать: Вещь 1"));
    fireEvent.click(screen.getByLabelText("Выбрать: Вещь 2"));
    fireEvent.click(screen.getByRole("button", { name: /Сохранить капсулу/ }));
    await screen.findByRole("button", { name: "Обновить гардероб" });

    // when: a fresh read fails, the old selection stays hidden and no retry can bypass the read gate.
    expect((screen.getByRole("button", { name: /Повторить тот же запрос/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Обновить гардероб" }));
    await screen.findByText(/Не удалось обновить гардероб/);
    expect(screen.queryByLabelText("Выбрать: Вещь 1")).toBeNull();
    expect(screen.queryByRole("button", { name: "Новая попытка" })).toBeNull();
    expect(posts).toHaveLength(1);

    // A later successful read permits NewAttempt, which alone unlocks the saved draft.
    fireEvent.click(screen.getByRole("button", { name: "Обновить гардероб" }));
    await screen.findByRole("button", { name: "Гардероб обновлён" });
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ").closest("form")!);
    expect(posts).toHaveLength(1);

    // when: a background read fails after the explicit conflict refresh.
    await act(async () => { await client.refetchQueries({ queryKey: ["capsule-items", user.id], exact: true }); });

    // then: another explicit refresh is available without discarding the draft.
    await waitFor(() => expect((screen.getByRole("button", { name: "Новая попытка" }) as HTMLButtonElement).disabled).toBe(true));
    expect((screen.getByRole("button", { name: "Обновить гардероб" }) as HTMLButtonElement).disabled).toBe(false);
    expect(posts).toHaveLength(1);

    // when
    fireEvent.click(screen.getByRole("button", { name: "Обновить гардероб" }));
    await screen.findByRole("button", { name: "Гардероб обновлён" });
    fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
    expect(confirm).toHaveBeenCalled();
    expect(await screen.findByLabelText("Выбрать: Вещь 2")).toBeTruthy();
    expect(screen.queryByLabelText("Выбрать: Вещь 1")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Сохранить капсулу/ }));

    // then: name and selection were kept through reload; the confirmed attempt gets a new key.
    expect(await screen.findByRole("heading", { name: "Набор" })).toBeTruthy();
    expect(posts).toHaveLength(2);
    expect(posts[0].body).toEqual({ name: "Набор", itemIds: [1, 2] });
    expect(posts[1].body).toEqual({ name: "Набор", itemIds: [2] });
    expect(posts[1].key).not.toEqual(posts[0].key);
  });

  it("не возвращает капсулу в кэш после завершившегося удаления и позднего GET", async () => {
    // given
    let finishDelete!: (response: Response) => void;
    let finishRead!: (response: Response) => void;
    let finishListRead!: (rows: Capsule[]) => void;
    let holdNextRead = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/me/capsules/5" && init?.method === "DELETE") {
        return new Promise<Response>((resolve) => { finishDelete = resolve; });
      }
      if (url === "/api/me/capsules/5" && holdNextRead) {
        holdNextRead = false;
        return new Promise<Response>((resolve) => { finishRead = resolve; });
      }
      if (url === "/api/me/capsules/5") return ok(capsule());
      if (url === "/api/wardrobe/items/1") return ok(item(1));
      if (url.endsWith("/photos")) return ok([]);
      if (url.startsWith("/api/me/capsules?page=")) return ok([]);
      return fail(404);
    }));
    const { client } = mount("/capsules/5");
    await screen.findByRole("heading", { name: "Поездка" });
    const listKey = ["capsules", user.id, 0] as const;
    client.setQueryData(listKey, [capsule()]);

    // when: a detail read overlaps the pending DELETE and resolves only after its success.
    fireEvent.click(screen.getByRole("button", { name: "Удалить капсулу" }));
    fireEvent.click(screen.getByRole("button", { name: "Да, удалить" }));
    await waitFor(() => expect(finishDelete).toBeDefined());
    holdNextRead = true;
    let staleRead!: Promise<void>;
    await act(async () => {
      staleRead = client.refetchQueries({ queryKey: ["capsule", user.id, 5], exact: true });
      await waitFor(() => expect(finishRead).toBeDefined());
    });
    const staleListRead = client.fetchQuery({ queryKey: listKey, staleTime: 0, queryFn: () => new Promise<Capsule[]>((resolve) => { finishListRead = resolve; }) });
    await waitFor(() => expect(finishListRead).toBeDefined());
    await act(async () => { finishDelete(ok(null, 204)); });
    await screen.findByRole("heading", { name: "Капсулы" });
    await act(async () => { finishRead(ok(capsule())); finishListRead([capsule()]); await Promise.all([staleRead, staleListRead]); });

    // then
    expect(client.getQueryData(["capsule", user.id, 5])).toBeUndefined();
    expect(client.getQueryData(listKey)).toEqual([]);
  });

  it("отменяет удаление без запроса и удаляет подтверждённую капсулу, сохраняя вещи", async () => {
    // given
    const calls: Array<{ url: string; method: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, method: init?.method ?? "GET" });
      if (url === "/api/me/capsules/5" && init?.method === "DELETE") return ok(null, 204);
      if (url === "/api/me/capsules/5") return ok(capsule());
      if (url === "/api/wardrobe/items/1") return ok(item(1));
      if (url.endsWith("/photos")) return ok([]);
      if (url.startsWith("/api/me/capsules?page=")) return ok([]);
      return fail(404);
    }));
    mount("/capsules/5");
    await screen.findByRole("heading", { name: "Поездка" });

    // when: cancel first, then confirm a second time.
    fireEvent.click(screen.getByRole("button", { name: "Удалить капсулу" }));
    fireEvent.click(screen.getByRole("button", { name: "Отмена" }));
    expect(calls.some((call) => call.method === "DELETE")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Удалить капсулу" }));
    fireEvent.click(screen.getByRole("button", { name: "Да, удалить" }));

    // then
    await screen.findByRole("heading", { name: "Капсулы" });
    expect(calls.some((call) => call.url === "/api/me/capsules/5" && call.method === "DELETE")).toBe(true);
    expect(calls.some((call) => call.url === "/api/wardrobe/items/1" && call.method === "DELETE")).toBe(false);
  });

  it("ограничивает капсулу двадцатью вещами и сохраняет предел пятьдесят по умолчанию", async () => {
    // given
    const wardrobe = Array.from({ length: 21 }, (_, index) => item(index + 1));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input).startsWith("/api/wardrobe/items?page=") ? ok(wardrobe) : ok([])));
    mount();
    const first = await screen.findByLabelText("Выбрать: Вещь 1");
    expect(screen.getByText("Отметьте от одной до 20 вещей.")).toBeTruthy();
    for (let id = 1; id <= 12; id += 1) fireEvent.click(screen.getByLabelText(`Выбрать: Вещь ${id}`));
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    for (let id = 13; id <= 20; id += 1) fireEvent.click(screen.getByLabelText(`Выбрать: Вещь ${id}`));

    // when / then
    expect((screen.getByLabelText("Выбрать: Вещь 21") as HTMLInputElement).disabled).toBe(true);
    expect(first).toBeTruthy();
    cleanup();
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter><WardrobeItemPicker actor={user} context={{ kind: "own" }} items={wardrobe} selected={[]}
        onSelectedChange={vi.fn()} disabled={false} /></MemoryRouter></QueryClientProvider>);
    expect(screen.getByText("Отметьте от одной до 50 вещей.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    expect((screen.getByLabelText("Выбрать: Вещь 21") as HTMLInputElement).disabled).toBe(false);
  });

  it("подписывает AI и ручные записи, а сохранённая AI-капсула переживает ошибку справочника", async () => {
    // given
    const ai = capsule({ source: "AI", weather: { temperatureC: 4.5, windSpeedMps: 2, precipitationTypeId: 77 }, occasion: "Поездка", preferredStyle: "Спокойный", explanation: "Текст <script>безопасен</script>." });
    const manual = capsule({ id: 6, name: "Ручная", source: undefined, weather: undefined, explanation: undefined });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/me/capsules?page=")) return ok([ai, manual]);
      if (url === "/api/me/capsules/5") return ok(ai);
      if (url === "/api/wardrobe/items/1") return ok(item(1));
      if (url.startsWith("/api/weather/precipitation-types?")) return fail(503);
      if (url.endsWith("/photos")) return ok([]);
      return fail(404);
    }));

    // when: read the list, then open the AI detail while its weather lookup is unavailable.
    mount("/capsules");

    // then
    expect(await screen.findByText("Подбор с ИИ")).toBeTruthy();
    expect(screen.getByText("Ручной набор")).toBeTruthy();
    cleanup();
    mount("/capsules/5");
    expect(await screen.findByText("Текст <script>безопасен</script>.")).toBeTruthy();
    expect(screen.getByText("4.5 °C · ветер 2 м/с · осадки: ID 77")).toBeTruthy();
    expect(screen.getByText("Повод: Поездка")).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("не показывает капсулы стилисту и оставляет обычный маршрут приложения", async () => {
    // given
    vi.stubGlobal("fetch", vi.fn(async () => ok([])));
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/capsules"]}><AppRoutes account={stylist} pending={false} onPending={vi.fn()} onChoose={vi.fn()} onSwitch={vi.fn()} /></MemoryRouter>
    </QueryClientProvider>);

    // when / then
    await waitFor(() => expect(screen.queryByRole("link", { name: "Капсулы ↗" })).toBeNull());
    expect(screen.queryByRole("heading", { name: "Капсулы" })).toBeNull();
  });
});
