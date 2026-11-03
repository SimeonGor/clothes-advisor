import React from "react";
import { createRequest, NewAttempt } from "./idempotency";
import { OutfitReplacement } from "./outfit-replacement";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  allPages,
  ApiError,
  resourceErrorMessage,
  outfitsPath,
  request,
  wardrobeItemsPath,
  type Actor,
  type Outfit,
  type Precipitation,
  type WardrobeItem,
  type WorkspaceContext,
} from "./api";
import { WardrobeItemPicker } from "./wardrobe-item-picker";
import styles from "./site.module.css";
import collectionStyles from "./collection.module.css";
import { WardrobeComposition } from "./wardrobe-composition";

const size = 12;
type HistoryRow = {
  stylistId: number;
  vote: "LIKE" | "DISLIKE";
  version: number;
  modifiedAt: string;
  archivedAt: string | null;
};

const contextKey = (context: WorkspaceContext) => context.kind === "own"
  ? "own"
  : `${context.kind}:${context.ownerId}`;
const routeBase = (context: WorkspaceContext) => context.kind === "own"
  ? "/outfits"
  : context.kind === "client"
    ? `/clients/${context.ownerId}/outfits`
    : `/accounts/${context.ownerId}/outfits`;
const canCreate = (context: WorkspaceContext) => context.kind !== "admin";
const canDelete = (context: WorkspaceContext) => context.kind !== "client";

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <div className={styles.error} role="alert">
      <p>{resourceErrorMessage(error)}</p>
      <button type="button" onClick={retry}>Повторить загрузку</button>
    </div>
  );
}

