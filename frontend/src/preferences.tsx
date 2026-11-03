import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, request, resourceErrorMessage, type Actor, type StylePreferences } from "./api";
import styles from "./site.module.css";
import personal from "./personal.module.css";

type Props = { actor: Actor; pending: boolean; onPending: (value: boolean) => void };
type Draft = Pick<StylePreferences, "preferredColorsAndMaterials" | "avoidances" | "additionalWishes"> & { version: number | null };
const fields = [
  { key: "preferredColorsAndMaterials", label: "Любимые цвета и материалы" },
  { key: "avoidances", label: "Чего избегать" },
  { key: "additionalWishes", label: "Дополнительные пожелания" },
] as const;
const emptyDraft: Draft = { preferredColorsAndMaterials: "", avoidances: "", additionalWishes: "", version: null };
const ownPath = "/api/me/style-preferences";
const preferencesKey = (actor: Actor, ownerId: number) => ["style-preferences", actor.id, ownerId];

function Intro({ client = false }: { client?: boolean }) {
  return <><header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШ СТИЛЬ</p>
      <h1>{client ? "Предпочтения клиента" : "Предпочтения"}</h1>
      <p className={styles.muted}>{client ? "Пожелания владельца. Доступны только для чтения."
        : "Расскажите о своём стиле. AI учтёт эти пожелания при подборе; стилист с доступом сможет их прочитать."}</p>
    </div></section></>;
}

export function Preferences({ actor, pending, onPending }: Props) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: preferencesKey(actor, actor.id),
    queryFn: async ({ signal }) => {
      try { return await request<StylePreferences>(ownPath, actor.id, { signal }); }
      catch (error) {
        if (error instanceof ApiError && error.status === 404) return null;
        throw error;
      }
    },
  });
  const retrying = React.useRef(false);
  async function retry() {
    if (retrying.current || pending || query.isFetching) return;
    retrying.current = true;
    onPending(true);
    try { await query.refetch(); }
    finally { retrying.current = false; onPending(false); }
  }
  return <main className={styles.main}>
    <Intro />
    {query.data === undefined ? <>
      {query.isFetching && <p className={styles.state}>Загружаем предпочтения…</p>}
      {query.isError && <div className={styles.error} role="alert"><p>{resourceErrorMessage(query.error)}</p>
        <button type="button" disabled={pending || query.isFetching} onClick={() => void retry()}>Повторить загрузку</button></div>}
    </> : <PreferencesEditor actor={actor} pending={pending} onPending={onPending} initial={query.data}
      loading={query.isFetching} loadError={query.isError ? query.error : null}
      reload={async () => {
        const fresh = await query.refetch();
        if (fresh.isError) throw fresh.error;
        return fresh.data!;
      }} onSaved={(saved) => queryClient.setQueryData(preferencesKey(actor, actor.id), saved)} />}
  </main>;
}

function PreferencesEditor({ actor, pending, onPending, initial, loading, loadError, reload, onSaved }: Props & {
  initial: StylePreferences | null;
  loading: boolean;
  loadError: unknown;
  reload: () => Promise<StylePreferences | null>;
  onSaved: (saved: StylePreferences) => void;
}) {
  const [draft, setDraft] = React.useState<Draft>(initial ?? emptyDraft);
  const [failure, setFailure] = React.useState("");
  const [success, setSuccess] = React.useState("");
  const [needsReload, setNeedsReload] = React.useState(false);
  const busy = React.useRef(false);
  const disabled = pending || loading;

  async function refresh() {
    if (busy.current || disabled) return;
    busy.current = true;
    onPending(true);
    setSuccess("");
    try {
      const fresh = await reload();
      setDraft(fresh ?? emptyDraft);
      setNeedsReload(false);
      setFailure("");
      setSuccess("Данные обновлены.");
    } catch (error) {
      setNeedsReload(true);
      setFailure(resourceErrorMessage(error));
    } finally { busy.current = false; onPending(false); }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || disabled || loadError || needsReload || fields.some(({ key }) => draft[key].length > 2000)) return;
    busy.current = true;
    onPending(true);
    setFailure("");
    setSuccess("");
    try {
      const saved = await request<StylePreferences>(ownPath, actor.id, {
        method: "PUT", body: JSON.stringify({ preferredColorsAndMaterials: draft.preferredColorsAndMaterials,
          avoidances: draft.avoidances, additionalWishes: draft.additionalWishes, version: draft.version }),
      });
      setDraft(saved);
      onSaved(saved);
      setSuccess("Предпочтения сохранены.");
    } catch (error) {
      setFailure(resourceErrorMessage(error, true));
      setNeedsReload(!(error instanceof ApiError) || error.status === 409 || error.status >= 500);
    } finally { busy.current = false; onPending(false); }
  }

  return <>
    <p className={styles.muted}>Все поля необязательны. До 2000 символов в каждом.</p>
    <form className={`${styles.itemForm} ${styles.preferencesForm} ${personal.preferenceCards}`} onSubmit={(event) => void submit(event)}>
      {fields.map(({ key, label }) => <label className={`${styles.nameField} ${personal.preferenceCard}`} key={key}>{label}
        <textarea value={draft[key]} maxLength={2000} rows={4} disabled={disabled || Boolean(loadError)}
          onChange={(event) => { setDraft({ ...draft, [key]: event.target.value }); setSuccess(""); }} />
      </label>)}
      <button className={styles.create} type="submit" disabled={disabled || Boolean(loadError) || needsReload}>Сохранить предпочтения</button>
    </form>
    {loading && <p className={styles.state}>Загружаем предпочтения…</p>}
    {loadError != null && <p className={styles.error} role="alert">{resourceErrorMessage(loadError)}</p>}
    {failure && <p className={styles.error} role="alert">{failure} Значения формы сохранены.</p>}
    {success && <p className={styles.success} role="status">{success}</p>}
    {(needsReload || loadError != null) && <div className={styles.notice}>
      <p>Загрузите актуальные данные перед сохранением. Обновление заменит введённые значения формы.</p>
      <button type="button" disabled={disabled} onClick={() => void refresh()}>Обновить данные</button>
    </div>}
  </>;
}

export function ClientPreferences({ actor, ownerId }: { actor: Actor; ownerId: number }) {
  const query = useQuery({ queryKey: preferencesKey(actor, ownerId),
    queryFn: ({ signal }) => request<StylePreferences>(`/api/stylist/clients/${ownerId}/style-preferences`, actor.id, { signal }) });
  return <main className={styles.main}>
    <Intro client />
    {query.isFetching ? <p className={styles.state}>Загружаем предпочтения…</p>
      : query.isError ? <div className={styles.error} role="alert"><p>{resourceErrorMessage(query.error)}</p>
        <button type="button" onClick={() => void query.refetch()}>Повторить загрузку</button></div>
      : query.data && <dl className={`${styles.preferencesReadOnly} ${personal.readOnlyCards}`}>
        {fields.map(({ key, label }) => <div className={personal.readOnlyCard} key={key}><dt>{label}</dt><dd>{query.data[key] || "Не указано"}</dd></div>)}
      </dl>}
  </main>;
}
