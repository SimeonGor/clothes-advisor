import React from "react";
import { createRequest, NewAttempt } from "./idempotency";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  allPages,
  mutationErrorMessage,
  request,
  resourceErrorMessage,
  type Account,
  type Actor,
  type Outfit,
  type Photo,
  type Precipitation,
  type WardrobeItem,
  availabilityLabel,
  itemAvailability,
  type WorkspaceContext,
} from "./api";
import styles from "./site.module.css";

const pageSize = 12;

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <div className={styles.error} role="alert">
      <p>{resourceErrorMessage(error)}</p>
      <button type="button" onClick={retry}>Повторить загрузку</button>
    </div>
  );
}

export function AccountPicker({ onChoose }: { onChoose: (account: Actor) => void }) {
  const accounts = useQuery({
    queryKey: ["accounts"],
    queryFn: async ({ signal }) => {
      const all = await allPages<Account>("/api/admin/users", undefined, signal);
      return all.filter((account) => account.status === "ACTIVE");
    },
  });
  const [loading, setLoading] = React.useState<number | null>(null);
  const [error, setError] = React.useState("");

  async function choose(id: number) {
    setLoading(id);
    setError("");
    try {
      const account = await request<Account>(`/api/admin/users/${id}`);
      if (account.status !== "ACTIVE") throw new Error("inactive");
      onChoose({ id: account.id, login: account.login, role: account.role });
    } catch {
      setError("Этот аккаунт больше не активен. "
        + "Обновите список и выберите другой.");
    } finally {
      setLoading(null);
    }
  }

  return (
    <main className={styles.pick} id="main">
      <p className={styles.eyebrow}>ГАРДЕРОБ · ПЕРСОНАЛЬНЫЙ СТИЛЬ</p>
      <h1>Выберите аккаунт</h1>
      <p className={styles.muted}>
        Это тестовый выбор аккаунта, а не вход в систему.
      </p>
      {accounts.isLoading && <p>Загружаем активные аккаунты…</p>}
      {accounts.isError && (
        <ReadError error={accounts.error} retry={() => void accounts.refetch()} />
      )}
      {accounts.data?.length === 0 && <p>Нет активных аккаунтов.</p>}
      {error && <p role="alert">{error}</p>}
      {accounts.data?.map((account) => (
        <button
          className={styles.account}
          key={account.id}
          disabled={loading !== null}
          onClick={() => void choose(account.id)}
        >
          <span>{account.login}</span>
          <small>{account.role}{loading === account.id ? " · проверяем" : ""}</small>
        </button>
      ))}
    </main>
  );
}

export function PhotoImage({ actor, item, photo, itemsBase = "/api/wardrobe/items" }: {
  actor: Actor;
  item: WardrobeItem;
  photo: Photo;
  itemsBase?: string;
}) {
  const [url, setUrl] = React.useState("");
  const [failed, setFailed] = React.useState(false);
  const [attempt, setAttempt] = React.useState(0);
  const objectUrl = React.useRef("");

  React.useEffect(() => {
    const controller = new AbortController();
    setUrl("");
    setFailed(false);
    fetch(`${itemsBase}/${item.id}/photos/${photo.id}/content`, {
      headers: { "X-User-Id": String(actor.id) },
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) throw new Error("photo");
        return response.blob();
      })
      .then((blob) => {
        if (!controller.signal.aborted) {
          objectUrl.current = URL.createObjectURL(blob);
          setUrl(objectUrl.current);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });

    return () => {
      controller.abort();
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = "";
    };
  }, [actor.id, item.id, photo.id, attempt, objectUrl, itemsBase]);

  function imageFailed() {
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = "";
    setUrl("");
    setFailed(true);
  }

  if (failed) {
    return (
      <button
        className={styles.photoRetry}
        type="button"
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
          setAttempt((n) => n + 1);
        }}
      >
        Фото не загрузилось · повторить
      </button>
    );
  }
  if (!url) return <span className={styles.photoEmpty}>Загружаем фото…</span>;
  return (
    <img
      className={styles.photo}
      src={url}
      alt={`${item.name} · ${item.color}`}
      onError={imageFailed}
    />
  );
}

