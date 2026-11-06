import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { act, cleanup, createEvent, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Link, MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Actor, Category, Photo, WardrobeItem } from "./api";
import { canDeleteWardrobePhoto, canEditWardrobe, wardrobeItemsPath } from "./api";
import { SiteShell } from "./site";
import { Wardrobe, WardrobeEditor } from "./wardrobe";

const actor: Actor = { id: 21, login: "user-one", role: "USER" };
const category: Category = { id: 4, code: "COAT", name: "Пальто" };
const item: WardrobeItem = {
  id: 8, name: "Пальто", categoryId: 4, color: "серое", material: "шерсть", version: 3,
  createdAt: "2026-01-01T00:00:00Z", modifiedAt: "2026-01-02T00:00:00Z",
};
const photo: Photo = {
  id: 9, itemId: item.id, contentType: "image/jpeg", sizeBytes: 12,
  createdAt: "2026-01-02T00:00:00Z",
};
const originalCreateObjectUrl = URL.createObjectURL;
const originalRevokeObjectUrl = URL.revokeObjectURL;

function json(data: unknown, status = 200) {
  return { ok: status < 400, status, headers: new Headers(), json: async () => data } as Response;
}
function failed(status: number) { return json({}, status); }

function fixtureFetch(override: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => override(String(input), init));
}

function readAsText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

function EditorPage({ actor }: { actor: Actor }) {
  const [pending, setPending] = useState(false);
  const location = useLocation();
  return <SiteShell account={actor} onSwitch={vi.fn()} pending={pending} active="wardrobe">
    <Link to="/wardrobe/9">Другая вещь</Link>
    <WardrobeEditor key={`${actor.id}:${location.pathname}`} actor={actor} onPending={setPending} pending={pending} />
  </SiteShell>;
}

function mount(path: string, fetchMock: ReturnType<typeof fixtureFetch>) {
  vi.stubGlobal("fetch", fetchMock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path="/wardrobe" element={<SiteShell account={actor} onSwitch={vi.fn()} pending={false} active="wardrobe">
        <Wardrobe actor={actor} pending={false} />
      </SiteShell>} />
      <Route path="/wardrobe/new" element={<EditorPage actor={actor} />} />
      <Route path="/wardrobe/:itemId" element={<EditorPage actor={actor} />} />
    </Routes>
  </MemoryRouter></QueryClientProvider>);
  return { ...view, client };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  if (originalCreateObjectUrl) Object.defineProperty(URL, "createObjectURL", { configurable: true, value: originalCreateObjectUrl });
  else Reflect.deleteProperty(URL, "createObjectURL");
  if (originalRevokeObjectUrl) Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: originalRevokeObjectUrl });
  else Reflect.deleteProperty(URL, "revokeObjectURL");
});

