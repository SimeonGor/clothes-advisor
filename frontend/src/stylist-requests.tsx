import React from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { allPages, ApiError, request, resourceErrorMessage, type Actor, type Precipitation, type StylistRequest } from "./api";
import { createRequest, NewAttempt } from "./idempotency";
import styles from "./site.module.css";

const pageSize = 12;
type StylistGrant = { id: number; login: string };
type Props = { actor: Actor; pending: boolean; onPending: (value: boolean) => void };
const listKey = (actor: Actor, page: number) => ["stylist-requests", actor.id, page];
const detailKey = (actor: Actor, id: number) => ["stylist-request", actor.id, id];
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

export function StylistRequests({ actor, pending }: Props) {
  const [page, setPage] = React.useState(0);
  const query = useQuery({ queryKey: listKey(actor, page),
    queryFn: ({ signal }) => request<StylistRequest[]>(`/api/me/stylist-requests?page=${page}&size=${pageSize}`, actor.id, { signal }) });
  const types = useQuery({ queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal) });
  return <main className={styles.main}>
    <Intro title="Запросы стилисту" description="Запросы, которые вы отправили стилистам с доступом к вашему гардеробу." />
    <p><Link className={styles.create} to="/requests/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Новый запрос ↗</Link></p>
    {query.isLoading && <p className={styles.state}>Загружаем запросы…</p>}
    {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
    {types.isError && <ReadError error={types.error} retry={() => void types.refetch()} />}
    {query.isSuccess && query.data.length === 0 && page > 0 && <p className={styles.state}>На этой странице нет запросов. Вернитесь на предыдущую.</p>}
    {query.isSuccess && query.data.length === 0 && page === 0 && <section className={styles.empty}><h2>Пока нет запросов</h2>
      <p>Создайте запрос, чтобы попросить стилиста подобрать образ.</p></section>}
    <section aria-label="Список запросов">
      {query.isSuccess && query.data.map((row) => <Link className={styles.account} key={row.id} to={`/requests/${row.id}`} aria-disabled={pending}
        onClick={(event) => { if (pending) event.preventDefault(); }}>
        <span>Запрос №{row.id} · стилист №{row.stylistId}</span>
        <small>{statusLabel(row.status)} · {new Date(row.createdAt).toLocaleDateString("ru-RU")}</small>
        <small>{types.data ? <Weather row={row} types={types.data} /> : `${row.weather.temperatureC} °C · ветер ${row.weather.windSpeedMps} м/с · Осадки`}</small>
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

export function StylistRequestDetail({ actor, pending, onPending }: Props) {
  const { requestId } = useParams();
  const id = Number(requestId);
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: detailKey(actor, id), enabled: Number.isSafeInteger(id) && id > 0,
    queryFn: ({ signal }) => request<StylistRequest>(`/api/me/stylist-requests/${id}`, actor.id, { signal }) });
  const types = useQuery({ queryKey: ["precipitation", actor.id],
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal) });
  const [failure, setFailure] = React.useState("");
  const [conflict, setConflict] = React.useState(false);
  const busy = React.useRef(false);

  async function reload() {
    if (busy.current || pending) return;
    busy.current = true;
    try {
      const fresh = await query.refetch();
      if (fresh.isError || !fresh.data) throw fresh.error ?? new ApiError(404);
      setConflict(false); setFailure("");
    } catch (error) { setFailure(resourceErrorMessage(error)); }
    finally { busy.current = false; }
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
      await queryClient.cancelQueries({ queryKey: detailKey(actor, id) });
      queryClient.setQueryData(detailKey(actor, id), saved);
      await queryClient.invalidateQueries({ queryKey: ["stylist-requests", actor.id] });
    } catch (error) {
      setFailure(resourceErrorMessage(error, true)); setConflict(true);
    } finally { busy.current = false; onPending(false); }
  }

  if (!Number.isSafeInteger(id) || id < 1) return <main className={styles.main}><p role="alert">Некорректный номер запроса.</p><Link to="/requests">← К запросам</Link></main>;
  if (query.isLoading || query.isFetching) return <main className={styles.main}><p className={styles.state}>Загружаем запрос…</p><Link to="/requests">← К запросам</Link></main>;
  if (query.isError || !query.data) return <main className={styles.main}><ReadError error={query.error ?? new ApiError(404)} retry={() => void query.refetch()} /><Link to="/requests">← К запросам</Link></main>;
  const row = query.data;
  return <main className={styles.main}>
    <Intro title={`Запрос №${row.id}`} description={`Стилист №${row.stylistId} · ${statusLabel(row.status)} · версия ${row.version}`} />
    <p className={styles.muted}>Создан: {new Date(row.createdAt).toLocaleString("ru-RU")}</p>
    <section className={styles.photoSection}><h2>Погода</h2>{types.data ? <Weather row={row} types={types.data} /> : <span>{row.weather.temperatureC} °C · ветер {row.weather.windSpeedMps} м/с · Осадки</span>}</section>
    {types.isError && <ReadError error={types.error} retry={() => void types.refetch()} />}
    <section className={styles.photoSection}><h2>Пожелания</h2><p>{row.wishes || "Пожеланий нет."}</p></section>
    {failure && <div className={styles.error} role="alert"><p>{failure}</p></div>}
    {conflict && <div className={styles.error}><p>Состояние могло измениться. Перечитайте запрос, прежде чем продолжить.</p>
      <button type="button" disabled={pending} onClick={() => void reload()}>Перечитать запрос</button></div>}
    {row.status === "PENDING" && !conflict && <button type="button" className={styles.deleteButton} disabled={pending || query.isFetching || query.isError} onClick={() => void cancel()}>Отменить запрос</button>}
    {row.status === "ANSWERED" && row.answer && <section className={styles.photoSection}>
      <h2>Ответ стилиста</h2><p>{row.answer.explanation}</p><Link className={styles.create} to={`/outfits/${row.answer.outfitId}`} aria-disabled={pending}>Открыть образ №{row.answer.outfitId} ↗</Link>
    </section>}
    <p><Link className={styles.textButton} to="/requests" aria-disabled={pending}>← К запросам</Link></p>
  </main>;
}
