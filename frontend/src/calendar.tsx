import React from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createRequest, NewAttempt } from "./idempotency";
import { allPages, mutationErrorMessage, request, resourceErrorMessage, type Actor, type CalendarEntry, type Outfit } from "./api";
import styles from "./site.module.css";

const size = 12;
const entriesKey = (actor: Actor, month: string) => ["calendar", actor.id, month];
const entryKey = (actor: Actor, id: number) => ["calendar-entry", actor.id, id];
const ownPath = "/api/me/calendar";
const localMonth = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
};
function monthRange(month: string) {
  const [year, number] = month.split("-").map(Number);
  const last = new Date(year, number, 0).getDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, "0")}` };
}
const statusLabel = (status: CalendarEntry["status"]) => status === "WORN" ? "Надет" : "Запланирован";

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return <div className={styles.error} role="alert"><p>{resourceErrorMessage(error)}</p>
    <button type="button" onClick={retry}>Повторить загрузку</button></div>;
}

export function Calendar({ actor, pending }: { actor: Actor; pending: boolean }) {
  const [month, setMonth] = React.useState(localMonth);
  const [page, setPage] = React.useState(0);
  const validMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(month);
  const range = validMonth ? monthRange(month) : null;
  const query = useQuery({
    queryKey: entriesKey(actor, month).concat(page),
    enabled: validMonth,
    queryFn: ({ signal }) => request<CalendarEntry[]>(`${ownPath}?from=${range!.from}&to=${range!.to}&page=${page}&size=${size}`, actor.id, { signal }),
  });
  const grouped = React.useMemo(() => {
    const groups = new Map<string, CalendarEntry[]>();
    for (const entry of query.data ?? []) groups.set(entry.date, [...(groups.get(entry.date) ?? []), entry]);
    return [...groups.entries()];
  }, [query.data]);
  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>ПЛАНЫ НА МЕСЯЦ</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШИ ОБРАЗЫ ПО ДНЯМ</p>
      <h1>Календарь</h1><p className={styles.muted}>Планируйте образы и отмечайте, что вы надевали.</p></div>
      <Link className={styles.create} to="/calendar/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Добавить образ <span>↗</span></Link>
    </section>
    <label className={styles.nameField}>МЕСЯЦ
      <input aria-label="Месяц" type="month" value={month} disabled={pending} onChange={(event) => { setMonth(event.target.value); setPage(0); }} />
    </label>
    {query.isLoading && <p className={styles.state}>Загружаем календарь…</p>}
    {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
    {!validMonth && <p className={styles.state}>Выберите месяц для просмотра календаря.</p>}
    {!query.isError && query.data?.length === 0 && page > 0 && <p className={styles.state}>На этой странице нет записей. Вернитесь назад.</p>}
    {!query.isError && query.data?.length === 0 && page === 0 && <section className={styles.empty}><h2>Планов на этот месяц пока нет</h2>
      <p>Добавьте образ на выбранную дату.</p><Link className={styles.create} to="/calendar/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Добавить образ</Link></section>}
    {!query.isError && grouped.map(([date, entries]) => <section className={styles.photoSection} key={date}>
      <h2>{new Date(`${date}T12:00:00`).toLocaleDateString("ru-RU", { weekday: "long", day: "numeric", month: "long" })}</h2>
      {entries.map((entry) => <article className={styles.personRow} key={entry.id}>
        <span>{statusLabel(entry.status)} · <Link to={`/outfits/${entry.outfitId}`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Образ №{entry.outfitId}</Link></span>
        <Link to={`/calendar/${entry.id}`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Изменить</Link>
      </article>)}
    </section>)}
    {(query.data?.length || page > 0) ? <div className={styles.pages}><span>Страница {page + 1}</span><div>
      <button type="button" disabled={!page || pending} onClick={() => setPage((current) => current - 1)}>← Назад</button>
      <button type="button" disabled={!query.data || query.data.length < size || pending} onClick={() => setPage((current) => current + 1)}>Далее →</button>
    </div></div> : null}
  </main>;
}

type Draft = { date: string; outfitId: string; status: CalendarEntry["status"] };
const initialDraft = (entry?: CalendarEntry): Draft => ({ date: entry?.date ?? "", outfitId: entry ? String(entry.outfitId) : "", status: entry?.status ?? "PLANNED" });

export function CalendarEditor({ actor, onPending, pending }: { actor: Actor; onPending: (value: boolean) => void; pending: boolean }) {
  const { entryId } = useParams();
  const id = entryId === undefined ? null : Number(entryId);
  const queryClient = useQueryClient();
  if (id !== null && (!Number.isInteger(id) || id <= 0)) return <main className={styles.main}><p className={styles.state}>Некорректный номер записи календаря.</p></main>;
  const entry = useQuery({ queryKey: entryKey(actor, id ?? 0), enabled: id !== null && Number.isInteger(id) && id > 0,
    queryFn: ({ signal }) => request<CalendarEntry>(`${ownPath}/${id}`, actor.id, { signal }) });
  if (id !== null && entry.data === undefined && (entry.isLoading || !entry.isError)) return <main className={styles.main}><p className={styles.state}>Загружаем запись…</p></main>;
  if (id !== null && entry.data === undefined && entry.isError) return <main className={styles.main}><ReadError error={entry.error} retry={() => void entry.refetch()} /></main>;
  return <CalendarEditorForm actor={actor} pending={pending} onPending={onPending} initial={initialDraft(entry.data)} entry={entry.data}
    readError={entry.isError ? entry.error : null} retryRead={() => void entry.refetch()} queryClient={queryClient} />;
}

function CalendarEditorForm({ actor, pending, onPending, initial, entry, readError, retryRead, queryClient }: {
  actor: Actor; pending: boolean; onPending: (value: boolean) => void; initial: Draft; entry?: CalendarEntry;
  readError: unknown | null; retryRead: () => void;
  queryClient: ReturnType<typeof useQueryClient>;
}) {
  const navigate = useNavigate();
  const [draft, setDraft] = React.useState(initial);
  const [acceptedEntry, setAcceptedEntry] = React.useState(entry);
  const [failure, setFailure] = React.useState("");
  const [success, setSuccess] = React.useState("");
  const [needsReload, setNeedsReload] = React.useState(false);
  const [reloadReadError, setReloadReadError] = React.useState<unknown | null>(null);
  const [confirmDelete, setConfirmDelete] = React.useState(false);
  const busy = React.useRef(false);
  const outfits = useQuery({ queryKey: ["calendar-outfits", actor.id], queryFn: ({ signal }) => allPages<Outfit>("/api/outfits", actor.id, signal) });
  const effectiveReadError = readError ?? reloadReadError;
  const mayWrite = !pending && !busy.current && !needsReload && !effectiveReadError && outfits.isSuccess;
  async function write(method: "POST" | "PUT") {
    if (!mayWrite) return;
    const outfitId = Number(draft.outfitId);
    const validDate = /^\d{4}-\d{2}-\d{2}$/.test(draft.date) && !Number.isNaN(Date.parse(`${draft.date}T00:00:00Z`))
      && new Date(`${draft.date}T00:00:00Z`).toISOString().slice(0, 10) === draft.date;
    if (!validDate || !outfits.data?.some((outfit) => outfit.id === outfitId)) {
      setFailure("Выберите календарную дату и свой сохранённый образ."); return;
    }
    const body = { date: draft.date, outfitId, status: draft.status };
    busy.current = true; onPending(true); setFailure(""); setSuccess("");
    try {
      if (acceptedEntry) await queryClient.cancelQueries({ queryKey: entryKey(actor, acceptedEntry.id), exact: true });
      await queryClient.cancelQueries({ queryKey: ["calendar", actor.id] });
      const saved = method === "POST"
        ? await createRequest<CalendarEntry>(ownPath, actor.id, JSON.stringify(body))
        : await request<CalendarEntry>(`${ownPath}/${acceptedEntry!.id}`, actor.id, { method, body: JSON.stringify({ ...body, version: acceptedEntry!.version }) });
      if (acceptedEntry) await queryClient.cancelQueries({ queryKey: entryKey(actor, acceptedEntry.id), exact: true });
      await queryClient.cancelQueries({ queryKey: ["calendar", actor.id] });
      queryClient.setQueryData(entryKey(actor, saved.id), saved);
      if (acceptedEntry) setAcceptedEntry(saved);
      await queryClient.invalidateQueries({ queryKey: ["calendar", actor.id] });
      navigate("/calendar");
    } catch (error) {
      setFailure(`Не удалось сохранить запись. ${mutationErrorMessage(error)} Данные формы сохранены.`);
      if (method === "PUT") setNeedsReload(true);
    } finally { busy.current = false; onPending(false); }
  }
  async function reload() {
    if (!entry || busy.current || pending) return;
    busy.current = true; onPending(true); setFailure(""); setSuccess("");
    try {
      await queryClient.cancelQueries({ queryKey: entryKey(actor, entry.id), exact: true });
      const fresh = await request<CalendarEntry>(`${ownPath}/${acceptedEntry!.id}`, actor.id);
      queryClient.setQueryData(entryKey(actor, acceptedEntry!.id), fresh);
      setAcceptedEntry(fresh);
      setDraft(initialDraft(fresh)); setNeedsReload(false); setReloadReadError(null); setConfirmDelete(false); setSuccess("Данные обновлены. Черновик заменён.");
    } catch (error) { setFailure(resourceErrorMessage(error)); setNeedsReload(true); setReloadReadError(error); }
    finally { busy.current = false; onPending(false); }
  }
  async function remove() {
    if (!entry || !mayWrite) return;
    busy.current = true; onPending(true); setFailure("");
    try {
      await queryClient.cancelQueries({ queryKey: entryKey(actor, acceptedEntry!.id), exact: true });
      await queryClient.cancelQueries({ queryKey: ["calendar", actor.id] });
      await request(`${ownPath}/${acceptedEntry!.id}?version=${acceptedEntry!.version}`, actor.id, { method: "DELETE" });
      await queryClient.cancelQueries({ queryKey: entryKey(actor, acceptedEntry!.id), exact: true });
      await queryClient.cancelQueries({ queryKey: ["calendar", actor.id] });
      queryClient.removeQueries({ queryKey: entryKey(actor, acceptedEntry!.id), exact: true });
      await queryClient.invalidateQueries({ queryKey: ["calendar", actor.id] });
      navigate("/calendar");
    } catch (error) { setFailure(`Не удалось удалить запись. ${mutationErrorMessage(error)}`); setNeedsReload(true); }
    finally { busy.current = false; onPending(false); }
  }
  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>{effectiveReadError ? "ДАННЫЕ НЕДОСТУПНЫ" : acceptedEntry ? `ЗАПИСЬ №${acceptedEntry.id}` : "НОВЫЙ ПЛАН"}</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШ КАЛЕНДАРЬ</p>
      <h1>{entry ? "Изменить план" : "Добавить образ"}</h1><p className={styles.muted}>Выберите дату, образ и отметьте его статус.</p></div></section>
    {effectiveReadError !== null && <>
      {acceptedEntry && <p className={styles.muted}>При обновлении данных несохранённые изменения будут заменены сохранённой записью.</p>}
      <ReadError error={effectiveReadError} retry={acceptedEntry ? () => void reload() : retryRead} />
    </>}
    {outfits.isLoading && <p className={styles.state}>Загружаем ваши образы…</p>}
    {outfits.isError && <ReadError error={outfits.error} retry={() => void outfits.refetch()} />}
    {!effectiveReadError && !outfits.isError && outfits.data?.length === 0 && <section className={styles.empty}><h2>Сначала сохраните образ</h2><p>Для календаря нужен хотя бы один образ.</p><Link className={styles.create} to="/outfits/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Создать образ</Link></section>}
    {!effectiveReadError && !outfits.isError && outfits.data && outfits.data.length > 0 && <form className={styles.itemForm} onSubmit={(event) => { event.preventDefault(); void write(acceptedEntry ? "PUT" : "POST"); }}>
      <label className={styles.nameField}>ДАТА<input aria-label="Дата" type="date" required value={draft.date} disabled={!mayWrite} onChange={(event) => setDraft((old) => ({ ...old, date: event.target.value }))} /></label>
      <label className={styles.nameField}>ОБРАЗ<select aria-label="Образ" required value={draft.outfitId} disabled={!mayWrite || outfits.isError} onChange={(event) => setDraft((old) => ({ ...old, outfitId: event.target.value }))}>
        <option value="">Выберите образ</option>{outfits.data.map((outfit) => <option key={outfit.id} value={outfit.id}>{outfit.name} · №{outfit.id}</option>)}
      </select></label>
      <label className={styles.nameField}>СТАТУС<select aria-label="Статус" value={draft.status} disabled={!mayWrite} onChange={(event) => setDraft((old) => ({ ...old, status: event.target.value as Draft["status"] }))}>
        <option value="PLANNED">Запланирован</option><option value="WORN">Надет</option></select></label>
      {failure && <p className={styles.error} role="alert">{failure}</p>}{success && <p className={styles.success} role="status">{success}</p>}
      {!needsReload && <button className={styles.create} type="submit" disabled={!mayWrite || outfits.isError}>{acceptedEntry ? "Сохранить изменения" : "Добавить в календарь"}</button>}
      {!acceptedEntry && failure && <NewAttempt actorId={actor.id} path={ownPath} disabled={pending || needsReload} onReset={() => setFailure("")} />}
    </form>}
    {needsReload && <div className={styles.error} role="group" aria-label="Требуется обновление данных">
      <p>Перед повторным изменением обновите данные. Несохранённые изменения будут заменены сохранённой записью.</p>
      <button type="button" disabled={pending} onClick={() => void reload()}>Обновить данные</button>
    </div>}
    {acceptedEntry && !effectiveReadError && <section className={styles.deleteSection}>{confirmDelete ? <div className={styles.confirmation} role="group" aria-label="Подтверждение удаления записи">
      <p>Удалить запись календаря? Сам образ останется.</p><button type="button" disabled={!mayWrite} onClick={() => void remove()}>Подтвердить удаление</button>
      <button type="button" disabled={pending} onClick={() => setConfirmDelete(false)}>Отмена</button>
    </div> : <button className={styles.deleteButton} type="button" disabled={!mayWrite} onClick={() => setConfirmDelete(true)}>Удалить запись</button>}</section>}
    <Link to="/calendar" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Вернуться в календарь</Link>
  </main>;
}
