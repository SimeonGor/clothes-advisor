import React from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { allPages, ApiError, request, resourceErrorMessage, type Actor, type Precipitation, type StylistRequest, type WardrobeItem, wardrobeItemsPath } from "./api";
import { createRequest, NewAttempt } from "./idempotency";
import { WardrobeItemPicker } from "./wardrobe-item-picker";
import styles from "./site.module.css";
import workspaceStyles from "./workspace.module.css";

const pageSize = 12;
type StylistGrant = { id: number; login: string };
type Props = { actor: Actor; pending: boolean; onPending: (value: boolean) => void; ownerId?: number };
const requestBase = (ownerId?: number) => ownerId === undefined ? "/api/me/stylist-requests" : `/api/stylist/clients/${ownerId}/requests`;
const pagePath = (ownerId?: number) => ownerId === undefined ? "/requests" : `/clients/${ownerId}/requests`;
const listKey = (actor: Actor, ownerId: number | undefined, page: number) => ["stylist-requests", actor.id, ownerId ?? "own", requestBase(ownerId), page];
const detailKey = (actor: Actor, ownerId: number | undefined, id: number) => ["stylist-request", actor.id, ownerId ?? "own", requestBase(ownerId), id];
const statusLabel = (status: StylistRequest["status"]) => status === "PENDING" ? "Ожидает ответа"
  : status === "CANCELLED" ? "Отменён" : "Есть ответ";

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return <div className={styles.error} role="alert"><p>{resourceErrorMessage(error)}</p>
    <button type="button" onClick={retry}>Повторить загрузку</button></div>;
}

function Intro({ title, description }: { title: string; description: string }) {
  return <><header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>ЗАПРОСЫ СТИЛИСТУ</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШИ ЗАПРОСЫ</p>
      <h1>{title}</h1><p className={styles.muted}>{description}</p></div></section></>;
}

function Weather({ row, types }: { row: StylistRequest; types: Precipitation[] }) {
  const name = types.find((type) => type.id === row.weather.precipitationTypeId)?.name ?? "Осадки";
  return <span>{row.weather.temperatureC} °C · ветер {row.weather.windSpeedMps} м/с · {name}</span>;
}