export function Outfits({ actor, context = { kind: "own" }, pending }: { actor: Actor; context?: WorkspaceContext; pending: boolean }) {
  const [page, setPage] = React.useState(0);
  const base = outfitsPath(context);
  const query = useQuery({
    queryKey: ["outfits", actor.id, contextKey(context), page],
    queryFn: ({ signal }) => request<Outfit[]>(`${base}?page=${page}&size=${size}`, actor.id, { signal }),
  });
  const canAdd = canCreate(context);

  return (
    <main className={styles.main}>
      <header className={`${styles.top} ${collectionStyles.compactTop}`}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>СОХРАНЁННЫЕ ОБРАЗЫ</span></header>
      <section className={styles.intro}>
        <div>
          <p className={styles.eyebrow}>{context.kind === "own" ? "ВАШИ СОЧЕТАНИЯ" : "СОЧЕТАНИЯ ВЛАДЕЛЬЦА"}</p>
          <h1>Образы</h1>
          <p className={styles.muted}>Сохранённые сочетания и заметки стилистов.</p>
        </div>
        {canAdd && <Link className={styles.create} to={`${routeBase(context)}/new`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Создать образ <span>↗</span></Link>}
      </section>
      {query.isLoading && <p className={styles.state}>Загружаем образы…</p>}
      {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
      {query.data?.length === 0 && page > 0 && <p className={styles.state}>На этой странице нет образов. Вернитесь на предыдущую.</p>}
      {query.data?.length === 0 && page === 0 && (
        <section className={styles.empty}>
          <h2>Пока нет сохранённых образов</h2>
          <p>{context.kind === "own" ? "Здесь появятся сохранённые образы." : "Владелец пока не сохранил образы."}</p>
          {canAdd && <Link className={styles.create} to={`${routeBase(context)}/new`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Создать первый образ <span>↗</span></Link>}
        </section>
      )}
      <div className={collectionStyles.catalog}>
        {query.data?.map((outfit) => <OutfitCard key={outfit.id} actor={actor} context={context} outfit={outfit} pending={pending} />)}
      </div>
      {(query.data?.length || page > 0) ? (
        <div className={styles.pages}>
          <span>Страница {page + 1}</span>
          <div>
            <button type="button" disabled={!page || pending} onClick={() => setPage((n) => n - 1)}>← Назад</button>
            <button type="button" disabled={!query.data || query.data.length < size || pending} onClick={() => setPage((n) => n + 1)}>Далее →</button>
          </div>
        </div>
      ) : null}
    </main>
  );
}

function OutfitCard({ actor, context, outfit, pending }: { actor: Actor; context: WorkspaceContext; outfit: Outfit; pending: boolean }) {
  const base = wardrobeItemsPath(context);
  const items = useQuery({
    queryKey: ["outfit-items", actor.id, contextKey(context), outfit.id],
    enabled: outfit.itemIds.length > 0,
    queryFn: ({ signal }) => Promise.all(outfit.itemIds.map((id) => request<WardrobeItem>(`${base}/${id}`, actor.id, { signal }))),
  });
  return (
    <Link className={collectionStyles.card} to={`${routeBase(context)}/${outfit.id}`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>
      <WardrobeComposition actor={actor} items={items.data?.slice(0, 4) ?? []} context={context} itemsBase={base} />
      <span className={collectionStyles.details}>
        <span className={collectionStyles.title}>{outfit.name}</span>
        <small>{items.isError ? "Не удалось загрузить вещи" : items.isLoading ? "Загружаем вещи…" : items.data?.map((item) => item.name).join(" · ") || "Нет вещей"}</small>
        <small>{sourceName(outfit.source)} · {outfit.likes} нравится · {outfit.dislikes} не нравится</small>
      </span>
    </Link>
  );
}

function sourceName(source: Outfit["source"]) { return source === "AI" ? "AI" : source === "STYLIST" ? "Стилист" : "Вручную"; }

export function OutfitEditor({ actor, context = { kind: "own" }, onPending, pending }: {
  actor: Actor;
  context?: WorkspaceContext;
  onPending: (value: boolean) => void;
  pending: boolean;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const base = outfitsPath(context);
  const itemsBase = wardrobeItemsPath(context);
  const [name, setName] = React.useState("");
  const [temperature, setTemperature] = React.useState("15");
  const [wind, setWind] = React.useState("0");
  const [precipitationId, setPrecipitationId] = React.useState("");
  const [selected, setSelected] = React.useState<number[]>([]);
  const [failure, setFailure] = React.useState("");
  const busy = React.useRef(false);
  const types = useQuery({
    queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal),
  });
  const items = useQuery({
    queryKey: ["outfit-items", actor.id, contextKey(context)],
    queryFn: ({ signal }) => allPages<WardrobeItem>(itemsBase, actor.id, signal),
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending) return;
    const cleanName = name.trim();
    const temp = Number(temperature);
    const breeze = Number(wind);
    const precipitationTypeId = Number(precipitationId);
    const validPrecipitation = types.data?.some((type) => type.id === precipitationTypeId);
    if (!cleanName || cleanName.length > 300 || !temperature.trim() || !wind.trim()
      || !Number.isFinite(temp) || !Number.isFinite(breeze) || breeze < 0 || !validPrecipitation
      || selected.length < 1 || selected.length > 50 || new Set(selected).size !== selected.length) {
      setFailure("Проверьте название, погоду и выбор от 1 до 50 разных вещей.");
      return;
    }

    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      const saved = await createRequest<Outfit>(base, actor.id, JSON.stringify({
          name: cleanName,
          itemIds: selected,
          weather: { temperatureC: temp, windSpeedMps: breeze, precipitationTypeId },
        }));
      await queryClient.invalidateQueries({ queryKey: ["outfits", actor.id, contextKey(context)] });
      navigate(`${routeBase(context)}/${saved.id}`);
    } catch (error) {
      const message = error instanceof ApiError && error.status === 409 && !error.code?.startsWith("idempotency_")
        ? "Список вещей изменился. Обновите его и проверьте выбор."
        : `Не удалось сохранить образ. ${resourceErrorMessage(error, true)} Данные формы сохранены.`;
      setFailure(message);
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  return (
    <main className={styles.main}>
      <header className={`${styles.top} ${collectionStyles.compactTop}`}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>НОВЫЙ ОБРАЗ</span></header>
      <section className={styles.intro}>
        <div>
          <p className={styles.eyebrow}>СОБЕРИТЕ СОЧЕТАНИЕ</p>
          <h1>Новый образ</h1>
          <p className={styles.muted}>Выберите вещи и сохраните погоду, в которой образ пригодится.</p>
        </div>
      </section>
      <form className={collectionStyles.formPanel} onSubmit={(event) => void submit(event)}>
        <section className={styles.formTop}>
          <label className={styles.nameField}>НАЗВАНИЕ ОБРАЗА
            <input required maxLength={300} value={name} onChange={(event) => setName(event.target.value)} disabled={pending} />
          </label>
          <div className={styles.weatherFields}>
            <label>ТЕМПЕРАТУРА °C
              <input type="number" step="any" required value={temperature} onChange={(event) => setTemperature(event.target.value)} disabled={pending} />
            </label>
            <label>ВЕТЕР м/с
              <input type="number" step="any" min="0" required value={wind} onChange={(event) => setWind(event.target.value)} disabled={pending} />
            </label>
            <label>ОСАДКИ
              <select required value={precipitationId} onChange={(event) => setPrecipitationId(event.target.value)} disabled={!types.data || pending}>
                <option value="">Выберите</option>
                {types.data?.map((type) => <option key={type.id} value={type.id}>{type.name}</option>)}
              </select>
            </label>
          </div>
          {types.isError && <ReadError error={types.error} retry={() => void types.refetch()} />}
        </section>
        {items.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
        {items.isError && <ReadError error={items.error} retry={() => void items.refetch()} />}
        <WardrobeItemPicker actor={actor} context={context} items={items.isSuccess ? items.data : undefined}
          selected={selected} onSelectedChange={setSelected} disabled={pending || !items.isSuccess} />
        {failure && <div role="alert" className={styles.error}>{failure}</div>}
        {failure && <NewAttempt actorId={actor.id} path={base} disabled={pending} onReset={() => setFailure("")} />}
        <div className={styles.submitRow}>
          <p>Образ будет сохранён сразу. Запрос не повторяется автоматически.</p>
          <button className={styles.create} type="submit" disabled={pending || !items.data || !types.data}>Сохранить образ <span>↗</span></button>
        </div>
      </form>
    </main>
  );
}

export function OutfitDetail({ actor, context = { kind: "own" }, onPending, pending }: { actor: Actor; context?: WorkspaceContext; onPending: (value: boolean) => void; pending: boolean }) {
  const { outfitId } = useParams();
  const id = Number(outfitId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const base = outfitsPath(context);
  const itemsBase = wardrobeItemsPath(context);
  const key = contextKey(context);
  const [confirmDelete, setConfirmDelete] = React.useState(false);
  const [conflict, setConflict] = React.useState(false);
  const [failure, setFailure] = React.useState("");
  const busy = React.useRef(false);
  const outfit = useQuery({
    queryKey: ["outfit", actor.id, key, id],
    enabled: Number.isInteger(id) && id > 0,
    queryFn: ({ signal }) => request<Outfit>(`${base}/${id}`, actor.id, { signal }),
  });
  const history = useQuery({
    queryKey: ["rating-history", actor.id, key, id],
    enabled: context.kind !== "admin",
    queryFn: ({ signal }) => allPages<HistoryRow>(`${base}/${id}/ratings/history`, actor.id, signal),
  });
  const precipitation = useQuery({
    queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal),
  });
  const items = useQuery({
    queryKey: ["outfit-detail-items", actor.id, key, id],
    enabled: !!outfit.data,
    queryFn: ({ signal }) => Promise.all((outfit.data?.itemIds ?? []).map((itemId) => request<WardrobeItem>(`${itemsBase}/${itemId}`, actor.id, { signal }))),
  });

  async function refresh() {
    await Promise.all([outfit.refetch(), ...(context.kind === "admin" ? [] : [history.refetch()])]);
    setConflict(false);
  }

  async function mutate(action: () => Promise<unknown>, success?: () => void) {
    if (busy.current || pending) return;
    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      await action();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["outfit", actor.id, key, id] }),
        queryClient.invalidateQueries({ queryKey: ["outfits", actor.id, key] }),
        queryClient.invalidateQueries({ queryKey: ["rating-history", actor.id, key, id] }),
      ]);
      success?.();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) setConflict(true);
      else setFailure(`Не удалось выполнить действие. ${resourceErrorMessage(error, true)}`);
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  const canRate = context.kind === "client" && actor.role === "STYLIST"
    && !!outfit.data && outfit.data.authorId !== actor.id;
  async function rate(vote: "LIKE" | "DISLIKE") {
    const current = outfit.data?.myRating;
    const method = current ? "PUT" : "POST";
    const body = current ? { vote, version: current.version } : { vote };
    await mutate(() => request(`${base}/${id}/rating`, actor.id, {
      method,
      body: JSON.stringify(body),
    }));
  }
  async function cancelRating() {
    const current = outfit.data?.myRating;
    if (current) await mutate(() => request(`${base}/${id}/rating?version=${current.version}`, actor.id, { method: "DELETE" }));
  }

  if (outfit.isLoading) return <main className={styles.main}><p className={styles.state}>Загружаем образ…</p></main>;
  if (outfit.isError || !outfit.data) return <main className={styles.main}><ReadError error={outfit.error} retry={() => void outfit.refetch()} /></main>;
  const row = outfit.data;
  const precipitationName = precipitation.data?.find((type) => type.id === row.weather.precipitationTypeId)?.name;
  return (
    <main className={styles.main}>
      <header className={`${styles.top} ${collectionStyles.compactTop}`}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>ОБРАЗ №{row.id}</span></header>
      <section className={styles.intro}>
        <div>
          <p className={styles.eyebrow}>{sourceName(row.source)} · {new Date(row.createdAt).toLocaleDateString("ru-RU")}</p>
          <h1>{row.name}</h1>
        </div>
        <Link className={styles.textButton} to={routeBase(context)} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>← К образам</Link>
      </section>
      <div className={collectionStyles.detailLayout}>
        <section>
          {items.isLoading && <p className={styles.state}>Загружаем вещи…</p>}
          {items.isError && <ReadError error={items.error} retry={() => void items.refetch()} />}
          {items.data?.length === 0 && <p className={styles.state}>В этом образе нет вещей.</p>}
          {items.data && <>
      <WardrobeComposition actor={actor} items={items.data} context={context} itemsBase={itemsBase} />
            <div className={collectionStyles.itemList} aria-label="Вещи в образе">
              {items.data.map((item) => <div className={collectionStyles.item} key={item.id}>{item.name}<small>{item.color} · {item.material}</small></div>)}
            </div>
          </>}
        </section>
        <section className={collectionStyles.description}>
          {row.explanation && <section aria-label="Почему этот образ"><h2>Почему этот образ</h2><p>{row.explanation}</p></section>}
          <div className={collectionStyles.weather}>
            <span>{row.weather.temperatureC} °C</span><span>Ветер {row.weather.windSpeedMps} м/с</span><span>{precipitationName ?? "Осадки"}</span>
          </div>
          {row.occasion && <p className={styles.muted}>Повод: {row.occasion}</p>}
          {row.preferredStyle && <p className={styles.muted}>Стиль: {row.preferredStyle}</p>}
          <div className={collectionStyles.ratings} aria-label="Оценки образа">
            <span className={collectionStyles.ratingCount}>{row.likes} нравится</span>
            <span className={collectionStyles.ratingCount}>{row.dislikes} не нравится</span>
          </div>
        </section>
      </div>
      {precipitation.isError && <ReadError error={precipitation.error} retry={() => void precipitation.refetch()} />}
      {context.kind === "own" && actor.role === "USER" && items.data && (
        <OutfitReplacement
          key={`${actor.id}:${row.id}`}
          actor={actor}
          outfit={row}
          sourceItems={items.data}
          pending={pending}
          onPending={onPending}
        />
      )}
      {failure && <div role="alert" className={styles.error}>{failure}</div>}
      {conflict && <div role="alert" className={styles.error}>
        Данные изменились. Обновите образ, затем решите, нужно ли повторить действие.
        <button type="button" disabled={pending} onClick={() => void refresh()}>Обновить</button>
      </div>}
      {canRate && <section className={`${styles.photoSection} ${collectionStyles.section}`}>
        <h2>Оценка стилиста</h2>
        <p className={styles.muted}>Оценить можно только образ другого автора.</p>
        <button type="button" disabled={pending || row.myRating?.vote === "LIKE"} onClick={() => void rate("LIKE")}>Нравится</button>{" "}
        <button type="button" disabled={pending || row.myRating?.vote === "DISLIKE"} onClick={() => void rate("DISLIKE")}>Не нравится</button>
        {row.myRating && <button type="button" disabled={pending} onClick={() => void cancelRating()}>Отменить оценку</button>}
      </section>}
      {context.kind !== "admin" && <section className={`${styles.photoSection} ${collectionStyles.section}`}>
        <h2>История оценок</h2>
        {history.isLoading && <p className={styles.state}>Загружаем историю…</p>}
        {history.isError && <ReadError error={history.error} retry={() => void history.refetch()} />}
        {history.data?.length === 0 && <p className={styles.state}>Оценок пока нет.</p>}
        {history.data?.map((entry) => (
          <p key={`${entry.stylistId}:${entry.version}`}>
            Стилист #{entry.stylistId} · {entry.vote === "LIKE" ? "Нравится" : "Не нравится"}
            {entry.archivedAt ? " · предыдущая оценка" : " · текущая"}
            {` · ${new Date(entry.modifiedAt).toLocaleDateString("ru-RU")}`}
          </p>
        ))}
      </section>}
      {canDelete(context) && <section className={styles.deleteSection}>
        {confirmDelete ? <div role="group" aria-label="Подтверждение удаления">
          <p>Удалить этот образ без возможности восстановления?</p>
          <button type="button" disabled={pending} onClick={() => void mutate(() => request(`${base}/${id}`, actor.id, { method: "DELETE" }), () => navigate(routeBase(context)))}>Да, удалить</button>
          <button type="button" disabled={pending} onClick={() => setConfirmDelete(false)}>Отмена</button>
        </div> : <button className={styles.deleteButton} type="button" disabled={pending} onClick={() => setConfirmDelete(true)}>Удалить образ</button>}
      </section>}
    </main>
  );
}
