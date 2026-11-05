import React from "react";
import { authenticatedFetch } from "./auth";
import { createRequest, NewAttempt } from "./idempotency";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  allPages,
  mutationErrorMessage,
  request,
  resourceErrorMessage,
  type Actor,
  type Outfit,
  type Photo,
  type Precipitation,
  type WardrobeItem,
  availabilityLabel,
  itemAvailability,
} from "./api";
import styles from "./site.module.css";
import { SiteShell } from "./site-shell";
import { Dialog } from "./dialog";
import todayStyles from "./today.module.css";
export { SiteShell } from "./site-shell";

const pageSize = 12;

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <div className={styles.error} role="alert">
      <p>{resourceErrorMessage(error)}</p>
      <button type="button" onClick={retry}>Повторить загрузку</button>
    </div>
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
    authenticatedFetch(`${itemsBase}/${item.id}/photos/${photo.id}/content`, actor.id, {
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

function ItemCard({ actor, item, categories, selected, onToggle, disabled, showCheckbox = true }: {
  actor: Actor;
  item: WardrobeItem;
  categories: Map<number, string>;
  selected: boolean;
  onToggle: () => void;
  disabled: boolean;
  showCheckbox?: boolean;
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
  const Card = showCheckbox ? "label" : "article";

  return (
    <Card className={`${styles.card} ${showCheckbox && selected ? styles.selected : ""} ${!showCheckbox ? todayStyles.previewCard : ""}`}>
      <span className={`${styles.image} ${!showCheckbox ? todayStyles.previewImage : ""}`}>
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
      <span className={`${styles.cardInfo} ${!showCheckbox ? todayStyles.previewInfo : ""}`}>
        <span className={`${styles.itemName} ${!showCheckbox ? todayStyles.previewName : ""}`}>{item.name}</span>
        <small>{categories.get(item.categoryId) ?? "Вещь"} · {item.color}</small>
        <small>{availabilityLabel(item)}</small>
      </span>
      {showCheckbox && <input
        type="checkbox"
        checked={selected}
        disabled={disabled}
        onChange={onToggle}
        aria-label={`Выбрать: ${item.name}`}
      />}
    </Card>
  );
}

export function Today({ account, onLogout, onPending, pending }: {
  account: Actor;
  onLogout: () => void;
  onPending: (pending: boolean) => void;
  pending: boolean;
}) {
  if (account.role !== "USER") {
    return <SiteShell account={account} onLogout={onLogout} pending={pending} active="wardrobe">
      <main className={styles.main} id="main"><header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span></header>
        <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШЕ ПРОСТРАНСТВО</p>
          <h1>Гардероб</h1><p className={styles.muted}>Экран «Сегодня» доступен для аккаунтов USER.</p></div></section>
      </main>
    </SiteShell>;
  }
  return (
    <TodayForUser
      account={account}
      onLogout={onLogout}
      onPending={onPending}
      pending={pending}
    />
  );
}

function TodayForUser({ account, onLogout, onPending, pending }: {
  account: Actor;
  onLogout: () => void;
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
  const [candidatesOpen, setCandidatesOpen] = React.useState(false);
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
  const previewQuery = useQuery({
    queryKey: ["items", account.id, 0],
    queryFn: ({ signal }) => request<WardrobeItem[]>(
      `/api/wardrobe/items?page=0&size=${pageSize}`, account.id, { signal },
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
    setCandidatesOpen(false);
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
    <SiteShell account={account} onLogout={onLogout} pending={pending} active="today">
      <main className={styles.main}>
        <header className={styles.top}>
          <span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span>
          <span>ВАШ ГАРДЕРОБ</span>
        </header>
        <section className={`${styles.intro} ${todayStyles.intro}`}>
          <div>
            <p className={styles.eyebrow}>НЕМНОГО ЯСНОСТИ НА СЕГОДНЯ</p>
            <h1>Что надеть сегодня?</h1>
            <p className={styles.muted}>
              Продуманный образ из вещей, которые у вас уже есть.
            </p>
          </div>
          <button className={todayStyles.headerAction} type="submit" form="today-form" disabled={pending || savedOutfit || !weatherTypes.data || !previewQuery.data}>
            Подобрать образ
          </button>
        </section>

        <form id="today-form" className={todayStyles.form} onSubmit={(event) => void submit(event)}>
          <div className={todayStyles.columns}>
            <div className={todayStyles.leftColumn}>
              <section className={todayStyles.panel}>
                <div className={todayStyles.panelTitle}><h2>Погода и планы</h2><span>Ввод вручную</span></div>
                <div className={todayStyles.weatherHeading}><span aria-hidden="true">☼</span><div><strong>{temperature ? `${Number(temperature) > 0 ? "+" : ""}${temperature}°` : "Укажите погоду"}</strong><small>{weatherTypes.data?.find((type) => String(type.id) === precipitationId)?.name ?? "Выберите осадки"} · ветер {wind || "—"} м/с</small></div></div>
                <div className={todayStyles.weatherFields}>
              <label>Температура, °C
                <input
                  aria-label="ТЕМПЕРАТУРА °C"
                  type="number"
                  step="any"
                  required
                  value={temperature}
                  onChange={(event) => setTemperature(event.target.value)}
                  disabled={pending || savedOutfit}
                />
              </label>
              <label>Ветер, м/с
                <input
                  aria-label="ВЕТЕР м/с"
                  type="number"
                  step="any"
                  min="0"
                  required
                  value={wind}
                  onChange={(event) => setWind(event.target.value)}
                  disabled={pending || savedOutfit}
                />
              </label>
              <label>Осадки
                <select
                  aria-label="ОСАДКИ"
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
                <div className={todayStyles.divider} />
                <div className={todayStyles.planFields}>
                  <label className={todayStyles.field}>Название образа<input aria-label="НАЗВАНИЕ ОБРАЗА" required maxLength={300} value={name} onChange={(event) => setName(event.target.value)} placeholder="На каждый день" disabled={pending || savedOutfit} /></label>
                  <label className={todayStyles.field}>Куда собираетесь?<small>Необязательно. Поможет учесть ваш день.</small><input aria-label="ПОВОД (НЕОБЯЗАТЕЛЬНО)" maxLength={300} value={occasion} onChange={(event) => setOccasion(event.target.value)} placeholder="Прогулка, работа или встреча" disabled={pending || savedOutfit} /></label>
                  <label className={todayStyles.field}>Пожелания к стилю<small>Можно оставить пустым.</small><input aria-label="ЖЕЛАЕМЫЙ СТИЛЬ (НЕОБЯЗАТЕЛЬНО)" maxLength={300} value={preferredStyle} onChange={(event) => setPreferredStyle(event.target.value)} placeholder="Например, спокойный повседневный" disabled={pending || savedOutfit} /></label>
                </div>
                {weatherTypes.isError && <ReadError error={weatherTypes.error} retry={() => void weatherTypes.refetch()} />}
                {categoryQuery.isError && <ReadError error={categoryQuery.error} retry={() => void categoryQuery.refetch()} />}
                {failure && <div role="alert" className={styles.error}><p>{failure}</p></div>}
                {failure && <NewAttempt actorId={account.id} path="/api/outfits/ai" disabled={pending} onReset={() => setFailure(null)} />}
              </section>
              <div className={todayStyles.submitBlock}>
                <button className={styles.create} type="submit" aria-label="Подобрать образ на сегодня" disabled={pending || savedOutfit || !weatherTypes.data || !previewQuery.data}>
                  {pending ? "Подбираем образ…" : "Подобрать образ на сегодня"}<span>↗</span>
                </button>
                <p>ИИ предложит сочетание и сразу сохранит образ.</p>
              </div>
            </div>
            <section className={todayStyles.preview}>
              <div className={todayStyles.previewHeader}><div><h2>Из вашего гардероба</h2><p>Выберите вещи для подбора</p></div><span>{selected.size ? `Выбрано: ${selected.size}` : "Весь гардероб"}</span></div>
              <div className={todayStyles.segmented}>
                <button type="button" aria-label="Использовать весь гардероб" aria-pressed={!selected.size} onClick={() => setSelected(new Map())} disabled={pending || savedOutfit}>Весь гардероб</button>
                <button type="button" aria-pressed={selected.size > 0} onClick={() => setCandidatesOpen(true)} disabled={pending || savedOutfit}>Выбрать вещи</button>
              </div>
              {previewQuery.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
              {previewQuery.isError && <ReadError error={previewQuery.error} retry={() => void previewQuery.refetch()} />}
              {previewQuery.data?.length === 0 && <p className={styles.state}>В гардеробе пока нет вещей.</p>}
              <div className={todayStyles.previewGrid}>
                {previewQuery.data?.slice(0, 4).map((item) => (
                  <ItemCard key={item.id} actor={account} item={item} categories={categories} selected={selected.has(item.id)} showCheckbox={false}
                    disabled={pending || savedOutfit || (!selected.has(item.id) && itemAvailability(item) !== "AVAILABLE")}
                    onToggle={() => setSelected((old) => { const next = new Map(old); if (next.has(item.id)) next.delete(item.id); else next.set(item.id, item); return next; })} />
                ))}
              </div>
              <p className={todayStyles.tip}>После исключения вещей в стирке, убранных и без фото должно остаться от 1 до 20 кандидатов. Без выбора используем весь подходящий гардероб.</p>
            </section>
          </div>
          {candidatesOpen && (
            <Dialog open title="Выбрать вещи" className={todayStyles.candidateDialog} onClose={() => setCandidatesOpen(false)}>
              <p className={todayStyles.dialogSummary}>{selected.size ? `Выбрано вещей: ${selected.size}` : "Выбор пуст — подойдут все доступные вещи."}</p>
              <button type="button" className={todayStyles.clearButton} onClick={() => setSelected(new Map())} disabled={!selected.size}>Весь гардероб</button>
              {itemsQuery.isLoading && <p>Загружаем гардероб…</p>}
              {itemsQuery.isError && <ReadError error={itemsQuery.error} retry={() => void itemsQuery.refetch()} />}
              {itemsQuery.data?.length === 0 && <p>В гардеробе пока нет вещей.</p>}
              <div className={todayStyles.dialogGrid}>{itemsQuery.data?.map((item) => (
                <ItemCard key={item.id} actor={account} item={item} categories={categories} selected={selected.has(item.id)}
                  disabled={pending || savedOutfit || (!selected.has(item.id) && itemAvailability(item) !== "AVAILABLE")}
                  onToggle={() => setSelected((old) => { const next = new Map(old); if (next.has(item.id)) next.delete(item.id); else next.set(item.id, item); return next; })} />
              ))}</div>
              <div className={styles.pages}><span>Страница {page + 1}</span><div>
                <button type="button" disabled={!page || pending || savedOutfit} onClick={() => setPage((n) => n - 1)}>← Назад</button>
                <button type="button" disabled={!itemsQuery.data || itemsQuery.data.length < pageSize || pending || savedOutfit} onClick={() => setPage((n) => n + 1)}>Далее →</button>
              </div></div>
              <button type="button" className={todayStyles.doneButton} onClick={() => setCandidatesOpen(false)}>Готово</button>
            </Dialog>
          )}
          {unavailableSelected.length > 0 && <p className={todayStyles.unavailable} role="status">Снимите выбор с вещей, которые сейчас в стирке или убраны.</p>}
          <p className={styles.notice}>Фотографии вещей передаются OpenAI для подбора образа. Доступность AI зависит от настроек сервера; запрос может занять до 60 секунд.</p>
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
