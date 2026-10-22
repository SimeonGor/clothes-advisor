export type Account = { id: number; login: string; role: string; status: string };
export type WardrobeItem = {
  id: number;
  name: string;
  categoryId: number;
  color: string;
  material: string;
};
export type Photo = { id: number; itemId: number };
export type Precipitation = { id: number; name: string; code: string };
export type Outfit = {
  id: number; name: string; itemIds: number[];
  weather: { temperatureC: number; windSpeedMps: number; precipitationTypeId: number };
};

export class ApiError extends Error {
  constructor(readonly status: number) { super(`HTTP ${status}`); }
}

export async function request<T>(
  path: string,
  actorId?: number,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (actorId !== undefined) headers.set("X-User-Id", String(actorId));
  if (options.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, {
    ...options,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) throw new ApiError(response.status);
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

export function errorMessage(error: unknown): string {
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

export function mutationErrorMessage(error: unknown): string {
  return error instanceof ApiError
    ? errorMessage(error)
    : [
      "Связь прервалась. Перед повтором проверьте сохранённые образы:",
      "образ мог сохраниться.",
    ].join(" ");
}
