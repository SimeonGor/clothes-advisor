import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Actor, Capsule, WardrobeItem } from "./api";
import { AiCapsuleEditor } from "./ai-capsule";
import { CapsuleDetail } from "./capsules";

const user: Actor = { id: 11, login: "user", role: "USER" };
const item = (id: number): WardrobeItem => ({ id, name: `Вещь ${id}`, categoryId: 1, color: "оливковый", material: "хлопок", version: 1, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-01T00:00:00Z", availability: "AVAILABLE" });
const unavailableItem = (id: number, availability: "IN_LAUNDRY" | "STORED"): WardrobeItem => ({ ...item(id), availability });
const capsule = (extra: Partial<Capsule> = {}): Capsule => ({ id: 5, ownerId: user.id, name: "На каждый день", itemIds: [1], createdAt: "2026-01-01T00:00:00Z", source: "AI", weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 1 }, explanation: "Сочетание подходит для прохладной погоды.", ...extra });
const ok = (data: unknown, status = 200) => ({ ok: true, status, headers: new Headers(), json: async () => data } as Response);
const fail = (status: number, error?: string) => ({ ok: false, status, headers: new Headers(), json: async () => error ? { error } : {} } as Response);

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onPending = vi.fn();
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/capsules/ai"]}><Routes>
    <Route path="/capsules/ai" element={<AiCapsuleEditor actor={user} onPending={onPending} pending={false} />} />
    <Route path="/capsules/:capsuleId" element={<CapsuleDetail actor={user} onPending={onPending} pending={false} />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { ...view, client, onPending };
}

beforeEach(() => { sessionStorage.clear(); vi.stubGlobal("fetch", vi.fn()); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("AI-капсулы", () => {
  it("показывает для ручного выбора только доступные вещи", async () => {
    // given
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.startsWith("/api/weather/precipitation-types?")) return ok([{ id: 1, name: "Дождь", code: "RAIN" }]);
      if (path.startsWith("/api/wardrobe/items?page=")) return ok([item(1), unavailableItem(2, "IN_LAUNDRY"), unavailableItem(3, "STORED")]);
      if (path.endsWith("/photos")) return ok([]);
      return fail(404);
    }));
    mount();
    await screen.findByRole("option", { name: "Дождь" });
    fireEvent.click(screen.getByRole("checkbox", { name: /Выбрать вещи-кандидаты вручную/ }));

    // when
    const availableChoice = await screen.findByLabelText("Выбрать: Вещь 1");

    // then
    expect(availableChoice).toBeTruthy();
    expect(screen.queryByLabelText("Выбрать: Вещь 2")).toBeNull();
    expect(screen.queryByLabelText("Выбрать: Вещь 3")).toBeNull();
  });

  it("не отправляет форму повторно, пока справочник осадков загружается по кнопке повтора", async () => {
    // given
    let finishRetry!: (response: Response) => void;
    let weatherReads = 0;
    const posts: unknown[] = [];
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.startsWith("/api/weather/precipitation-types?")) {
        weatherReads += 1;
        if (weatherReads === 1) return Promise.resolve(fail(500));
        return new Promise<Response>((resolve) => { finishRetry = resolve; });
      }
      if (path.startsWith("/api/wardrobe/items?page=")) return Promise.resolve(ok([item(1)]));
      if (path === "/api/me/capsules/ai" && init?.method === "POST") {
        posts.push(JSON.parse(String(init.body)));
        return Promise.resolve(ok(capsule(), 201));
      }
      if (path.endsWith("/photos")) return Promise.resolve(ok([]));
      return Promise.resolve(fail(404));
    }));
    const { onPending } = mount();
    await screen.findByRole("button", { name: "Повторить загрузку" });
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "На каждый день" } });
    fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));

    // when
    await waitFor(() => expect(weatherReads).toBe(2));
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ").closest("form")!);

    // then
    expect(posts).toHaveLength(0);
    expect(onPending).toHaveBeenCalledWith(true);
    expect((screen.getByRole("button", { name: /Создать AI-капсулу/ }) as HTMLButtonElement).disabled).toBe(true);

    // when: the lookup completes, submission becomes available again.
    await act(async () => finishRetry(ok([{ id: 1, name: "Дождь", code: "RAIN" }])));
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));

    // then
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(onPending).toHaveBeenCalledWith(false);
  });

  it("отправляет весь гардероб без candidateItemIds и открывает сохранённое объяснение", async () => {
    // given
    const posts: Array<{ body: unknown; key: string | null }> = [];
    const paths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      paths.push(path);
      if (path.startsWith("/api/weather/precipitation-types?")) return ok([{ id: 1, name: "Дождь", code: "RAIN" }]);
      if (path.startsWith("/api/wardrobe/items?page=")) return ok([item(1)]);
      if (path === "/api/me/capsules/ai" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        return ok(capsule(), 201);
      }
      if (path === "/api/me/capsules/5") return ok(capsule());
      if (path === "/api/wardrobe/items/1") return ok(item(1));
      if (path.endsWith("/photos")) return ok([]);
      if (path.startsWith("/api/weather/precipitation-types")) return ok([{ id: 1, name: "Дождь", code: "RAIN" }]);
      return fail(404);
    }));
    mount();
    await screen.findByRole("option", { name: "Дождь" });
    await screen.findByText("Весь гардероб");
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "  На каждый день  " } });
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "1" } });
    expect(screen.getByRole("link", { name: /предпочтения профиля/i })).toBeTruthy();

    // when
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));

    // then
    expect(await screen.findByText("Сочетание подходит для прохладной погоды.")).toBeTruthy();
    expect(posts).toHaveLength(1);
    expect(posts[0].body).toEqual({ name: "На каждый день", weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 1 } });
    expect(posts[0].key).toBeTruthy();
    expect(paths.some((path) => path.includes("preferences"))).toBe(false);
  });

  it("повторяет прежний снимок и ключ после обновления справочника и гардероба", async () => {
    // given
    const posts: Array<{ body: unknown; key: string | null }> = [];
    let reads = 0;
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.startsWith("/api/weather/precipitation-types?")) return ok([{ id: reads ? 2 : 1, name: reads ? "Снег" : "Дождь", code: "RAIN" }]);
      if (path.startsWith("/api/wardrobe/items?page=")) return ok([item(reads ? 2 : 1)]);
      if (path === "/api/me/capsules/ai" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        return posts.length === 1 ? fail(503) : ok(capsule({ itemIds: [1] }), 201);
      }
      if (path === "/api/me/capsules/5") return ok(capsule());
      if (path === "/api/wardrobe/items/1") return ok(item(1));
      if (path.endsWith("/photos")) return ok([]);
      return fail(404);
    }));
    const { client } = mount();
    await screen.findByRole("option", { name: "Дождь" });
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "На каждый день" } });
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "1" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /Выбрать вещи-кандидаты вручную/ }));
    fireEvent.click(await screen.findByLabelText("Выбрать: Вещь 1"));

    // when: the ambiguous response is followed by changed lookups and an explicit retry.
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));
    await screen.findByRole("button", { name: "Новая попытка" });
    await act(async () => {
      reads += 1;
      await client.refetchQueries({ queryKey: ["precipitation", user.id], exact: true });
      await client.refetchQueries({ queryKey: ["capsule-ai-items", user.id], exact: true });
    });
    fireEvent.click(screen.getByRole("button", { name: /Повторить ту же попытку/ }));

    // then
    expect(await screen.findByText("Сочетание подходит для прохладной погоды.")).toBeTruthy();
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect(posts[0].body).toEqual({ name: "На каждый день", weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 1 }, candidateItemIds: [1] });
  });

  it.each([502, 503, 504])("после HTTP %s сохраняет ключ и запускает новую команду только с подтверждением", async (status) => {
    // given
    const posts: Array<{ body: unknown; key: string | null }> = [];
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.startsWith("/api/weather/precipitation-types?")) return ok([{ id: 1, name: "Дождь", code: "RAIN" }]);
      if (path.startsWith("/api/wardrobe/items?page=")) return ok([item(1)]);
      if (path === "/api/me/capsules/ai" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        if (posts.length === 1) return fail(status);
        if (posts.length === 2) return fail(409, "idempotency_failed");
        return ok(capsule(), 201);
      }
      if (path === "/api/me/capsules/5") return ok(capsule());
      if (path === "/api/wardrobe/items/1") return ok(item(1));
      if (path.endsWith("/photos")) return ok([]);
      return fail(404);
    }));
    mount();
    await screen.findByRole("option", { name: "Дождь" });
    await screen.findByText("Весь гардероб");
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "На каждый день" } });
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "1" } });

    // when: a gateway or provider error leaves the command outcome uncertain.
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));
    const alert = await screen.findByRole("alert");

    // then
    expect(alert.textContent).toContain("Проверьте сохранённые капсулы: действие могло выполниться");
    expect(alert.textContent).toContain("Повтор с тем же ключом не запускает уже принятую команду заново");
    expect(alert.textContent).not.toContain("позже");
    expect(posts).toHaveLength(1);

    // when: the server confirms that this key belongs to a failed command.
    fireEvent.click(screen.getByRole("button", { name: /Повторить ту же попытку/ }));
    await screen.findByText(/Предыдущая попытка завершилась ошибкой.*Для нового запуска выберите «Новая попытка»/);
    fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));

    // then: rejecting the confirmation preserves the draft and the retry state.
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect((screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ") as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByRole("button", { name: /Повторить ту же попытку/ })).toBeTruthy();

    // when
    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));

    // then
    expect(await screen.findByText("Сочетание подходит для прохладной погоды.")).toBeTruthy();
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(confirm.mock.calls[0][0]).toMatch(/дубликаты.*лимит AI/);
    expect(posts).toHaveLength(3);
    expect(posts[2].body).toEqual(posts[0].body);
    expect(posts[2].key).not.toBe(posts[0].key);
  });

  it("после конфликта запрещает обход refresh и только NewAttempt меняет тело", async () => {
    // given
    const posts: Array<{ body: unknown; key: string | null }> = [];
    let wardrobeReads = 0;
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.startsWith("/api/weather/precipitation-types?")) return ok([{ id: 1, name: "Дождь", code: "RAIN" }]);
      if (path.startsWith("/api/wardrobe/items?page=")) {
        wardrobeReads += 1;
        return wardrobeReads === 3 ? fail(503) : ok([item(wardrobeReads === 1 ? 1 : 2)]);
      }
      if (path === "/api/me/capsules/ai" && init?.method === "POST") {
        posts.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get("Idempotency-Key") });
        return posts.length === 1 ? fail(409) : ok(capsule({ itemIds: [2] }), 201);
      }
      if (path === "/api/me/capsules/5") return ok(capsule({ itemIds: [2] }));
      if (path === "/api/wardrobe/items/2") return ok(item(2));
      if (path.endsWith("/photos")) return ok([]);
      return fail(404);
    }));
    const { client } = mount();
    await screen.findByRole("option", { name: "Дождь" });
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ"), { target: { value: "На каждый день" } });
    fireEvent.change(screen.getByLabelText("ОСАДКИ"), { target: { value: "1" } });
    // when
    fireEvent.click(screen.getByRole("checkbox", { name: /Выбрать вещи-кандидаты вручную/ }));
    fireEvent.click(await screen.findByLabelText("Выбрать: Вещь 1"));
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));
    await screen.findByRole("button", { name: "Обновить гардероб" });
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ КАПСУЛЫ").closest("form")!);
    expect(posts).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Обновить гардероб" }));
    await screen.findByRole("button", { name: "Гардероб обновлён" });

    // when: a background read fails after the required refresh succeeded.
    await act(async () => { await client.refetchQueries({ queryKey: ["capsule-ai-items", user.id], exact: true }); });

    // then: the draft can still recover through an explicit read.
    await waitFor(() => expect((screen.getByRole("button", { name: "Новая попытка" }) as HTMLButtonElement).disabled).toBe(true));
    expect((screen.getByRole("button", { name: "Обновить гардероб" }) as HTMLButtonElement).disabled).toBe(false);
    expect(posts).toHaveLength(1);

    // when
    fireEvent.click(screen.getByRole("button", { name: "Обновить гардероб" }));
    await screen.findByRole("button", { name: "Гардероб обновлён" });
    fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
    expect(confirm).toHaveBeenCalled();
    expect(await screen.findByLabelText("Выбрать: Вещь 2")).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Выбрать: Вещь 2"));
    fireEvent.click(screen.getByRole("button", { name: /Создать AI-капсулу/ }));

    // then
    expect(await screen.findByText("Сочетание подходит для прохладной погоды.")).toBeTruthy();
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[0].body).toEqual({ name: "На каждый день", weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 1 }, candidateItemIds: [1] });
    expect(posts[1].body).toEqual({ name: "На каждый день", weather: { temperatureC: 15, windSpeedMps: 0, precipitationTypeId: 1 }, candidateItemIds: [2] });
    expect(posts[0].key).not.toEqual(posts[1].key);
  });
});
