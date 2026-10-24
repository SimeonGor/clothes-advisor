import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Actor, Outfit, WardrobeItem } from "./api";
import { OutfitDetail, OutfitEditor, Outfits } from "./outfits";

const user: Actor = { id: 11, login: "user", role: "USER" };
const stylist: Actor = { id: 22, login: "stylist", role: "STYLIST" };
const admin: Actor = { id: 33, login: "admin", role: "ADMIN" };
const item = (id: number): WardrobeItem => ({ id, name: `Вещь ${id}`, categoryId: 1, color: "оливковый", material: "хлопок", version: 1, createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-01T00:00:00Z" });
const outfit = (extra: Partial<Outfit> = {}): Outfit => ({ id: 5, ownerId: 77, authorId: 99, source: "STYLIST", name: "Образ", itemIds: [], likes: 1, dislikes: 0, createdAt: "2026-01-01T00:00:00Z", weather: { temperatureC: -2.5, windSpeedMps: 1.25, precipitationTypeId: 7 }, ...extra });
const ok = (data: unknown, status = 200) => ({ ok: true, status, headers: new Headers(), json: async () => data } as Response);
const fail = (status: number) => ({ ok: false, status, headers: new Headers(), json: async () => ({}) } as Response);

function mount(node: React.ReactNode, path = "/outfits/new") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/outfits/new" element={node} />
    <Route path="/outfits/:outfitId" element={node} />
  </Routes></MemoryRouter></QueryClientProvider>);
}

beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Сохранённые образы", () => {
  it("показывает сохранённый контекст в карточке образа", async () => {
    // given
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/outfits/5") return ok(outfit({ occasion: "Работа", preferredStyle: "Свободный" }));
      if (url.startsWith("/api/weather/precipitation-types")) return ok([]);
      return fail(404);
    }));

    // when
    mount(<OutfitDetail actor={user} onPending={vi.fn()} pending={false} />, "/outfits/5");

    // then
    expect(await screen.findByText("Повод: Работа")).toBeTruthy();
    expect(screen.getByText("Стиль: Свободный")).toBeTruthy();
  });

  it("сериализует ручной образ и сохраняет значения после ошибки", async () => {
    // given: вещи и погода доступны, сохранение ожидает ответ.
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    let finishCreate!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, init });
      if (url.startsWith("/api/weather/precipitation-types")) return ok([{ id: 7, name: "Ясно", code: "CLEAR" }]);
      if (url.startsWith("/api/wardrobe/items?page=")) return ok([item(1), item(2)]);
      if (/\/wardrobe\/items\/\d+\/photos/.test(url)) return ok([]);
      if (url === "/api/outfits") return new Promise<Response>((resolve) => { finishCreate = resolve; });
      return fail(404);
    }));
    mount(<OutfitEditor actor={user} onPending={vi.fn()} pending={false} />);
    fireEvent.change(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА"), { target: { value: "  Городской день  " } });
    fireEvent.change(screen.getByLabelText("ТЕМПЕРАТУРА °C"), { target: { value: "-2.5" } });
    fireEvent.change(screen.getByLabelText("ВЕТЕР м/с"), { target: { value: "1.25" } });
    const precip = await screen.findByLabelText("ОСАДКИ") as HTMLSelectElement;
    await waitFor(() => expect(precip.disabled).toBe(false));
    fireEvent.change(precip, { target: { value: "7" } });
    fireEvent.click(await screen.findByLabelText("Выбрать: Вещь 1"));
    expect(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА").closest("form")?.checkValidity()).toBe(true);
    // when: форма отправляется дважды, сервер отвечает ошибкой.
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА").closest("form")!);
    fireEvent.submit(screen.getByLabelText("НАЗВАНИЕ ОБРАЗА").closest("form")!);
    await waitFor(() => expect(calls.filter((call) => call.url === "/api/outfits")).toHaveLength(1));
    finishCreate(fail(503));
    await screen.findByRole("alert");
    expect(screen.getByRole("alert").textContent).toContain("Проверьте данные перед повтором");
    expect(screen.getByRole("alert").textContent).not.toContain("AI");
    const sent = calls.find((call) => call.url === "/api/outfits");
    // then: запрос один, значения и дробная погода сохранены.
    expect(sent).toBeDefined();
    expect(JSON.parse(String(sent?.init?.body))).toEqual({ name: "Городской день", itemIds: [1], weather: { temperatureC: -2.5, windSpeedMps: 1.25, precipitationTypeId: 7 } });
    expect((screen.getByLabelText("НАЗВАНИЕ ОБРАЗА") as HTMLInputElement).value).toBe("  Городской день  ");
  });

  it("проводит рейтинг POST, PUT и DELETE с текущими версиями", async () => {
    // given: чужой образ без текущего голоса, новая версия начинается с двух.
    let saved = outfit(); let nextVersion = 2;
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push({ url, init });
      if (url === "/api/stylist/clients/77/outfits/5") return ok(saved);
      if (url.includes("ratings/history")) return ok([]);
      if (url.includes("/rating") && init?.method === "POST") { saved = { ...saved, myRating: { vote: "LIKE", version: nextVersion++ } }; return ok(saved.myRating, 201); }
      if (url.includes("/rating") && init?.method === "PUT") { saved = { ...saved, myRating: { vote: "DISLIKE", version: nextVersion++ } }; return ok(saved.myRating); }
      if (url.includes("/rating") && init?.method === "DELETE") { saved = { ...saved, myRating: null }; return ok(null, 204); }
      return fail(404);
    }));
    mount(<OutfitDetail actor={stylist} context={{ kind: "client", ownerId: 77 }} onPending={vi.fn()} pending={false} />, "/outfits/5");
    expect(screen.queryByRole("button", { name: "Удалить образ" })).toBeNull();
    // when: стилист ставит, меняет и отменяет голос.
    fireEvent.click(await screen.findByRole("button", { name: "Нравится" }));
    await waitFor(() => expect(saved.myRating?.vote).toBe("LIKE"));
    fireEvent.click(await screen.findByRole("button", { name: "Не нравится" }));
    await waitFor(() => expect(saved.myRating?.vote).toBe("DISLIKE"));
    fireEvent.click(await screen.findByRole("button", { name: "Отменить оценку" }));
    await waitFor(() => expect(saved.myRating).toBeNull());
    const writes = calls.filter((call) => call.url.includes("/rating") && !call.url.includes("ratings/history"));
    // then: каждый запрос использует текущую версию сервера.
    expect(writes.map(({ init }) => init?.method)).toEqual(["POST", "PUT", "DELETE"]);
    expect(JSON.parse(String(writes[0].init?.body))).toEqual({ vote: "LIKE" });
    expect(JSON.parse(String(writes[1].init?.body))).toEqual({ vote: "DISLIKE", version: 2 });
    expect(writes[2].url).toContain("?version=3");
  });

  it("различает собственные образы администратора и модерацию", async () => {
    // given: администратор может работать со своими образами.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/outfits/5")) return ok(outfit());
      if (url.includes("/outfits?page=")) return ok([]);
      return fail(404);
    }));
    mount(<Outfits actor={admin} pending={false} />);
    await screen.findByText("Пока нет сохранённых образов");
    expect(screen.getByRole("link", { name: /Создать образ/ })).toBeTruthy();
    // when: открыт чужой образ для модерации.
    cleanup();
    mount(<OutfitDetail actor={admin} context={{ kind: "admin", ownerId: 77 }} onPending={vi.fn()} pending={false} />, "/outfits/5");
    // then: доступно удаление, но не история оценок.
    expect(await screen.findByRole("button", { name: "Удалить образ" })).toBeTruthy();
    expect(screen.queryByText("История оценок")).toBeNull();
  });

  it("не предлагает стилисту оценивать собственный образ клиента", async () => {
    // given: автор образа — текущий стилист.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/outfits/5")) return ok(outfit({ authorId: stylist.id }));
      if (url.includes("ratings/history")) return ok([]);
      return fail(404);
    }));
    // when: открыта карточка клиента.
    mount(<OutfitDetail actor={stylist} context={{ kind: "client", ownerId: 77 }} onPending={vi.fn()} pending={false} />, "/outfits/5");
    await screen.findByText("Образ");
    // then: кнопок оценивания нет.
    expect(screen.queryByRole("heading", { name: "Оценка стилиста" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Нравится" })).toBeNull();
  });

  it("обновляет чтение после конфликта, не повторяя голос", async () => {
    // given: сервер отклоняет запись устаревшей оценки.
    const writes: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/stylist/clients/77/outfits/5") return ok(outfit());
      if (url.includes("ratings/history")) return ok([]);
      if (url.endsWith("/rating")) { writes.push(String(init?.method)); return fail(409); }
      return fail(404);
    }));
    mount(<OutfitDetail actor={stylist} context={{ kind: "client", ownerId: 77 }} onPending={vi.fn()} pending={false} />, "/outfits/5");
    // when: стилист отправляет голос и обновляет данные.
    fireEvent.click(await screen.findByRole("button", { name: "Нравится" }));
    fireEvent.click(await screen.findByRole("button", { name: "Обновить" }));
    // then: запись не повторяется.
    await waitFor(() => expect(writes).toEqual(["POST"]));
  });

  it("показывает конфликт удаления при модерации и не запрашивает чужую историю", async () => {
    // given: удаление отклонено сервером.
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input); calls.push(`${init?.method ?? "GET"} ${url}`);
      if (init?.method === "DELETE") return fail(409);
      if (url.endsWith("/outfits/5")) return ok(outfit());
      return ok([]);
    }));
    mount(<OutfitDetail actor={admin} context={{ kind: "admin", ownerId: 77 }} onPending={vi.fn()} pending={false} />, "/outfits/5");
    // when: администратор подтверждает удаление, затем обновляет данные.
    fireEvent.click(await screen.findByRole("button", { name: "Удалить образ" }));
    fireEvent.click(screen.getByRole("button", { name: "Да, удалить" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Обновить" }));
    // then: ошибка видна, запись не повторена, неподдерживаемого запроса истории нет.
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    expect(calls.filter((call) => call.startsWith("DELETE"))).toHaveLength(1);
    expect(calls.some((call) => call.includes("ratings/history"))).toBe(false);
  });

  it("объясняет пустую последнюю страницу и оставляет возврат", async () => {
    // given: первая страница заполнена ровно двенадцатью образами.
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/outfits?page=0")) return ok(Array.from({ length: 12 }, (_, id) => outfit({ id })));
      return ok([]);
    }));
    mount(<Outfits actor={user} pending={false} />);
    // when: пользователь переходит на пустую вторую страницу.
    const next = await screen.findByRole("button", { name: "Далее →" });
    fireEvent.click(next);
    // then: есть пояснение и возможность вернуться.
    await screen.findByText("На этой странице нет образов. Вернитесь на предыдущую.");
    expect((screen.getByRole("button", { name: "← Назад" }) as HTMLButtonElement).disabled).toBe(false);
  });
});