export function StylistRequests({ actor, pending, ownerId }: Props) {
  const [page, setPage] = React.useState(0);
  const base = requestBase(ownerId);
  const query = useQuery({ queryKey: listKey(actor, ownerId, page),
    queryFn: ({ signal }) => request<StylistRequest[]>(`${base}?page=${page}&size=${pageSize}`, actor.id, { signal }) });
  const types = useQuery({ queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal) });
  return <main className={styles.main}>
    <Intro title={ownerId === undefined ? "Запросы стилисту" : "Запросы клиента"} description={ownerId === undefined ? "Запросы, которые вы отправили стилистам с доступом к вашему гардеробу." : "Запросы клиента, адресованные вам."} />
    {ownerId === undefined && <p><Link className={styles.create} to="/requests/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Новый запрос ↗</Link></p>}
    {query.isLoading && <p className={styles.state}>Загружаем запросы…</p>}
    {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
    {types.isError && <ReadError error={types.error} retry={() => void types.refetch()} />}
    {query.isSuccess && query.data.length === 0 && page > 0 && <p className={styles.state}>На этой странице нет запросов. Вернитесь на предыдущую.</p>}
    {query.isSuccess && query.data.length === 0 && page === 0 && <section className={styles.empty}><h2>Пока нет запросов</h2>
      <p>Создайте запрос, чтобы попросить стилиста подобрать образ.</p></section>}
    <section aria-label="Список запросов">
      {query.isSuccess && query.data.map((row) => <Link className={workspaceStyles.requestCard} key={row.id} to={`${pagePath(ownerId)}/${row.id}`} aria-disabled={pending}
        aria-label={`Запрос №${row.id} · стилист №${row.stylistId} · ${statusLabel(row.status)}`}
        onClick={(event) => { if (pending) event.preventDefault(); }}>
        <span className={workspaceStyles.requestTitle}>Запрос №{row.id}</span>
        <span className={workspaceStyles.requestMeta}>Стилист №{row.stylistId} · {new Date(row.createdAt).toLocaleDateString("ru-RU")}</span>
        <span className={workspaceStyles.requestMeta}>{statusLabel(row.status)} · {types.data ? <Weather row={row} types={types.data} /> : `${row.weather.temperatureC} °C · ветер ${row.weather.windSpeedMps} м/с · Осадки`}</span>
        {row.wishes && <span className={workspaceStyles.requestWishes}>{row.wishes}</span>}
      </Link>)}
    </section>
    {(query.isSuccess && query.data.length || page > 0) ? <div className={styles.pages}><span>Страница {page + 1}</span><div>
      <button type="button" disabled={!page || pending} onClick={() => setPage((value) => value - 1)}>← Назад</button>
      <button type="button" disabled={!query.isSuccess || query.data.length < pageSize || pending} onClick={() => setPage((value) => value + 1)}>Далее →</button>
    </div></div> : null}
  </main>;
}

export function StylistRequestEditor({ actor, pending, onPending }: Props) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const grants = useQuery({ queryKey: ["stylist-access", actor.id],
    queryFn: ({ signal }) => allPages<StylistGrant>("/api/me/stylist-access", actor.id, signal) });
  const types = useQuery({ queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal) });
  const [stylistId, setStylistId] = React.useState("");
  const [temperature, setTemperature] = React.useState("15");
  const [wind, setWind] = React.useState("0");
  const [precipitationId, setPrecipitationId] = React.useState("");
  const [wishes, setWishes] = React.useState("");
  const [failure, setFailure] = React.useState("");
  const busy = React.useRef(false);
  const path = "/api/me/stylist-requests";

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending || !grants.isSuccess || !types.isSuccess) return;
    const stylist = Number(stylistId);
    const temp = Number(temperature);
    const breeze = Number(wind);
    const precipitationTypeId = Number(precipitationId);
    if (!grants.data.some((grant) => grant.id === stylist) || !temperature.trim() || !wind.trim()
      || !Number.isFinite(temp) || !Number.isFinite(breeze) || breeze < 0
      || !types.data.some((type) => type.id === precipitationTypeId) || wishes.trim().length > 2000) {
      setFailure("Проверьте стилиста, погоду и пожелания.");
      return;
    }
    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      const saved = await createRequest<StylistRequest>(path, actor.id, JSON.stringify({
        stylistId: stylist, weather: { temperatureC: temp, windSpeedMps: breeze, precipitationTypeId }, wishes: wishes.trim(),
      }));
      await queryClient.invalidateQueries({ queryKey: ["stylist-requests", actor.id] });
      navigate(`/requests/${saved.id}`);
    } catch (error) { setFailure(`Не удалось отправить запрос. ${resourceErrorMessage(error, true)} Пожелания сохранены.`); }
    finally { busy.current = false; onPending(false); }
  }

  const selectedGrantIsCurrent = grants.data?.some((grant) => String(grant.id) === stylistId) ?? false;
  return <main className={styles.main}>
    <Intro title="Новый запрос" description="Выберите стилиста, задайте погоду и добавьте пожелания." />
    <p><Link className={styles.textButton} to="/requests" aria-disabled={pending}>← К запросам</Link></p>
    {grants.isError && <ReadError error={grants.error} retry={() => void grants.refetch()} />}
    {types.isError && <ReadError error={types.error} retry={() => void types.refetch()} />}
    {grants.isSuccess && grants.data.length === 0 && <section className={styles.empty}><h2>Нет доступных стилистов</h2>
      <p>Сначала предоставьте стилисту доступ к гардеробу.</p><Link className={styles.create} to="/access">Открыть доступы ↗</Link></section>}
    <form className={styles.itemForm} onSubmit={(event) => void submit(event)}>
      <label className={styles.nameField}>СТИЛИСТ
        <select required value={stylistId} disabled={pending || grants.isLoading || grants.isError || !grants.data?.length}
          onChange={(event) => setStylistId(event.target.value)}>
          <option value="">Выберите стилиста</option>{grants.isSuccess && grants.data.map((grant) => <option key={grant.id} value={grant.id}>{grant.login} · №{grant.id}</option>)}
        </select>
      </label>
      <div className={styles.weatherFields}>
        <label>ТЕМПЕРАТУРА °C<input type="number" step="any" required value={temperature} onChange={(event) => setTemperature(event.target.value)} disabled={pending} /></label>
        <label>ВЕТЕР м/с<input type="number" step="any" min="0" required value={wind} onChange={(event) => setWind(event.target.value)} disabled={pending} /></label>
        <label>ОСАДКИ<select required value={precipitationId} disabled={pending || !types.isSuccess} onChange={(event) => setPrecipitationId(event.target.value)}>
          <option value="">Выберите</option>{types.data?.map((type) => <option key={type.id} value={type.id}>{type.name}</option>)}
        </select></label>
      </div>
      {types.isLoading && <p className={styles.state}>Загружаем погоду…</p>}
      <label className={styles.nameField}>ПОЖЕЛАНИЯ (НЕОБЯЗАТЕЛЬНО)
        <textarea maxLength={2000} value={wishes} onChange={(event) => setWishes(event.target.value)} disabled={pending} />
      </label>
      {failure && <div className={styles.error} role="alert">{failure}<NewAttempt actorId={actor.id} path={path} disabled={pending} onReset={() => setFailure("")} /></div>}
      <div className={styles.submitRow}><button className={styles.create} type="submit" disabled={pending || !grants.isSuccess || !selectedGrantIsCurrent || !types.isSuccess}>Отправить запрос</button></div>
    </form>
  </main>;
}

