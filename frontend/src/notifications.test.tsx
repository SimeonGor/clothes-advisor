import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./app";
import { authenticatedActor } from "./auth-test-fixture";
import type { Actor } from "./api";

const user = { id: 11, login: "владелец", role: "USER" };
const stylist = { id: 22, login: "стилист", role: "STYLIST" };
const base = "/api/me/notifications";
const jobId = "550e8400-e29b-41d4-a716-446655440000";
const createdAt = "2026-05-11T09:10:00Z";
const notice = (id: number, message: string, resource: { type: string; id: string } | null = null) =>
  ({ id, type: "Event", message, resource, readAt: null as string | null, createdAt });
const ok = (body: unknown, total?: number) => new Response(JSON.stringify(body), {
  headers: total === undefined ? {} : { "X-Total-Count": String(total) },
});
function mount(actor: Actor, handler: (url: string, init: RequestInit) => Response | Promise<Response> | undefined) {
  authenticatedActor(actor);
  const fetch = vi.fn(async (path: string, init: RequestInit = {}) => (await handler(path, init)) ?? ok([]));
  vi.stubGlobal("fetch", fetch);
  render(<MemoryRouter initialEntries={[actor.role === "STYLIST" ? "/clients" : "/wardrobe"]}><App /></MemoryRouter>);
  return fetch;
}
async function openInbox(count: number) {
  fireEvent.click(await screen.findByRole("button", { name: `Уведомления, непрочитанных: ${count}` }));
  return within(screen.getByRole("dialog", { name: "Уведомления" }));
}
beforeEach(() => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute("open", ""); } });
  Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute("open"); } });
});
afterEach(() => {
  cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal"); Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
});