function ItemCard({ actor, item, categories, selected, onToggle, disabled }: {
  actor: Actor;
  item: WardrobeItem;
  categories: Map<number, string>;
  selected: boolean;
  onToggle: () => void;
  disabled: boolean;
}) {
  const photos = useQuery({
    queryKey: ["photos", actor.id, item.id],
    queryFn: ({ signal }) => request<Photo[]>(
      `/api/wardrobe/items/${item.id}/photos`,
      actor.id,
      { signal },
    ),
  });
  const photo = photos.data?.[0];

  return (
    <label className={`${styles.card} ${selected ? styles.selected : ""}`}>
      <span className={styles.image}>
        {photo ? (
          <PhotoImage actor={actor} item={item} photo={photo} />
        ) : photos.isError ? (
          <button
            type="button"
            className={styles.photoRetry}
            onClick={(event) => {
              event.preventDefault();
              event.stopPropagation();
              void photos.refetch();
            }}
          >Повторить загрузку фото</button>
        ) : photos.data ? (
          <span className={styles.photoEmpty}>Нет фото</span>
        ) : (
          <span className={styles.photoEmpty}>Загружаем фото…</span>
        )}
      </span>
      <span className={styles.cardInfo}>
        <span className={styles.itemName}>{item.name}</span>
        <small>{categories.get(item.categoryId) ?? "Вещь"} · {item.color}</small>
        <small>{availabilityLabel(item)}</small>
      </span>
      <input
        type="checkbox"
        checked={selected}
        disabled={disabled}
        onChange={onToggle}
        aria-label={`Выбрать: ${item.name}`}
      />
    </label>
  );
}

export function Today({ account, onSwitch, onPending, pending }: {
  account: Actor;
  onSwitch: () => void;
  onPending: (pending: boolean) => void;
  pending: boolean;
}) {
  if (account.role !== "USER") {
    return <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="wardrobe">
      <main className={styles.main} id="main"><header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span></header>
        <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШЕ ПРОСТРАНСТВО</p>
          <h1>Гардероб</h1><p className={styles.muted}>Экран «Сегодня» доступен для аккаунтов USER.</p></div></section>
      </main>
    </SiteShell>;
  }
  return (
    <TodayForUser
      account={account}
      onSwitch={onSwitch}
      onPending={onPending}
      pending={pending}
    />
  );
}

export function SiteShell({ account, onSwitch, pending, active, children }: {
  account: Actor;
  onSwitch: () => void;
  pending: boolean;
  active: "today" | "wardrobe" | "outfits" | "capsules" | "access" | "clients" | "accounts" | "preferences" | "calendar" | "requests";
  context?: WorkspaceContext;
  children: React.ReactNode;
}) {
  const wardrobePath = "/wardrobe";
  const outfitsPath = "/outfits";
  function guard(event: React.MouseEvent<HTMLAnchorElement>) {
    if (pending) event.preventDefault();
  }

  return (
    <div className={styles.shell} id="main" onClickCapture={(event) => {
      if (pending && (event.target as HTMLElement).closest("a")) {
        event.preventDefault();
        event.stopPropagation();
      }
    }}>
      <aside className={styles.sidebar}>
        <Link className={styles.brand} to={account.role === "USER" ? "/today" : account.role === "STYLIST" ? "/clients" : "/accounts"} onClick={guard} aria-disabled={pending}>
          <span className={styles.brandMark}>ca</span><span>clothes advisor</span>
        </Link>
        <p className={styles.navLabel}>ВАШЕ ПРОСТРАНСТВО</p>
        <nav className={styles.navigation} aria-label="Основная навигация">
        {account.role === "USER" && (
          <Link
            className={active === "today" ? styles.activeNav : styles.navLink}
            to="/today"
            aria-current={active === "today" ? "page" : undefined}
            aria-disabled={pending}
            onClick={guard}
          >Сегодня <span>↗</span></Link>
        )}
        <Link
          className={active === "wardrobe" ? styles.activeNav : styles.navLink}
          to={wardrobePath}
          aria-current={active === "wardrobe" ? "page" : undefined}
          aria-disabled={pending}
          onClick={guard}
        >Гардероб <span>↗</span></Link>
        <Link className={active === "outfits" ? styles.activeNav : styles.navLink} to={outfitsPath} aria-current={active === "outfits" ? "page" : undefined} aria-disabled={pending} onClick={guard}>Образы <span>↗</span></Link>
        {account.role === "USER" && <Link className={active === "capsules" ? styles.activeNav : styles.navLink} to="/capsules" aria-current={active === "capsules" ? "page" : undefined} aria-disabled={pending} onClick={guard}>Капсулы <span>↗</span></Link>}
        {account.role === "USER" && <Link className={active === "calendar" ? styles.activeNav : styles.navLink} to="/calendar" aria-current={active === "calendar" ? "page" : undefined} aria-disabled={pending} onClick={guard}>Календарь <span>↗</span></Link>}
        {([{ role: "USER", path: "preferences", label: "Предпочтения" },
          { role: "USER", path: "access", label: "Доступ стилистам" },
          { role: "USER", path: "requests", label: "Запросы стилисту" },
          { role: "STYLIST", path: "clients", label: "Клиенты" },
          { role: "ADMIN", path: "accounts", label: "Аккаунты" }]).filter((entry) => entry.role === account.role).map((entry) => (
          <Link key={entry.path} className={active === entry.path ? styles.activeNav : styles.navLink}
            to={`/${entry.path}`} aria-current={active === entry.path ? "page" : undefined}
            aria-disabled={pending} onClick={guard}>{entry.label} <span>↗</span></Link>
        ))}
        </nav>
        <div className={styles.sidebarBottom}>
          <p className={styles.navLabel}>АККАУНТ</p>
          <span className={styles.accountName}>{account.login}</span>
          <button className={styles.textButton} disabled={pending} onClick={onSwitch}>
            Сменить аккаунт
          </button>
        </div>
      </aside>
      {children}
    </div>
  );
}