export function StylistRequestDetail({ actor, pending, onPending, ownerId }: Props) {
  const { requestId } = useParams();
  const id = Number(requestId);
  const queryClient = useQueryClient();
  const base = requestBase(ownerId);
  const query = useQuery({ queryKey: detailKey(actor, ownerId, id), enabled: Number.isSafeInteger(id) && id > 0,
    queryFn: ({ signal }) => request<StylistRequest>(`${base}/${id}`, actor.id, { signal }) });
  const types = useQuery({ queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal) });
  const [failure, setFailure] = React.useState("");
  const [conflict, setConflict] = React.useState(false);
  const [newAttemptRequired, setNewAttemptRequired] = React.useState(false);
  const [answerAttemptFailed, setAnswerAttemptFailed] = React.useState(false);
  const [answerFailure, setAnswerFailure] = React.useState("");
  const [accessBlocked, setAccessBlocked] = React.useState(false);
  const [answerDraft, setAnswerDraft] = React.useState<AnswerDraft>({ name: "", explanation: "", selected: [], acceptedVersion: 0 });
  const [answerVersionReady, setAnswerVersionReady] = React.useState(false);
  const initializedAnswerVersion = React.useRef(false);
  const busy = React.useRef(false);
  React.useEffect(() => {
    if (ownerId !== undefined && query.isSuccess && query.data && !initializedAnswerVersion.current) {
      initializedAnswerVersion.current = true;
      setAnswerDraft((draft) => ({ ...draft, acceptedVersion: query.data.version }));
      setAnswerVersionReady(true);
    }
  }, [ownerId, query.isSuccess, query.data]);

  async function reload() {
    if (busy.current || pending) return;
    busy.current = true;
    onPending(true);
    try {
      const fresh = await query.refetch();
      if (fresh.isError || !fresh.data) throw fresh.error ?? new ApiError(404);
      if (ownerId !== undefined && (!answerAttemptFailed || conflict || accessBlocked)) {
        if (answerAttemptFailed && fresh.data.version !== answerDraft.acceptedVersion) setNewAttemptRequired(true);
        setAnswerDraft((draft) => ({ ...draft, acceptedVersion: fresh.data.version }));
      }
      setConflict(false); setAccessBlocked(false); setFailure("");
    } catch (error) { setFailure(resourceErrorMessage(error)); }
    finally { busy.current = false; onPending(false); }
  }
  async function cancel() {
    const row = query.data;
    if (!row || row.status !== "PENDING" || busy.current || pending || conflict) return;
    if (!window.confirm("Отменить запрос стилисту?")) return;
    busy.current = true; onPending(true); setFailure("");
    try {
      const saved = await request<StylistRequest>(`/api/me/stylist-requests/${id}/cancellation`, actor.id, {
        method: "PUT", body: JSON.stringify({ version: row.version }),
      });
      await queryClient.cancelQueries({ queryKey: detailKey(actor, ownerId, id) });
      queryClient.setQueryData(detailKey(actor, ownerId, id), saved);
      await queryClient.invalidateQueries({ queryKey: ["stylist-requests", actor.id] });
    } catch (error) {
      setFailure(resourceErrorMessage(error, true)); setConflict(true);
    } finally { busy.current = false; onPending(false); }
  }

  if (!Number.isSafeInteger(id) || id < 1) return <main className={styles.main}><p role="alert">Некорректный номер запроса.</p><Link to={pagePath(ownerId)}>← К запросам</Link></main>;
  if (query.isLoading || query.isFetching) return <main className={styles.main}><p className={styles.state}>Загружаем запрос…</p><Link to={pagePath(ownerId)}>← К запросам</Link></main>;
  if (accessBlocked) return <main className={styles.main}><ReadError error={new ApiError(403)} retry={() => void reload()} /><Link to={pagePath(ownerId)}>← К запросам</Link></main>;
  if (query.isError || !query.data) return <main className={styles.main}><ReadError error={query.error ?? new ApiError(404)} retry={() => void reload()} /><Link to={pagePath(ownerId)}>← К запросам</Link></main>;
  const row = query.data;
  return <main className={`${styles.main} ${workspaceStyles.requestDetail}`}>
    <Intro title={`Запрос №${row.id}`} description={`Стилист №${row.stylistId} · ${statusLabel(row.status)}`} />
    <div className={workspaceStyles.requestLayout}>
    <section className={workspaceStyles.requestSummary} aria-label="Сводка запроса">
      <p className={styles.muted}>Создан: {new Date(row.createdAt).toLocaleString("ru-RU")}</p>
      <h2>Погода</h2><p>{types.data ? <Weather row={row} types={types.data} /> : `${row.weather.temperatureC} °C · ветер ${row.weather.windSpeedMps} м/с · Осадки`}</p>
      {types.isError && <ReadError error={types.error} retry={() => void types.refetch()} />}
      <h2>Пожелания</h2><p>{row.wishes || "Пожеланий нет."}</p>
    </section>
    <section className={workspaceStyles.requestActions} aria-label="Действия по запросу">
    {failure && <div className={styles.error} role="alert"><p>{failure}</p></div>}
    {conflict && ownerId === undefined && <div className={styles.error}><p>Состояние могло измениться. Перечитайте запрос, прежде чем продолжить.</p>
      <button type="button" disabled={pending} onClick={() => void reload()}>Перечитать запрос</button></div>}
    {conflict && ownerId !== undefined && <div className={styles.error}><p>Состояние запроса изменилось. Перечитайте его, прежде чем отправлять ответ.</p>
      <button type="button" disabled={pending} onClick={() => void reload()}>Перечитать запрос</button></div>}
    {ownerId !== undefined && answerAttemptFailed && !conflict && <div className={styles.error}>
      <button type="button" disabled={pending} onClick={() => void reload()}>Проверить состояние запроса</button></div>}
      {ownerId !== undefined && row.status === "PENDING" && <AnswerForm actor={actor} ownerId={ownerId} row={row} pending={pending || query.isFetching || query.isError || accessBlocked}
      conflict={conflict} attemptFailed={answerAttemptFailed} newAttemptRequired={newAttemptRequired} accessBlocked={accessBlocked} failure={answerFailure} setFailure={setAnswerFailure} draft={answerDraft} versionReady={answerVersionReady} setDraft={setAnswerDraft} setAttemptFailed={setAnswerAttemptFailed} setNewAttemptRequired={setNewAttemptRequired} onPending={onPending} onConflict={setConflict} onAccessBlocked={setAccessBlocked} onSaved={async (saved) => {
        setAnswerFailure(""); setAnswerAttemptFailed(false); setConflict(false);
        await queryClient.cancelQueries({ queryKey: detailKey(actor, ownerId, id) });
        queryClient.setQueryData(detailKey(actor, ownerId, id), saved);
        await queryClient.invalidateQueries({ queryKey: ["stylist-requests", actor.id, ownerId, base] });
      }} />}
    {ownerId === undefined && row.status === "PENDING" && !conflict && <button type="button" className={styles.deleteButton} disabled={pending || query.isFetching || query.isError} onClick={() => void cancel()}>Отменить запрос</button>}
    {row.status === "ANSWERED" && row.answer && <section className={styles.photoSection}>
      <h2>Ответ стилиста</h2><p>{row.answer.explanation}</p><Link className={styles.create} to={ownerId === undefined ? `/outfits/${row.answer.outfitId}` : `/clients/${ownerId}/outfits/${row.answer.outfitId}`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Открыть образ №{row.answer.outfitId} ↗</Link>
    </section>}
    </section>
    </div>
    <p><Link className={styles.textButton} to={pagePath(ownerId)} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>← К запросам</Link></p>
  </main>;
}