describe("Уведомления", () => {
  it("показывает точное число страниц и обновляет счётчик после подтверждённого прочтения", async () => {
    const rows = Array.from({ length: 11 }, (_, i) => notice(i + 1, `Сообщение ${i + 1}`));
    const fetch = mount(user, (url, init) => {
      if (url === `${base}/unread-count`) return ok({ count: rows.filter(row => !row.readAt).length });
      if (url === `${base}?page=0&size=10`) return ok(rows.slice(0, 10), rows.length);
      if (url === `${base}?page=1&size=10`) return ok(rows.slice(10), rows.length);
      if (url === `${base}/11/read` && init.method === "PUT") {
        rows[10].readAt = createdAt;
        return new Response(null, { status: 204 });
      }
    });
    const inbox = await openInbox(11);
    await inbox.findByText("Сообщение 1");
    expect(inbox.queryByText("Сообщение 11")).toBeNull();
    fireEvent.click(inbox.getByRole("button", { name: "Далее" }));
    await inbox.findByText("Сообщение 11");
    expect((inbox.getByRole("button", { name: "Далее" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(inbox.getByRole("button", { name: "Отметить прочитанным" }));
    await waitFor(() => expect(inbox.queryByText("Новое")).toBeNull());
    expect(screen.getByRole("button", { name: "Уведомления, непрочитанных: 10" })).toBeTruthy();
    expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(1);
    fireEvent.click(inbox.getByRole("button", { name: "Назад" }));
    await inbox.findByText("Сообщение 1");
  });

  it("отображает сообщение как текст и строит только проверенные маршруты", async () => {
    const unsafe = '<a href="https://evil.test">Нажмите</a>';
    const rows = [
      notice(1, unsafe, { type: "https://evil.test", id: "1" }),
      notice(2, "Повреждённый путь", { type: "OUTFIT", id: "1/../../accounts" }),
      notice(3, "Чужая роль", { type: "CLIENT", id: "10" }),
      notice(4, "Повреждённый UUID", { type: "AI_JOB", id: "https://evil.test" }),
      notice(5, "Нет объекта"), notice(6, "Образ", { type: "OUTFIT", id: "9" }),
      notice(7, "Ответ стилиста", { type: "STYLIST_REQUEST", id: "3" }),
    ];
    mount(user, url => url.endsWith("unread-count") ? ok({ count: 7 }) : url.startsWith(base) ? ok(rows, 7)
      : url === "/api/me/stylist-requests/3" ? new Response(null, { status: 404 }) : undefined);
    const inbox = await openInbox(7);
    await inbox.findByText(unsafe);
    expect(inbox.getAllByRole("link").map(link => link.getAttribute("href"))).toEqual(["/outfits/9", "/requests/3"]);
    expect(inbox.getAllByText("Ресурс недоступен.")).toHaveLength(4);
    fireEvent.click(inbox.getByRole("link", { name: "Открыть запрос" }));
    await screen.findByText("Данные не найдены или доступ к ним отозван.");
    expect(screen.queryByRole("dialog", { name: "Уведомления" })).toBeNull();
  });

  it("ведёт стилиста к конкретному клиенту или запросу и проверяет отозванный доступ", async () => {
    const rows = [notice(1, "Есть доступ", { type: "CLIENT", id: "41" }),
      notice(2, "Новый запрос", { type: "CLIENT_REQUEST", id: "41/8" })];
    mount(stylist, url => url.endsWith("unread-count") ? ok({ count: 2 })
      : url.startsWith(base) ? ok(rows, 2)
      : url.startsWith("/api/stylist/clients?") ? ok([{ id: 41, login: "клиент" }])
      : url === "/api/stylist/clients/41/requests/8" ? new Response(null, { status: 403 }) : undefined);
    const inbox = await openInbox(2);
    const link = await inbox.findByRole("link", { name: "Открыть запрос" });
    expect(inbox.getByRole("link", { name: "Гардероб клиента" }).getAttribute("href")).toBe("/clients/41/wardrobe");
    expect(link.getAttribute("href")).toBe("/clients/41/requests/8");
    fireEvent.click(link);
    await screen.findByText("Недостаточно прав. Проверьте выбранный аккаунт.");
  });

  it.each([200, 404])("открывает AI-задание через GET и обрабатывает ответ %i", async (status) => {
    const row = notice(1, "Подбор завершён", { type: "AI_JOB", id: jobId });
    const fetch = mount(user, url => url.endsWith("unread-count") ? ok({ count: 1 })
      : url.startsWith(base) ? ok([row], 1)
      : url === `/api/v2/me/ai-jobs/${jobId}` ? status === 404 ? new Response(null, { status }) :
        ok({ jobId, status: "SUCCEEDED", result: { type: "CAPSULE", id: 31, url: "https://evil.test" } }) : undefined);
    const inbox = await openInbox(1);
    fireEvent.click(await inbox.findByRole("button", { name: "Открыть задание" }));
    if (status === 404) await inbox.findByText("Задание недоступно.");
    else expect((await inbox.findByRole("link", { name: "Открыть капсулу" })).getAttribute("href")).toBe("/capsules/31");
    expect(fetch.mock.calls.every(([url, init]) => url.startsWith("/api/") && !init?.method)).toBe(true);
  });

  it("после выхода и смены сессии игнорирует запоздавшую страницу первого аккаунта", async () => {
    let resolvePage!: (response: Response) => void;
    let oldSignal: AbortSignal | null | undefined;
    mount(user, (url, init) => {
      if (url === "/api/auth/logout") return new Response(null, { status: 204 });
      const old = new Headers(init.headers).get("Authorization") === "Bearer test-token-11";
      if (url.endsWith("unread-count")) return ok({ count: old ? 8 : 0 });
      if (url.startsWith(base)) {
        if (!old) return ok([], 0);
        oldSignal = init.signal;
        return new Promise<Response>(resolve => { resolvePage = resolve; });
      }
    });
    const inbox = await openInbox(8);
    await inbox.findByText("Загружаем уведомления…");
    fireEvent.click(inbox.getByRole("button", { name: "Закрыть" }));
    fireEvent.click(screen.getByRole("button", { name: "Выйти" }));
    await screen.findByRole("heading", { name: "Вход" });
    expect(oldSignal?.aborted).toBe(true);
    act(() => authenticatedActor(stylist));
    const next = await openInbox(0);
    await next.findByText("Уведомлений пока нет.");
    await act(async () => resolvePage(ok([notice(1, "Секрет первого аккаунта")], 1)));
    expect(screen.queryByText("Секрет первого аккаунта")).toBeNull();
  });

  it("очищает уведомления и прерывает отметку при новой сессии того же пользователя", async () => {
    let fresh = false;
    let resolveWrite!: (response: Response) => void;
    let writeSignal: AbortSignal | null | undefined;
    mount(user, (url, init) => {
      if (url.endsWith("unread-count")) return ok({ count: fresh ? 3 : 1 });
      if (url.endsWith("/read")) {
        writeSignal = init.signal;
        return new Promise<Response>(resolve => { resolveWrite = resolve; });
      }
      if (url.startsWith(base)) return ok([notice(1, fresh ? "Новое сообщение" : "Старая сессия")], 1);
    });
    const first = await openInbox(1);
    fireEvent.click(await first.findByRole("button", { name: "Отметить прочитанным" }));
    await first.findByText("Сохраняем…");
    fresh = true;
    act(() => authenticatedActor(user));
    expect(screen.queryByText("Старая сессия")).toBeNull();
    expect(writeSignal?.aborted).toBe(true);
    const next = await openInbox(3);
    await next.findByText("Новое сообщение");
    await act(async () => resolveWrite(new Response(null, { status: 403 })));
    expect(next.queryByRole("alert")).toBeNull();
    expect(next.getByText("Новое")).toBeTruthy();
  });
});
