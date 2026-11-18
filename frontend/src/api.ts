import { authenticatedFetch, getSessionEpoch } from "./auth";
import { ApiError } from "./api-error";
export { ApiError } from "./api-error";

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
  availability?: "AVAILABLE" | "IN_LAUNDRY" | "STORED";
};
export type WardrobeItemAvailability = NonNullable<WardrobeItem["availability"]>;
export function itemAvailability(item: WardrobeItem): WardrobeItemAvailability {
  return item.availability ?? "AVAILABLE";
}
export function availabilityLabel(item: WardrobeItem): string {
  return ({ AVAILABLE: "Доступна", IN_LAUNDRY: "В стирке", STORED: "Убрана" })[itemAvailability(item)];
}
export type Photo = {
  id: number;
  itemId: number;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
};
export type Category = { id: number; code: string; name: string };
export type Actor = Pick<Account, "id" | "login" | "role">;
export type StylePreferences = {
  preferredColorsAndMaterials: string;
  avoidances: string;
  additionalWishes: string;
  version: number;
  createdAt: string;
  modifiedAt: string;
};
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
  explanation?: string | null;
};
export type Capsule = {
  id: number; ownerId: number; name: string; itemIds: number[]; createdAt: string;
  source?: "USER" | "AI";
  weather?: { temperatureC: number; windSpeedMps: number; precipitationTypeId: number } | null;
  occasion?: string | null;
  preferredStyle?: string | null;
  explanation?: string | null;
};
export type StylistRequestAnswer = { outfitId: number; explanation: string; createdAt: string };
export type StylistRequest = {
  id: number; ownerId: number; stylistId: number; wishes: string;
  weather: { temperatureC: number; windSpeedMps: number; precipitationTypeId: number };
  status: "PENDING" | "CANCELLED" | "ANSWERED"; version: number;
  createdAt: string; modifiedAt: string; answer?: StylistRequestAnswer | null;
};

export type CalendarEntry = {
  id: number; ownerId: number; date: string; outfitId: number;
  status: "PLANNED" | "WORN"; version: number; createdAt: string; modifiedAt: string;
};

export type AiJob = {
  jobId: string;
  type: "OUTFIT_SELECTION" | "OUTFIT_REPLACEMENT" | "CAPSULE_SELECTION";
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "UNKNOWN";
  createdAt: string;
  modifiedAt: string;
  result: { type: "OUTFIT" | "CAPSULE"; id: number; url: string } | null;
  error: { code: string; message: string } | null;
};
export type AcceptedAiJob = { jobId: string; status: "QUEUED" | "RUNNING" };
export type AiJobPage = { jobs: AiJob[]; total: number };

export function outfitsPath(context: WorkspaceContext): string {
  if (context.kind === "own") return "/api/outfits";
  if (context.kind === "client") return `/api/stylist/clients/${context.ownerId}/outfits`;
  return `/api/admin/users/${context.ownerId}/outfits`;
}

export async function request<T>(
  path: string,
  actorId?: number,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body && !(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const response = await authenticatedFetch(path, actorId, {
    ...options,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  return response.status === 204 ? null as T : response.json() as Promise<T>;
}

export async function aiJobPage(actorId: number, page: number, signal?: AbortSignal): Promise<AiJobPage> {
  const response = await authenticatedFetch(`/api/v2/me/ai-jobs?page=${page}&size=50`, actorId, { signal });
  const jobs = await response.json() as AiJob[];
  const totalHeader = response.headers.get("X-Total-Count");
  const parsedTotal = totalHeader === null ? NaN : Number(totalHeader);
  return { jobs, total: Number.isSafeInteger(parsedTotal) && parsedTotal >= 0 ? parsedTotal : page * 50 + jobs.length };
}

export async function allPages<T>(
  path: string,
  actorId?: number,
  signal?: AbortSignal,
): Promise<T[]> {
  const all: T[] = [];
  const epoch = getSessionEpoch();
  for (let page = 0; ; page += 1) {
    if (epoch !== getSessionEpoch()) throw new ApiError(401);
    const url = `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=50`;
    const response = await authenticatedFetch(url, actorId, {
      credentials: "same-origin",
      cache: "no-store",
      signal,
    });
    const rows = await response.json() as T[];
    if (epoch !== getSessionEpoch()) throw new ApiError(401);
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
