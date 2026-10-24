export type Account = { id: number; login: string; role: string; status: string };
export type WardrobeItem = {
  id: number;
  name: string;
  categoryId: number;
  color: string;
  material: string;
  version: number;
  createdAt: string;
  modifiedAt: string;
};
export type Photo = {
  id: number;
  itemId: number;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
};
export type Category = { id: number; code: string; name: string };
export type Actor = Pick<Account, "id" | "login" | "role">;
export type WorkspaceContext =
  | { kind: "own" }
  | { kind: "client"; ownerId: number }
  | { kind: "admin"; ownerId: number };

export function wardrobeItemsPath(context: WorkspaceContext): string {
  if (context.kind === "own") return "/api/wardrobe/items";
  if (context.kind === "client") return `/api/stylist/clients/${context.ownerId}/wardrobe/items`;
  return `/api/admin/users/${context.ownerId}/wardrobe/items`;
}

export function canEditWardrobe(context: WorkspaceContext): boolean {
  return context.kind === "own";
}

export function canDeleteWardrobePhoto(context: WorkspaceContext): boolean {
  return context.kind === "own" || context.kind === "admin";
}
export type Precipitation = { id: number; name: string; code: string };
export type Outfit = {
  id: number; ownerId: number; authorId: number; source: "USER" | "STYLIST" | "AI";
  name: string; itemIds: number[]; likes: number; dislikes: number; createdAt: string;
  weather: { temperatureC: number; windSpeedMps: number; precipitationTypeId: number };
  myRating?: { vote: "LIKE" | "DISLIKE"; version: number } | null;
  occasion?: string | null;
  preferredStyle?: string | null;
};

export function outfitsPath(context: WorkspaceContext): string {
  if (context.kind === "own") return "/api/outfits";
  if (context.kind === "client") return `/api/stylist/clients/${context.ownerId}/outfits`;
  return `/api/admin/users/${context.ownerId}/outfits`;
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code?: string) { super(`HTTP ${status}`); }
}

export async function request<T>(
  path: string,
  actorId?: number,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (actorId !== undefined) headers.set("X-User-Id", String(actorId));
  if (options.body && !(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(path, {
    ...options,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null);
    const code = body && typeof body === "object" && "error" in body
      && typeof body.error === "string" && /^[a-z_]{1,80}$/.test(body.error) ? body.error : undefined;
    throw new ApiError(response.status, code);
  }
  return response.status === 204 ? null as T : response.json() as Promise<T>;
}

export async function allPages<T>(
  path: string,
  actorId?: number,
  signal?: AbortSignal,
): Promise<T[]> {
  const all: T[] = [];
  for (let page = 0; ; page += 1) {
    const url = `${path}?page=${page}&size=50`;
    const response = await fetch(url, {
      headers: actorId === undefined ? {} : { "X-User-Id": String(actorId) },
      credentials: "same-origin",
      cache: "no-store",
      signal,
    });
    if (!response.ok) throw new ApiError(response.status);
    const rows = await response.json() as T[];
    all.push(...rows);
    const total = response.headers.get("X-Total-Count");
    if (!rows.length || (total === null ? rows.length < 50 : all.length >= Number(total))) {
      return all;
    }
  }
}

export function resourceErrorMessage(error: unknown, writing = false): string {
  const idempotency = idempotencyErrorMessage(error);
  if (idempotency) return idempotency;
  if (writing && (!(error instanceof ApiError) || error.status >= 500)) {
    return "Не удалось получить подтверждение. Проверьте данные перед повтором: действие могло выполниться.";
  }
  if (!(error instanceof ApiError)) return "Нет связи. Проверьте подключение и повторите загрузку.";
  if (error.status === 400) return "Проверьте введённые данные.";
  if (error.status === 403) return "Недостаточно прав. Проверьте выбранный аккаунт.";
  if (error.status === 404) return "Данные не найдены или доступ к ним отозван.";
  if (error.status === 409) return "Данные изменились. Обновите их перед повтором.";
  return "Сервис временно недоступен. Повторите загрузку позже.";
}

export function errorMessage(error: unknown): string {
  const idempotency = idempotencyErrorMessage(error);
  if (idempotency) return idempotency;
  if (!(error instanceof ApiError)) {
    return "Нет связи. Проверьте подключение и повторите загрузку.";
  }
  if (error.status === 400) return "Проверьте название и значения погоды.";
  if (error.status === 404 || error.status === 409) {
    return "Аккаунт или выбранная вещь изменились. Обновите данные.";
  }
  if (error.status === 422) {
    return "Не удалось подобрать подходящий образ. "
      + "Данные формы сохранены.";
  }
  if (error.status === 503) {
    return "Сервис AI сейчас недоступен. Данные формы сохранены.";
  }
  if (error.status === 502) {
    return "Сервис AI вернул некорректный результат. "
      + "Данные формы сохранены.";
  }
  if (error.status === 504) {
    return [
      "Истекло время ожидания. Перед повтором проверьте сохранённые образы:",
      "образ мог сохраниться.",
    ].join(" ");
  }
  return `Не удалось выполнить запрос (${error.status}). `
    + "Данные формы сохранены.";
}

export function idempotencyErrorMessage(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return;
  if (error.code === "idempotency_in_progress") return "Запрос уже принят, но результат пока неизвестен. Проверьте сохранённые данные. Повтор с тем же ключом не запускает действие заново.";
  if (error.code === "idempotency_failed") return "Предыдущая попытка завершилась ошибкой. Она не будет выполнена повторно. Для нового запуска выберите «Новая попытка».";
  if (error.code === "idempotency_key_reused") return "Ключ уже связан с другими данными. Проверьте сохранённый результат перед новой попыткой.";
}

export function mutationErrorMessage(error: unknown): string {
  return error instanceof ApiError
    ? errorMessage(error)
    : [
      "Связь прервалась. Перед повтором проверьте сохранённые образы:",
      "образ мог сохраниться.",
    ].join(" ");
}