function TodayForUser({ account, onSwitch, onPending, pending }: {
  account: Actor;
  onSwitch: () => void;
  onPending: (pending: boolean) => void;
  pending: boolean;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = React.useState("");
  const [occasion, setOccasion] = React.useState("");
  const [preferredStyle, setPreferredStyle] = React.useState("");
  const [temperature, setTemperature] = React.useState("15");
  const [wind, setWind] = React.useState("0");
  const [precipitationId, setPrecipitationId] = React.useState("");
  const [selected, setSelected] = React.useState<Map<number, WardrobeItem>>(() => new Map());
  const [page, setPage] = React.useState(0);
  const [result, setResult] = React.useState<{
    outfit: Outfit;
    items: WardrobeItem[];
  } | null>(null);
  const [resultError, setResultError] = React.useState("");
  const [resultLoading, setResultLoading] = React.useState(false);
  const [savedOutfit, setSavedOutfit] = React.useState(false);
  const [failure, setFailure] = React.useState<string | null>(null);
  const sending = React.useRef(false);
  const resultController = React.useRef<AbortController | null>(null);

  React.useEffect(() => () => {
    resultController.current?.abort();
    void queryClient.cancelQueries();
  }, [queryClient]);

  const weatherTypes = useQuery({
    queryKey: ["precipitation", account.id],
    queryFn: ({ signal }) => allPages<Precipitation>(
      "/api/weather/precipitation-types",
      undefined,
      signal,
    ),
  });
  const categoryQuery = useQuery({
    queryKey: ["categories", account.id],
    queryFn: ({ signal }) => allPages<{ id: number; name: string }>(
      "/api/wardrobe/categories",
      undefined,
      signal,
    ),
  });
  const itemsQuery = useQuery({
    queryKey: ["items", account.id, page],
    queryFn: ({ signal }) => request<WardrobeItem[]>(
      `/api/wardrobe/items?page=${page}&size=${pageSize}`,
      account.id,
      { signal },
    ),
  });
  React.useEffect(() => {
    const pageItems = itemsQuery.data;
    if (!pageItems) return;
    setSelected((old) => {
      let changed = false;
      const next = new Map(old);
      for (const item of pageItems) {
        if (next.has(item.id) && next.get(item.id) !== item) {
          next.set(item.id, item);
          changed = true;
        }
      }
      return changed ? next : old;
    });
  }, [itemsQuery.data]);
  const categories = new Map(
    (categoryQuery.data ?? []).map((category) => [category.id, category.name]),
  );
  const knownItems = new Map(selected);
  for (const item of itemsQuery.data ?? []) if (knownItems.has(item.id)) knownItems.set(item.id, item);
  const unavailableSelected = [...selected.keys()].filter((id) => {
    const item = knownItems.get(id);
    return item && itemAvailability(item) !== "AVAILABLE";
  });

  async function loadResult(outfit: Outfit) {
    resultController.current?.abort();
    const controller = new AbortController();
    resultController.current = controller;
    try {
      const rows = await Promise.all(
        outfit.itemIds.map((id) => request<WardrobeItem>(
          `/api/wardrobe/items/${id}`,
          account.id,
          { signal: controller.signal },
        )),
      );
      if (!controller.signal.aborted) {
        setResult({ outfit, items: rows });
        setResultError("");
      }
    } finally {
      if (resultController.current === controller) resultController.current = null;
    }
  }

  function startAnotherOutfit() {
    if (sending.current || resultLoading) return;
    setSavedOutfit(false);
    setResult(null);
    setResultError("");
    setFailure(null);
    setName("");
    setOccasion("");
    setPreferredStyle("");
    setTemperature("15");
    setWind("0");
    setPrecipitationId("");
    setSelected(new Map());
    setPage(0);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sending.current) return;
    if (unavailableSelected.length) {
      setFailure("Снимите выбор с вещей, которые сейчас в стирке или убраны.");
      return;
    }

    const cleanName = name.trim();
    const cleanOccasion = occasion.trim();
    const cleanStyle = preferredStyle.trim();
    if (cleanOccasion.length > 300 || cleanStyle.length > 300) {
      setFailure("Повод и стиль должны содержать не более 300 символов каждый.");
      return;
    }
    const temp = Number(temperature);
    const breeze = Number(wind);
    const hasPrecipitation = weatherTypes.data?.some((type) => type.id === Number(precipitationId));
    if (!cleanName || cleanName.length > 300 || !temperature.trim() || !wind.trim()
      || !Number.isFinite(temp) || !Number.isFinite(breeze) || breeze < 0 || !hasPrecipitation) {
      setFailure("Проверьте название, погоду и вид осадков.");
      return;
    }

    sending.current = true;
    onPending(true);
    setFailure(null);
    setResultError("");
    setResult(null);
    const body: {
      name: string;
      weather: { temperatureC: number; windSpeedMps: number; precipitationTypeId: number };
      candidateItemIds?: number[];
      occasion?: string;
      preferredStyle?: string;
    } = {
      name: cleanName,
      weather: {
        temperatureC: temp,
        windSpeedMps: breeze,
        precipitationTypeId: Number(precipitationId),
      },
    };
    if (selected.size) body.candidateItemIds = [...selected.keys()];
    if (cleanOccasion) body.occasion = cleanOccasion;
    if (cleanStyle) body.preferredStyle = cleanStyle;

    try {
      const outfit = await createRequest<Outfit>("/api/outfits/ai", account.id, JSON.stringify(body));
      setSavedOutfit(true);
      setResult({ outfit, items: [] });
      try {
        await loadResult(outfit);
      } catch {
        setResultError("Образ сохранён, но не удалось загрузить его вещи.");
      }
    } catch (error) {
      setFailure(mutationErrorMessage(error));
    } finally {
      sending.current = false;
      onPending(false);
    }
  }

  return (
    <SiteShell account={account} onSwitch={onSwitch} pending={pending} active="today">
      <main className={styles.main}>
        <header className={styles.top}>
          <span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span>
          <span>ВАШ ГАРДЕРОБ</span>
        </header>
        <section className={styles.intro}>
          <div>
            <p className={styles.eyebrow}>НЕМНОГО ЯСНОСТИ НА СЕГОДНЯ</p>
            <h1>Что надеть<br />сегодня?</h1>
            <p className={styles.muted}>
              Продуманный образ из вещей, которые у вас уже есть.
            </p>
          </div>
          <div className={styles.weatherNote}>
            <span className={styles.sun}>◌</span><span>ПОГОДА — ВАШ ВЫБОР</span>
            <small>Укажите условия ниже</small>
          </div>
        </section>

        <form onSubmit={(event) => void submit(event)}>
          <section className={styles.formTop}>
            <div className={styles.sectionHeading}>
              <span>01</span>
              <div>
                <h2>Задайте контекст</h2>
                <p>Назовите образ, укажите погоду и при желании добавьте повод и стиль.</p>
              </div>
            </div>
            <label className={styles.nameField}>
              НАЗВАНИЕ ОБРАЗА
              <input
                required
                maxLength={300}
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Неспешное утро в городе"
                disabled={pending || savedOutfit}
              />
            </label>
            <label className={styles.nameField}>
              ПОВОД (НЕОБЯЗАТЕЛЬНО)
              <input
                maxLength={300}
                value={occasion}
                onChange={(event) => setOccasion(event.target.value)}
                placeholder="Прогулка, работа или встреча с друзьями"
                disabled={pending || savedOutfit}
              />
            </label>
            <label className={styles.nameField}>
              ЖЕЛАЕМЫЙ СТИЛЬ (НЕОБЯЗАТЕЛЬНО)
              <input
                maxLength={300}
                value={preferredStyle}
                onChange={(event) => setPreferredStyle(event.target.value)}
                placeholder="Например, спокойный повседневный"
                disabled={pending || savedOutfit}
              />
            </label>
            <div className={styles.weatherFields}>
              <label>ТЕМПЕРАТУРА <span>°C</span>
                <input
                  type="number"
                  step="any"
                  required
                  value={temperature}
                  onChange={(event) => setTemperature(event.target.value)}
                  disabled={pending || savedOutfit}
                />
              </label>
              <label>ВЕТЕР <span>м/с</span>
                <input
                  type="number"
                  step="any"
                  min="0"
                  required
                  value={wind}
                  onChange={(event) => setWind(event.target.value)}
                  disabled={pending || savedOutfit}
                />
              </label>
              <label>ОСАДКИ
                <select
                  value={precipitationId}
                  onChange={(event) => setPrecipitationId(event.target.value)}
                  disabled={pending || savedOutfit || !weatherTypes.data}
                  required
                >
                  <option value="">Выберите</option>
                  {weatherTypes.data?.map((type) => (
                    <option key={type.id} value={type.id}>{type.name}</option>
                  ))}
                </select>
              </label>
            </div>
            {weatherTypes.isError && (
              <ReadError error={weatherTypes.error} retry={() => void weatherTypes.refetch()} />
            )}
            {categoryQuery.isError && (
              <ReadError error={categoryQuery.error} retry={() => void categoryQuery.refetch()} />
            )}
          </section>

          <section className={styles.wardrobe}>
            <div className={styles.sectionHeading}>
              <span>02</span>
              <div>
                <h2>Выберите вещи</h2>
                <p>
                  Отметьте вещи или оставьте выбор пустым, чтобы искать по всему гардеробу.
                </p>
              </div>
              <span className={styles.count}>
                {selected.size ? `Выбрано: ${selected.size}` : "Весь гардероб"}
              </span>
            </div>
            {itemsQuery.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
            {itemsQuery.isError && (
              <ReadError error={itemsQuery.error} retry={() => void itemsQuery.refetch()} />
            )}
            {itemsQuery.data?.length === 0 && (
              <p className={styles.state}>В гардеробе пока нет вещей.</p>
            )}
            <div className={styles.grid}>
              {itemsQuery.data?.map((item) => (
                <ItemCard
                  key={item.id}
                  actor={account}
                  item={item}
                  categories={categories}
                  selected={selected.has(item.id)}
                  disabled={pending || savedOutfit || (!selected.has(item.id) && itemAvailability(item) !== "AVAILABLE")}
                  onToggle={() => setSelected((old) => {
                    const next = new Map(old);
                    if (next.has(item.id)) next.delete(item.id);
                    else next.set(item.id, item);
                    return next;
                  })}
                />
              ))}
            </div>
            <div className={styles.pages}>
              <span>Страница {page + 1}</span>
              <div>
                <button
                  type="button"
                  disabled={!page || pending || savedOutfit}
                  onClick={() => setPage((n) => n - 1)}
                >← Назад</button>
                <button
                  type="button"
                  disabled={!itemsQuery.data || itemsQuery.data.length < pageSize || pending || savedOutfit}
                  onClick={() => setPage((n) => n + 1)}
                >Далее →</button>
              </div>
            </div>
          </section>

          {failure && <div role="alert" className={styles.error}><p>{failure}</p></div>}
          {failure && <NewAttempt actorId={account.id} path="/api/outfits/ai" disabled={pending} onReset={() => setFailure(null)} />}
          <div className={styles.submitRow}>
            <p>
              Образ сохранится сразу. Запросы не повторяются автоматически.
            </p>
            <button
              className={styles.create}
              type="submit"
              disabled={pending || savedOutfit || !weatherTypes.data || !itemsQuery.data}
            >
              {pending ? "Создаём образ…" : "Создать образ на сегодня"}
              <span>↗</span>
            </button>
          </div>
          <p className={styles.notice}>
            При подборе фотографии вещей передаются OpenAI. После исключения вещей в стирке, убранных и без фото
            должно остаться от 1 до 20 кандидатов. Доступность AI зависит от настроек сервера;
            запрос может занять до 60 секунд.
          </p>
        </form>

        {result && (
          <section className={styles.result} aria-live="polite">
            <p className={styles.eyebrow}>ОБРАЗ СОХРАНЁН · №{result.outfit.id}</p>
            <h2>{result.outfit.name}</h2>
            {result.outfit.occasion && <p>Повод: {result.outfit.occasion}</p>}
            {result.outfit.preferredStyle && <p>Стиль: {result.outfit.preferredStyle}</p>}
            {result.outfit.explanation && (
              <section aria-label="Почему этот образ">
                <h3>Почему этот образ</h3>
                <p>{result.outfit.explanation}</p>
              </section>
            )}
            <p>
              {result.outfit.weather.temperatureC} °C · ветер {result.outfit.weather.windSpeedMps} м/с ·{" "}
              {weatherTypes.data?.find(
                (type) => type.id === result.outfit.weather.precipitationTypeId,
              )?.name ?? "Погода"}
            </p>
            {resultError ? (
              <div role="alert" className={styles.error}>
                <p>{resultError}</p>
                <button
                  type="button"
                  disabled={resultLoading}
                  onClick={() => {
                    setResultLoading(true);
                    void loadResult(result.outfit)
                      .catch(() => setResultError(
                        "Образ сохранён, но его вещи пока недоступны.",
                      ))
                      .finally(() => setResultLoading(false));
                  }}
                >Повторить загрузку вещей</button>
              </div>
            ) : result.items.length === result.outfit.itemIds.length ? (
              <p>Сохранено вещей: {result.items.length}.</p>
            ) : (
              <p>Загружаем сохранённые вещи…</p>
            )}
            <div className={styles.grid}>
              {result.items.map((item) => <ResultCard key={item.id} actor={account} item={item} />)}
            </div>
            <Link className={styles.textButton} to={`/outfits/${result.outfit.id}`}>Открыть сохранённый образ</Link>
            <button className={styles.textButton} type="button" disabled={pending || resultLoading} onClick={startAnotherOutfit}>
              Составить ещё один образ
            </button>
          </section>
        )}
      </main>
    </SiteShell>
  );
}

function ResultCard({ actor, item }: { actor: Actor; item: WardrobeItem }) {
  const photos = useQuery({
    queryKey: ["photos", actor.id, item.id],
    queryFn: ({ signal }) => request<Photo[]>(`/api/wardrobe/items/${item.id}/photos`, actor.id, { signal }),
  });

  return (
    <article className={styles.card}>
      <span className={styles.image}>
        {photos.data?.[0] ? (
          <PhotoImage actor={actor} item={item} photo={photos.data[0]} />
        ) : photos.isError ? (
          <button type="button" className={styles.photoRetry} onClick={() => void photos.refetch()}>
            Повторить загрузку фото
          </button>
        ) : photos.data ? (
          <span className={styles.photoEmpty}>Нет фото</span>
        ) : (
          <span className={styles.photoEmpty}>Загружаем фото…</span>
        )}
      </span>
      <span className={styles.cardInfo}>
        <span className={styles.itemName}>{item.name}</span>
        <small>{item.color} · {item.material}</small>
        <small>{availabilityLabel(item)}</small>
      </span>
    </article>
  );
}