type AnswerDraft = { name: string; explanation: string; selected: number[]; acceptedVersion: number };

function AnswerForm({ actor, ownerId, row, pending, conflict, attemptFailed, newAttemptRequired, accessBlocked, failure, setFailure, draft, versionReady, setDraft, setAttemptFailed, setNewAttemptRequired, onPending, onConflict, onAccessBlocked, onSaved }: {
  actor: Actor; ownerId: number; row: StylistRequest; pending: boolean; conflict: boolean;
  attemptFailed: boolean; newAttemptRequired: boolean; accessBlocked: boolean;
  failure: string; setFailure: (value: string) => void;
  draft: AnswerDraft; versionReady: boolean; setDraft: React.Dispatch<React.SetStateAction<AnswerDraft>>;
  setAttemptFailed: (value: boolean) => void;
  setNewAttemptRequired: (value: boolean) => void;
  onPending: (value: boolean) => void; onConflict: (value: boolean) => void; onAccessBlocked: (value: boolean) => void; onSaved: (saved: StylistRequest) => Promise<void>;
}) {
  const context = { kind: "client", ownerId } as const;
  const itemsBase = wardrobeItemsPath(context);
  const items = useQuery({ queryKey: ["outfit-items", actor.id, ownerId, itemsBase],
    queryFn: ({ signal }) => allPages<WardrobeItem>(itemsBase, actor.id, signal) });
  const path = `${requestBase(ownerId)}/${row.id}/answer`;
  const busy = React.useRef(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending || conflict || newAttemptRequired || !items.isSuccess || items.isFetching || !versionReady) return;
    const cleanName = draft.name.trim();
    const cleanExplanation = draft.explanation.trim();
    if (!cleanName || cleanName.length > 300 || !cleanExplanation || cleanExplanation.length > 2000
      || draft.selected.length < 1 || draft.selected.length > 50 || new Set(draft.selected).size !== draft.selected.length) {
      setFailure("Проверьте название, пояснение и выбор от одной до пятидесяти разных вещей."); return;
    }
    busy.current = true; onPending(true); setFailure("");
    try {
      const saved = await createRequest<StylistRequest>(path, actor.id, JSON.stringify({ version: draft.acceptedVersion, name: cleanName, itemIds: draft.selected, explanation: cleanExplanation }));
      await onSaved(saved);
    } catch (error) {
      setAttemptFailed(true);
      setFailure(`Не удалось отправить ответ. ${resourceErrorMessage(error, true)} Данные формы сохранены.`);
      if (error instanceof ApiError && error.status === 403) onAccessBlocked(true);
      if (error instanceof ApiError && error.status === 409 && !error.code?.startsWith("idempotency_")) {
        onConflict(true); setNewAttemptRequired(true);
      }
    } finally { busy.current = false; onPending(false); }
  }

  return <section className={styles.photoSection}>
    <h2>Ответить на запрос</h2>
    {items.isFetching && <p className={styles.state}>Загружаем гардероб клиента…</p>}
    {items.isError && <ReadError error={items.error} retry={() => void items.refetch()} />}
    {(failure || attemptFailed) && <div className={styles.error} role="alert">{failure && <p>{failure}</p>}
      {attemptFailed && <NewAttempt actorId={actor.id} path={path} disabled={pending || conflict || accessBlocked} onReset={() => { setFailure(""); setAttemptFailed(false); setNewAttemptRequired(false); }} />}</div>}
    <form className={styles.itemForm} onSubmit={(event) => void submit(event)}>
      <label className={styles.nameField}>НАЗВАНИЕ ОБРАЗА<input required maxLength={300} value={draft.name} onChange={(event) => setDraft((current) => ({ ...current, name: event.target.value }))} disabled={pending || conflict || attemptFailed || !items.isSuccess} /></label>
      <label className={styles.nameField}>ПОЯСНЕНИЕ<textarea required maxLength={2000} value={draft.explanation} onChange={(event) => setDraft((current) => ({ ...current, explanation: event.target.value }))} disabled={pending || conflict || attemptFailed || !items.isSuccess} /></label>
      <WardrobeItemPicker actor={actor} context={context} items={items.isSuccess && !items.isFetching ? items.data : undefined} selected={draft.selected}
        onSelectedChange={(selected) => setDraft((current) => ({ ...current, selected }))} disabled={pending || conflict || attemptFailed || !items.isSuccess || items.isFetching} />
      <div className={styles.submitRow}><button className={styles.create} type="submit" disabled={pending || conflict || newAttemptRequired || accessBlocked || !items.isSuccess || items.isFetching || !versionReady}>Отправить ответ</button></div>
    </form>
  </section>;
}
