import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Actor, Outfit, WardrobeItem } from "./api";
import { OutfitDetail } from "./outfits";

const user: Actor = { id: 11, login: "user", role: "USER" };
const item = (id: number, availability?: WardrobeItem["availability"]): WardrobeItem => ({ id, name: `Вещь ${id}`, categoryId: 1, color: "оливковый", material: "хлопок", version: 1, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-01T00:00:00Z", availability });
const outfit = (id = 5): Outfit => ({ id, ownerId: user.id, authorId: user.id, source: "USER", name: "Городской образ", itemIds: [1], likes: 0, dislikes: 0, createdAt: "2026-01-01T00:00:00Z", weather: { temperatureC: 12, windSpeedMps: 2, precipitationTypeId: 7 } });
const ok = (data: unknown, status = 200) => ({ ok: true, status, headers: new Headers(), json: async () => data } as Response);
const fail = (status: number) => ({ ok: false, status, headers: new Headers(), json: async () => ({}) } as Response);

function mount(actor: Actor = user, context: { kind: "own" } | { kind: "client" | "admin"; ownerId: number } = { kind: "own" }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function DetailHarness() {
    const [pending, setPending] = React.useState(false);
    return <OutfitDetail actor={actor} context={context} onPending={setPending} pending={pending} />;
  }
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/outfits/5"]}><Routes>
    <Route path="/outfits/:outfitId" element={<DetailHarness />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { ...view, client };
}

function readRequest(input: RequestInfo | URL, init?: RequestInit) {
  return { url: String(input), init };
}

beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Замена вещей через AI", () => {
  it("показывает форму только USER в собственном контексте", async () => {
    // given: загружается собственный USER-образ.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/outfits/5")) return ok(outfit());
      if (url.endsWith("/wardrobe/items/1")) return ok(item(1));
      return ok([]);
    }));
    mount();

    // when: карточка загружена.
    await screen.findByRole("heading", { name: "Заменить вещи через AI" });

    // then: форма видна только в собственном контексте USER.
    expect(screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА")).toBeTruthy();
    cleanup();
    mount({ ...user, role: "STYLIST" });
    await screen.findByRole("heading", { name: "Городской образ" });
    expect(screen.queryByRole("heading", { name: "Заменить вещи через AI" })).toBeNull();
    cleanup();
    mount(user, { kind: "client", ownerId: 77 });
    await screen.findByRole("heading", { name: "Городской образ" });
    expect(screen.queryByRole("heading", { name: "Заменить вещи через AI" })).toBeNull();
  });

  it("сериализует замену, не допускает двойную отправку и повторяет неизвестный результат с тем же ключом", async () => {
    // given: созданное действие сначала завершается ошибкой подтверждения.
    const calls: ReturnType<typeof readRequest>[] = [];
    let rejectCreate!: (error: Error) => void;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const call = readRequest(input, init); calls.push(call);
      if (call.url.endsWith("/outfits/5")) return ok(outfit());
      if (call.url.endsWith("/outfits/9")) return ok(outfit(9));
      if (call.url.endsWith("/wardrobe/items/1")) return ok(item(1));
      if (call.url === "/api/outfits/5/ai-replacements") {
        if (calls.filter((entry) => entry.url === call.url).length === 1) return new Promise<Response>((_, reject) => { rejectCreate = reject; });
        return ok(outfit(9), 201);
      }
      return ok([]);
    }));
    mount();
    const replace = await screen.findByLabelText(/Вещь 1/);
    fireEvent.click(replace);
    const name = screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА");
    expect((name as HTMLInputElement).value).toBe("Городской образ");

    // when: пользователь отправляет форму дважды, затем повторяет её после неизвестного ответа.
    const form = name.closest("form")!;
    fireEvent.submit(form);
    await waitFor(() => expect((screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА") as HTMLInputElement).disabled).toBe(true));
    fireEvent.submit(form);
    await waitFor(() => expect(calls.filter((call) => call.url.endsWith("/ai-replacements"))).toHaveLength(1));
    rejectCreate(new TypeError("Network failure"));
    await screen.findByRole("button", { name: "Новая попытка" });
    expect((name as HTMLInputElement).value).toBe("Городской образ");
    fireEvent.submit(form);

    // then: только одна новая попытка с тем же содержимым/ключом открывает новую карточку.
    await waitFor(() => expect(calls.some((call) => call.url === "/api/outfits/9")).toBe(true));
    const posts = calls.filter((call) => call.url === "/api/outfits/5/ai-replacements");
    expect(posts).toHaveLength(2);
    expect(JSON.parse(String(posts[0].init?.body))).toEqual({ name: "Городской образ", replaceItemIds: [1] });
    expect(new Headers(posts[1].init?.headers).get("Idempotency-Key")).toBe(new Headers(posts[0].init?.headers).get("Idempotency-Key"));
    expect(new Headers(posts[1].init?.headers).get("Idempotency-Key")).toBeTruthy();
    expect(calls.some((call) => call.url === "/api/outfits/9")).toBe(true);
    expect(posts.every((call) => call.init?.method === "POST")).toBe(true);
  });

  it("сохраняет ручной выбор после ошибки загрузки и отказа сервера", async () => {
    // given
    let catalogAvailable = false;
    let posts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/outfits/5")) return ok(outfit());
      if (url.endsWith("/wardrobe/items/1")) return ok(item(1));
      if (url.startsWith("/api/wardrobe/items?page=")) return catalogAvailable ? ok([item(2)]) : fail(503);
      if (url.endsWith("/ai-replacements")) { posts++; return fail(400); }
      return ok([]);
    }));
    mount();
    fireEvent.click(await screen.findByLabelText(/Вещь 1/));

    // when
    fireEvent.click(screen.getByLabelText("Выбрать вещи-кандидаты вручную"));
    const loadFailure = await screen.findByRole("alert");

    // then
    expect(loadFailure.textContent).toContain("Повторите загрузку позже");
    expect(loadFailure.textContent).not.toContain("AI");
    expect(posts).toBe(0);

    // when
    catalogAvailable = true;
    fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
    fireEvent.click(await screen.findByLabelText(/Вещь 2/));
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА").closest("form")!);

    // then
    expect((await screen.findByRole("alert")).textContent).toContain("20 вещей с фото");
    expect((screen.getByLabelText(/Вещь 1/) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/Вещь 2/) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("Выбрать вещи-кандидаты вручную") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА") as HTMLInputElement).value).toBe("Городской образ");
    expect(posts).toBe(1);
  });

  it("в ручном режиме исключает исходные вещи и отправляет только выбранных кандидатов", async () => {
    // given: в гардеробе есть исходная вещь и одна вещь вне образа.
    const calls: ReturnType<typeof readRequest>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const call = readRequest(input, init); calls.push(call);
      if (call.url.endsWith("/outfits/5")) return ok(outfit());
      if (call.url.endsWith("/wardrobe/items/1")) return ok(item(1));
      if (call.url.startsWith("/api/wardrobe/items?page=")) return ok([item(1), item(2)]);
      if (call.url === "/api/outfits/5/ai-replacements") return ok(outfit(9), 201);
      if (call.url === "/api/outfits/9") return ok(outfit(9));
      return ok([]);
    }));
    mount();
    fireEvent.click(await screen.findByLabelText(/Вещь 1/));

    // when: ручной режим открыт, но кандидат ещё не выбран; затем отмечается вещь вне образа.
    fireEvent.click(screen.getByLabelText("Выбрать вещи-кандидаты вручную"));
    await screen.findByLabelText(/Вещь 2/);
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА").closest("form")!);
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(calls.some((call) => call.url.endsWith("/ai-replacements"))).toBe(false);
    fireEvent.click(screen.getByLabelText(/Вещь 2/));
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА").closest("form")!);
    await waitFor(() => expect(calls.some((call) => call.url.endsWith("/ai-replacements"))).toBe(true));

    // then: payload не содержит погоду/контекст, а список кандидатов не включает исходную вещь.
    const sent = calls.find((call) => call.url.endsWith("/ai-replacements"));
    expect(JSON.parse(String(sent?.init?.body))).toEqual({ name: "Городской образ", replaceItemIds: [1], candidateItemIds: [2] });
  });

  it("требует отметить недоступную исходную вещь и не отправляет выбор до этого", async () => {
    // given: сохранённый образ содержит вещь, убранную на хранение.
    let posts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/outfits/5")) return ok(outfit());
      if (url.endsWith("/wardrobe/items/1")) return ok(item(1, "STORED"));
      if (url.endsWith("/ai-replacements")) { posts += 1; return ok(outfit(9), 201); }
      if (url.endsWith("/outfits/9")) return ok(outfit(9));
      return ok([]);
    }));
    mount();
    const form = (await screen.findByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА")).closest("form")!;

    // when: форма отправляется без явного указания заменить исходную вещь.
    fireEvent.submit(form);

    // then: POST блокируется до явного решения пользователя.
    expect((await screen.findByRole("alert")).textContent).toContain("Отметьте недоступные вещи");
    expect(posts).toBe(0);

    // when: пользователь явно отмечает эту вещь к замене.
    fireEvent.click(screen.getByLabelText(/Вещь 1/));
    fireEvent.submit(form);

    // then: отправка использует отмеченный ID.
    await waitFor(() => expect(posts).toBe(1));
  });

  it("блокирует выбранного кандидата после обновления статуса и позволяет снять выбор", async () => {
    // given: ручной кандидат выбран, затем его состояние становится недоступным.
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/outfits/5")) return ok(outfit());
      if (url.endsWith("/wardrobe/items/1")) return ok(item(1));
      if (url.startsWith("/api/wardrobe/items?page=")) return ok([item(1), item(2), item(3, "STORED")]);
      if (url.endsWith("/ai-replacements")) return ok(outfit(9), 201);
      if (url.endsWith("/outfits/9")) return ok(outfit(9));
      return ok([]);
    });
    vi.stubGlobal("fetch", fetchMock);
    const { client } = mount();
    fireEvent.click(await screen.findByLabelText(/Вещь 1/));
    fireEvent.click(screen.getByLabelText("Выбрать вещи-кандидаты вручную"));
    const chosen = await screen.findByLabelText(/Вещь 2/) as HTMLInputElement;
    const initiallyUnavailable = await screen.findByLabelText(/Вещь 3/) as HTMLInputElement;
    expect(initiallyUnavailable.disabled).toBe(true);
    fireEvent.click(chosen);
    await act(async () => client.setQueryData(["outfit-replacement-candidates", user.id], [item(1), item(2, "IN_LAUNDRY"), item(3, "STORED")]));
    await waitFor(() => expect(screen.getByLabelText(/Вещь 2/).closest("label")?.textContent).toContain("В стирке"));
    expect((screen.getByLabelText(/Вещь 2/) as HTMLInputElement).checked).toBe(true);

    // when: форма пытается отправить недоступного кандидата, а затем пользователь его снимает.
    const form = (screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА")).closest("form")!;
    fireEvent.submit(form);

    // then: POST блокируется, но сохранённый выбор можно явно снять.
    expect((await screen.findByRole("alert")).textContent).toContain("Снимите выбор");
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/ai-replacements"))).toBe(false);
    const retained = screen.getByLabelText(/Вещь 2/) as HTMLInputElement;
    expect(retained.checked).toBe(true);
    expect(retained.disabled).toBe(false);
    fireEvent.click(retained);
    expect((screen.getByLabelText(/Вещь 2/) as HTMLInputElement).checked).toBe(false);
  });

  it("игнорирует сохранённый ручной выбор недоступного кандидата после отключения ручного режима", async () => {
    // given: кандидат был выбран вручную, а форма переключена обратно в автоматический режим.
    const calls: ReturnType<typeof readRequest>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const call = readRequest(input, init); calls.push(call);
      if (call.url.endsWith("/outfits/5")) return ok(outfit());
      if (call.url.endsWith("/wardrobe/items/1")) return ok(item(1));
      if (call.url.startsWith("/api/wardrobe/items?page=")) return ok([item(1), item(2)]);
      if (call.url.endsWith("/ai-replacements")) return ok(outfit(9), 201);
      if (call.url.endsWith("/outfits/9")) return ok(outfit(9));
      return ok([]);
    }));
    const { client } = mount();
    fireEvent.click(await screen.findByLabelText(/Вещь 1/));
    fireEvent.click(screen.getByLabelText("Выбрать вещи-кандидаты вручную"));
    fireEvent.click(await screen.findByLabelText(/Вещь 2/));
    fireEvent.click(screen.getByLabelText("Выбрать вещи-кандидаты вручную"));
    await act(async () => client.setQueryData(["outfit-replacement-candidates", user.id], [item(1), item(2, "STORED")]));

    // when: automatic replacement is submitted despite the stale unused candidate.
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ НОВОГО ОБРАЗА").closest("form")!);

    // then: the request follows automatic mode and omits manual candidates.
    await waitFor(() => expect(calls.some((call) => call.url.endsWith("/ai-replacements"))).toBe(true));
    const sent = calls.find((call) => call.url.endsWith("/ai-replacements"));
    expect(JSON.parse(String(sent?.init?.body))).toEqual({ name: "Городской образ", replaceItemIds: [1] });
  });
});