describe("Гардероб", () => {
  it("использует раздельные пути и возможности для контекстов", () => {
    expect(wardrobeItemsPath({ kind: "own" })).toBe("/api/wardrobe/items");
    expect(wardrobeItemsPath({ kind: "client", ownerId: 12 })).toBe("/api/stylist/clients/12/wardrobe/items");
    expect(wardrobeItemsPath({ kind: "admin", ownerId: 14 })).toBe("/api/admin/users/14/wardrobe/items");
    expect(canEditWardrobe({ kind: "own" })).toBe(true);
    expect(canEditWardrobe({ kind: "client", ownerId: 12 })).toBe(false);
    expect(canEditWardrobe({ kind: "admin", ownerId: 14 })).toBe(false);
    expect(canDeleteWardrobePhoto({ kind: "client", ownerId: 12 })).toBe(false);
    expect(canDeleteWardrobePhoto({ kind: "admin", ownerId: 14 })).toBe(true);
  });

  it("создаёт вещь с multipart item Blob и фотографиями без JSON content-type", async () => {
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:preview") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    let sent: RequestInit | undefined;
    const fetchMock = fixtureFetch((url, init) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items" && init?.method === "POST") { sent = init; return json(item); }
      if (url === "/api/wardrobe/items/8") return json(item);
      if (url === "/api/wardrobe/items/8/photos") return json([photo]);
      if (url.endsWith("/content")) return {
        ok: true, status: 200, headers: new Headers(), blob: async () => new Blob(["image"]),
      } as Response;
      return failed(404);
    });
    mount("/wardrobe/new", fetchMock);

    // given: заполнены обязательные поля и выбрана JPEG-фотография.

    await screen.findByRole("option", { name: "Пальто" });
    fireEvent.change(screen.getByLabelText("Название"), { target: { value: "Пальто" } });
    fireEvent.change(screen.getByLabelText("Категория"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Цвет"), { target: { value: "серое" } });
    fireEvent.change(screen.getByLabelText("Материал"), { target: { value: "шерсть" } });
    fireEvent.change(screen.getByLabelText(/Фотографии/i), {
      target: { files: [new File(["jpeg"], "coat.jpg", { type: "image/jpeg" })] },
    });

    // when: пользователь сохраняет новую вещь.

    fireEvent.click(screen.getByRole("button", { name: /Добавить вещь/ }));

    // then: item JSON и файл отправлены как multipart, после чего открыта карточка вещи.

    await screen.findByRole("heading", { name: "Изменить вещь" });
    expect(sent?.headers).toBeInstanceOf(Headers);
    expect(new Headers(sent?.headers).get("Content-Type")).toBeNull();
    expect(sent?.body).toBeInstanceOf(FormData);
    const form = sent?.body as FormData;
    expect(form.get("photos")).toBeInstanceOf(File);
    expect(JSON.parse(await readAsText(form.get("item") as Blob))).toEqual({
      name: "Пальто", categoryId: 4, color: "серое", material: "шерсть",
    });
    expect(fetchMock.mock.calls.some(([url, init]) => url === "/api/wardrobe/items/8"
      && new Headers(init?.headers).get("X-User-Id") === "21")).toBe(true);
  });

  it("передаёт версию при редактировании и удалении, сохраняя поля после 409", async () => {
    const urls: Array<[string, RequestInit | undefined]> = [];
    const fetchMock = fixtureFetch((url, init) => {
      urls.push([url, init]);
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items/8") return init?.method === "PUT" ? failed(409) : json(item);
      if (url === "/api/wardrobe/items/8/photos") return json([]);
      if (url === "/api/wardrobe/items/8?version=3") return failed(409);
      return failed(404);
    });
    mount("/wardrobe/8", fetchMock);

    // given: загружены сохранённые поля вещи.

    const name = await screen.findByLabelText("Название") as HTMLInputElement;
    fireEvent.change(name, { target: { value: "Моё название" } });

    // when: обновление версии конфликтует, затем пользователь подтверждает удаление.

    fireEvent.click(screen.getByRole("button", { name: /Сохранить изменения/ }));
    expect((await screen.findByRole("alert")).textContent).toContain("Данные вещи изменились");
    expect(name.value).toBe("Моё название");
    fireEvent.click(screen.getByRole("button", { name: "Удалить вещь" }));
    fireEvent.click(screen.getByRole("button", { name: "Да, удалить" }));

    // then: обе операции используют версию, конфликт оставляет форму и страницу доступными.

    await waitFor(() => expect(urls.some(([url, init]) => url === "/api/wardrobe/items/8?version=3" && init?.method === "DELETE")).toBe(true));
    expect(urls.find(([url, init]) => url === "/api/wardrobe/items/8" && init?.method === "PUT")?.[1]?.body)
      .toBe(JSON.stringify({ name: "Моё название", categoryId: 4, color: "серое", material: "шерсть", availability: "AVAILABLE", version: 3 }));
    expect((await screen.findByRole("alert")).textContent).toContain("используется в образе");
    expect(name.value).toBe("Моё название");
  });

  it("не отправляет неподходящие, слишком большие или избыточные фото", async () => {
    let posts = 0;
    const fetchMock = fixtureFetch((url, init) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items" && init?.method === "POST") { posts += 1; return json(item); }
      return failed(404);
    });
    mount("/wardrobe/new", fetchMock);
    await screen.findByRole("option", { name: "Пальто" });
    fireEvent.change(screen.getByLabelText("Название"), { target: { value: "Пальто" } });
    fireEvent.change(screen.getByLabelText("Категория"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Цвет"), { target: { value: "серое" } });
    fireEvent.change(screen.getByLabelText("Материал"), { target: { value: "шерсть" } });

    // given: файл не JPEG/PNG, затем файл больше предела и выбор из шести фото.

    const input = screen.getByLabelText(/Фотографии/i);
    const invalidChoices = [
      [new File(["gif"], "coat.gif", { type: "image/gif" })],
      [new File([new Uint8Array(10_000_001)], "large.jpg", { type: "image/jpeg" })],
      Array.from({ length: 6 }, (_, index) => new File(["ok"], `${index}.png`, { type: "image/png" })),
    ];
    for (const files of invalidChoices) {
      fireEvent.change(input, { target: { files } });
      fireEvent.click(screen.getByRole("button", { name: /Добавить вещь/ }));
      await screen.findByRole("alert");
      expect(posts).toBe(0);
    }

    // when: цвет состоит только из пробелов, хотя остальные поля корректны.

    fireEvent.change(screen.getByLabelText("Цвет"), { target: { value: "   " } });
    fireEvent.change(input, { target: { files: [] } });
    fireEvent.click(screen.getByRole("button", { name: /Добавить вещь/ }));

    // then: обязательное значение проверяется после trim и запрос не отправляется.

    expect((await screen.findByRole("alert")).textContent).toContain("Укажите цвет и материал");
    expect(posts).toBe(0);
  });

  it("принимает перетаскивание валидных фото, не показывает невалидные и удаляет выбранное превью", async () => {
    const createUrl = vi.fn((file: File) => `blob:${file.name}`);
    const revokeUrl = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeUrl });
    const fetchMock = fixtureFetch((url) => url.startsWith("/api/wardrobe/categories") ? json([category]) : failed(404));
    mount("/wardrobe/new", fetchMock);
    const zone = await screen.findByText("Начните с фотографии");
    const valid = new File(["jpeg"], "coat.jpg", { type: "image/jpeg" });
    fireEvent.drop(zone.closest("div")!, { dataTransfer: { files: [valid] } });
    await screen.findByAltText("Предпросмотр: coat.jpg");
    expect(createUrl).toHaveBeenCalledWith(valid);

    const invalid = new File(["gif"], "coat.gif", { type: "image/gif" });
    fireEvent.drop(zone.closest("div")!, { dataTransfer: { files: [invalid] } });
    expect(await screen.findByRole("alert").then((alert) => alert.textContent)).toContain("Поддерживаются фотографии JPEG и PNG");
    expect(screen.queryByAltText("Предпросмотр: coat.gif")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Продолжить без этих файлов" }));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByAltText("Предпросмотр: coat.jpg")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Удалить фото coat.jpg" }));
    await waitFor(() => expect(screen.queryByAltText("Предпросмотр: coat.jpg")).toBeNull());
    expect(revokeUrl).toHaveBeenCalledWith("blob:coat.jpg");
  });

  it("игнорирует перетаскивание пока создание вещи ожидает ответа", async () => {
    let finishCreate!: (response: Response) => void;
    const fetchMock = fixtureFetch((url, init) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items" && init?.method === "POST") {
        return new Promise<Response>((resolve) => { finishCreate = resolve; });
      }
      if (url === "/api/wardrobe/items/8") return json(item);
      if (url === "/api/wardrobe/items/8/photos") return json([]);
      return failed(404);
    });
    mount("/wardrobe/new", fetchMock);
    await screen.findByRole("option", { name: "Пальто" });
    fireEvent.change(screen.getByLabelText("Название"), { target: { value: "Пальто" } });
    fireEvent.change(screen.getByLabelText("Категория"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Цвет"), { target: { value: "серое" } });
    fireEvent.change(screen.getByLabelText("Материал"), { target: { value: "шерсть" } });
    fireEvent.click(screen.getByRole("button", { name: /Добавить вещь/ }));
    await screen.findByRole("button", { name: /Сохраняем/ });

    const zone = screen.getByText("Начните с фотографии").closest("div")!;
    const dragOver = createEvent.dragOver(zone);
    fireEvent(zone, dragOver);
    expect(dragOver.defaultPrevented).toBe(true);
    fireEvent.drop(zone, { dataTransfer: { files: [new File(["jpeg"], "late.jpg", { type: "image/jpeg" })] } });
    expect(screen.queryByAltText("Предпросмотр: late.jpg")).toBeNull();

    await act(async () => { finishCreate(json(item)); });
    await screen.findByRole("heading", { name: "Изменить вещь" });
  });

  it("сохраняет версию исходного черновика при фоне и обновляет её только по явному запросу", async () => {
    let reads = 0;
    const versions = [
      { ...item, name: "Исходное пальто", version: 1, availability: "IN_LAUNDRY" },
      { ...item, name: "Фоновое изменение", version: 2, availability: "STORED" },
      { ...item, name: "Обновлённое пальто", version: 3, availability: "AVAILABLE" },
    ];
    const puts: Array<Record<string, unknown>> = [];
    const fetchMock = fixtureFetch((url, init) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items/8" && init?.method === "PUT") {
        puts.push(JSON.parse(String(init.body)) as Record<string, unknown>);
        return puts.length === 1 ? failed(409) : json({ ...item, name: "Сохранённое пальто", version: 4, availability: "STORED" });
      }
      if (url === "/api/wardrobe/items/8") return json(versions[Math.min(reads++, versions.length - 1)]);
      if (url === "/api/wardrobe/items/8/photos") return json([]);
      return failed(404);
    });
    const { client } = mount("/wardrobe/8", fetchMock);
    const name = await screen.findByLabelText("Название") as HTMLInputElement;
    await screen.findByRole("option", { name: "Пальто" });
    const availability = screen.getByLabelText("Доступность") as HTMLSelectElement;
    expect(availability.value).toBe("IN_LAUNDRY");

    // given: draft is based on v1, then a background query refresh observes v2.

    await client.invalidateQueries({ queryKey: ["wardrobe-item", actor.id, 8] });
    await waitFor(() => expect(reads).toBe(2));
    expect(name.value).toBe("Исходное пальто");
    expect(availability.value).toBe("IN_LAUNDRY");
    fireEvent.change(name, { target: { value: "Черновик пользователя" } });
    fireEvent.change(availability, { target: { value: "STORED" } });

    // when: v1 conflicts, the user explicitly reloads v3, edits, and saves.

    fireEvent.click(screen.getByRole("button", { name: /Сохранить изменения/ }));
    expect((await screen.findByRole("alert")).textContent).toContain("Данные вещи изменились");
    expect(puts[0].version).toBe(1);
    expect(puts[0].availability).toBe("STORED");
    expect(availability.value).toBe("STORED");
    fireEvent.click(screen.getByRole("button", { name: "Обновить данные" }));
    await waitFor(() => expect(name.value).toBe("Обновлённое пальто"));
    expect(availability.value).toBe("AVAILABLE");
    fireEvent.change(name, { target: { value: "Новая правка" } });
    fireEvent.click(screen.getByRole("button", { name: /Сохранить изменения/ }));

    // then: the reloaded fields and v3 travel together; successful PUT reports saved.

    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Сохранено"));
    expect(puts[1]).toEqual({ name: "Новая правка", categoryId: 4, color: "серое", material: "шерсть", availability: "AVAILABLE", version: 3 });
    expect(availability.value).toBe("STORED");
    expect(screen.getByRole("status").textContent).toContain("Сохранено");
  });

  it("возвращает пустую последнюю страницу назад к вещам", async () => {
    const rows = Array.from({ length: 12 }, (_, index) => ({ ...item, id: index + 1, name: `Вещь ${index + 1}` }));
    const fetchMock = fixtureFetch((url) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items?page=0&size=12") return json(rows);
      if (url === "/api/wardrobe/items?page=1&size=12") return json([]);
      if (url.endsWith("/photos")) return json([]);
      return failed(404);
    });
    mount("/wardrobe", fetchMock);

    // given: первая страница заполнена ровно до границы размера.

    await screen.findByText("Вещь 1");

    // when: следующая страница оказывается пустой.

    fireEvent.click(screen.getByRole("button", { name: "Далее →" }));
    await screen.findByText("На этой странице вещей нет.");

    // then: назад доступно и возвращает пользователя к первой странице.

    const back = screen.getByRole("button", { name: "← Назад" }) as HTMLButtonElement;
    expect(back.disabled).toBe(false);
    fireEvent.click(back);
    await screen.findByText("Вещь 1");
  });

  it("сбрасывает поля при переходе к другому id вещи", async () => {
    const other = { ...item, id: 9, name: "Куртка", version: 7 };
    const fetchMock = fixtureFetch((url, init) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items/8") return json(item);
      if (url === "/api/wardrobe/items/9") return init?.method === "PUT" ? json(other) : json(other);
      if (url.endsWith("/photos")) return json([]);
      return failed(404);
    });
    mount("/wardrobe/8", fetchMock);

    // given: пользователь редактирует первую вещь.

    const name = await screen.findByLabelText("Название") as HTMLInputElement;
    await screen.findByRole("option", { name: "Пальто" });
    fireEvent.change(name, { target: { value: "Несохранённый текст" } });

    // when: он открывает другую вещь.

    fireEvent.click(screen.getByRole("link", { name: "Другая вещь" }));

    // then: новая карточка показывает свои поля, а PUT адресован новому id/version.

    await screen.findByDisplayValue("Куртка");
    const nextName = screen.getByLabelText("Название") as HTMLInputElement;
    expect(nextName.value).toBe("Куртка");
    fireEvent.change(nextName, { target: { value: "Новая куртка" } });
    fireEvent.click(screen.getByRole("button", { name: /Сохранить изменения/ }));
    await screen.findByRole("status");
    const put = fetchMock.mock.calls.find(([url, init]) => url === "/api/wardrobe/items/9" && init?.method === "PUT");
    expect(JSON.parse(String(put?.[1]?.body)).version).toBe(7);
  });

  it.each(["8", "invalid"])("показывает ошибку вместо вечной загрузки для вещи %s", async (id) => {
    // given: вещь отсутствует либо идентификатор некорректен.
    const fetchMock = fixtureFetch((url) => url.startsWith("/api/wardrobe/categories") ? json([category]) : failed(404));
    // when: пользователь открывает карточку.
    mount(`/wardrobe/${id}`, fetchMock);
    // then: загрузка сменяется сообщением и доступным действием восстановления.
    await screen.findByRole("alert");
    expect(screen.queryByText("Загружаем вещь…")).toBeNull();
    if (id === "8") expect(screen.getByRole("button", { name: "Повторить загрузку" })).toBeTruthy();
    else expect(screen.getByRole("link", { name: "К гардеробу" })).toBeTruthy();
  });

  it("не дублирует сохранение и блокирует навигацию пока запрос ожидает ответа", async () => {
    let finish!: (response: Response) => void;
    let posts = 0;
    const fetchMock = fixtureFetch((url, init) => {
      if (url.startsWith("/api/wardrobe/categories")) return json([category]);
      if (url === "/api/wardrobe/items" && init?.method === "POST") {
        posts += 1;
        return new Promise<Response>((resolve) => { finish = resolve; });
      }
      if (url === "/api/wardrobe/items/8") return json(item);
      return failed(404);
    });
    mount("/wardrobe/new", fetchMock);
    await screen.findByRole("option", { name: "Пальто" });
    fireEvent.change(screen.getByLabelText("Название"), { target: { value: "Пальто" } });
    fireEvent.change(screen.getByLabelText("Категория"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Цвет"), { target: { value: "серое" } });
    fireEvent.change(screen.getByLabelText("Материал"), { target: { value: "шерсть" } });

    // given: сохранение отправлено, ответ сервера ещё не пришёл.

    const form = screen.getByLabelText("Название").closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(posts).toBe(1));
    expect((screen.getByRole("button", { name: "Сменить аккаунт" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getAllByRole("link", { name: /Гардероб/ })[0].getAttribute("aria-disabled")).toBe("true");

    // when: сервер завершает одно сохранение.

    await act(async () => finish(json(item)));

    // then: отправлен один запрос и открыта сохранённая вещь.

    await screen.findByRole("heading", { name: "Изменить вещь" });
    expect(posts).toBe(1);
  });
});
