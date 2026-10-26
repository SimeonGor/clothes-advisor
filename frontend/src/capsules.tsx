import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { createRequest, NewAttempt } from "./idempotency";
import { allPages, ApiError, request, resourceErrorMessage, type Actor, type Capsule, type WardrobeItem, wardrobeItemsPath } from "./api";
import { WardrobeItemPicker } from "./wardrobe-item-picker";
import { WardrobeItemCard } from "./wardrobe-item-card";
import { CapsuleAiContext } from "./ai-capsule";
import styles from "./site.module.css";

const pageSize = 12;
const capsulesKey = (actorId: number, page: number) => ["capsules", actorId, page] as const;
const capsuleKey = (actorId: number, id: number) => ["capsule", actorId, id] as const;
const itemsKey = (actorId: number) => ["capsule-items", actorId] as const;

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return <div className={styles.error} role="alert"><p>{resourceErrorMessage(error)}</p>
    <button type="button" onClick={retry}>Повторить загрузку</button></div>;
}

function Denied() {
  return <main className={styles.main}><div className={styles.error} role="alert">Капсулы доступны только аккаунтам USER.</div></main>;
}

export function Capsules({ actor, pending }: { actor: Actor; pending: boolean }) {
  const [page, setPage] = React.useState(0);
  const query = useQuery({
    queryKey: capsulesKey(actor.id, page),
    enabled: actor.role === "USER",
    queryFn: ({ signal }) => request<Capsule[]>(`/api/me/capsules?page=${page}&size=${pageSize}`, actor.id, { signal }),
  });
  if (actor.role !== "USER") return <Denied />;
  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>НАБОРЫ</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ВАШ ГАРДЕРОБ</p><h1>Капсулы</h1>
      <p className={styles.muted}>Ручные наборы вещей и подборки, созданные с помощью ИИ.</p></div>
      <div><Link className={styles.create} to="/capsules/ai" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Подобрать с ИИ <span>↗</span></Link>{" "}
        <Link className={styles.textButton} to="/capsules/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Новая капсула</Link></div>
    </section>
    {query.isLoading && <p className={styles.state}>Загружаем капсулы…</p>}
    {query.isError && <><ReadError error={query.error} retry={() => void query.refetch()} />
      {page > 0 && <button type="button" disabled={pending} onClick={() => setPage(0)}>← Вернуться к списку</button>}</>}
    {!query.isError && query.data?.length === 0 && page > 0 && <p className={styles.state}>На этой странице нет капсул. Вернитесь на предыдущую.</p>}
    {!query.isError && query.data?.length === 0 && page === 0 && <section className={styles.empty}>
      <h2>Пока нет капсул</h2><p>Создайте первый набор из своих вещей.</p>
      <Link className={styles.create} to="/capsules/ai" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Подобрать с ИИ <span>↗</span></Link>{" "}
      <Link className={styles.textButton} to="/capsules/new" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Создать вручную</Link>
    </section>}
    {!query.isError && <div className={styles.grid}>{query.data?.map((capsule) => <Link className={styles.card} key={capsule.id} to={`/capsules/${capsule.id}`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>
      <span className={styles.cardInfo}><span className={styles.itemName}>{capsule.name}</span>
        <small>{capsule.source === "AI" ? "Подбор с ИИ" : "Ручной набор"}</small>
        <small>{new Date(capsule.createdAt).toLocaleDateString("ru-RU")}</small><small>{capsule.itemIds.length} вещей</small></span>
    </Link>)}</div>}
    {(query.data?.length || page > 0) && !query.isError && <div className={styles.pages}><span>Страница {page + 1}</span><div>
      <button type="button" disabled={!page || pending} onClick={() => setPage((value) => value - 1)}>← Назад</button>
      <button type="button" disabled={!query.data || query.data.length < pageSize || pending} onClick={() => setPage((value) => value + 1)}>Далее →</button>
    </div></div>}
  </main>;
}

export function CapsuleEditor({ actor, onPending, pending }: { actor: Actor; onPending: (value: boolean) => void; pending: boolean }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = React.useState("");
  const [selected, setSelected] = React.useState<number[]>([]);
  const [failure, setFailure] = React.useState("");
  const [attemptFailed, setAttemptFailed] = React.useState(false);
  const [wardrobeReloadRequired, setWardrobeReloadRequired] = React.useState(false);
  const [wardrobeReloaded, setWardrobeReloaded] = React.useState(false);
  const [wardrobeReloading, setWardrobeReloading] = React.useState(false);
  const busy = React.useRef(false);
  const items = useQuery({
    queryKey: itemsKey(actor.id),
    enabled: actor.role === "USER",
    queryFn: ({ signal }) => allPages<WardrobeItem>(wardrobeItemsPath({ kind: "own" }), actor.id, signal),
  });
  if (actor.role !== "USER") return <Denied />;

  async function reloadWardrobe() {
    if (pending || wardrobeReloading) return;
    setWardrobeReloading(true);
    onPending(true);
    setFailure("");
    try {
      const result = await items.refetch();
      if (!result.isSuccess) {
        setFailure(`Не удалось обновить гардероб. ${resourceErrorMessage(result.error, true)} Черновик сохранён.`);
        setWardrobeReloaded(false);
        return;
      }
      const freshIds = new Set(result.data.map((item) => item.id));
      const removed = selected.some((id) => !freshIds.has(id));
      if (removed) {
        setFailure("Некоторых выбранных вещей больше нет в гардеробе. Проверьте выбор перед новой попыткой.");
      } else {
        setFailure("Гардероб обновлён. Проверьте выбор перед новой попыткой.");
      }
      setWardrobeReloaded(true);
    } finally {
      setWardrobeReloading(false);
      onPending(false);
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending || !items.isSuccess || items.isFetching || wardrobeReloadRequired) return;
    const cleanName = name.trim();
    if (!cleanName || cleanName.length > 300 || selected.length < 1 || selected.length > 20
      || new Set(selected).size !== selected.length
      || (!attemptFailed && selected.some((id) => !items.data.some((item) => item.id === id)))) {
      setFailure("Проверьте название и выберите от одной до двадцати разных вещей.");
      return;
    }
    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      const saved = await createRequest<Capsule>("/api/me/capsules", actor.id, JSON.stringify({ name: cleanName, itemIds: selected }));
      queryClient.setQueryData(capsuleKey(actor.id, saved.id), saved);
      await queryClient.invalidateQueries({ queryKey: ["capsules", actor.id] });
      navigate(`/capsules/${saved.id}`);
    } catch (error) {
      const message = error instanceof ApiError && error.status === 409 && !error.code?.startsWith("idempotency_")
        ? "Список вещей изменился. Обновите его и проверьте выбор."
        : `Не удалось сохранить капсулу. ${resourceErrorMessage(error, true)} Данные формы сохранены.`;
      setFailure(message);
      setAttemptFailed(true);
      if (error instanceof ApiError && error.status === 409 && !error.code?.startsWith("idempotency_")) {
        setWardrobeReloadRequired(true);
        setWardrobeReloaded(false);
      }
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  function beginNewAttempt() {
    const freshIds = new Set(items.data?.map((item) => item.id) ?? []);
    const removed = selected.some((id) => !freshIds.has(id));
    setSelected((current) => current.filter((id) => freshIds.has(id)));
    setFailure(removed ? "Некоторых выбранных вещей больше нет в гардеробе. Проверьте выбор." : "");
    setAttemptFailed(false);
    setWardrobeReloadRequired(false);
    setWardrobeReloaded(false);
  }

  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>НОВАЯ КАПСУЛА</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>РУЧНОЙ НАБОР</p><h1>Новая капсула</h1>
      <p className={styles.muted}>Состав нельзя изменить после сохранения. Для другого набора создайте новую капсулу.</p></div></section>
    <p className={styles.muted}><Link to="/capsules/ai">Подобрать капсулу с ИИ</Link></p>
    <form onSubmit={(event) => void submit(event)}>
      <section className={styles.formTop}><label className={styles.nameField}>НАЗВАНИЕ КАПСУЛЫ
        <input required maxLength={300} value={name} onChange={(event) => setName(event.target.value)} disabled={pending || attemptFailed} />
      </label></section>
      {items.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
      {items.isError && !wardrobeReloadRequired && <ReadError error={items.error} retry={() => void reloadWardrobe()} />}
      {wardrobeReloadRequired && <div role="status" className={styles.state}>
        <p>{wardrobeReloading ? "Обновляем гардероб…" : "Для продолжения загрузите актуальный гардероб."}</p>
        <button type="button" disabled={pending || wardrobeReloading || (wardrobeReloaded && items.isSuccess)} onClick={() => void reloadWardrobe()}>
          {wardrobeReloaded && items.isSuccess ? "Гардероб обновлён" : "Обновить гардероб"}
        </button>
      </div>}
      <WardrobeItemPicker actor={actor} context={{ kind: "own" }} items={items.isSuccess && !wardrobeReloadRequired && !items.isFetching ? items.data : undefined}
        selected={selected} onSelectedChange={setSelected} maxItems={20} disabled={pending || attemptFailed || wardrobeReloadRequired || !items.isSuccess || items.isFetching} />
      {failure && <div role="alert" className={styles.error}>{failure}
        {attemptFailed && (!wardrobeReloadRequired || wardrobeReloaded) && <NewAttempt actorId={actor.id} path="/api/me/capsules"
          disabled={pending || items.isFetching || !items.isSuccess || (wardrobeReloadRequired && !wardrobeReloaded)} onReset={beginNewAttempt} />}
      </div>}
      <div className={styles.submitRow}><p>Вещи в стирке и убранные можно включить вручную.</p>
        <button className={styles.create} type="submit" disabled={pending || !items.isSuccess || items.isFetching || wardrobeReloadRequired}>{attemptFailed ? "Повторить тот же запрос" : "Сохранить капсулу"} <span>↗</span></button>
      </div>
    </form>
  </main>;
}

export function CapsuleDetail({ actor, onPending, pending }: { actor: Actor; onPending: (value: boolean) => void; pending: boolean }) {
  const { capsuleId } = useParams();
  const id = Number(capsuleId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirmDelete, setConfirmDelete] = React.useState(false);
  const [failure, setFailure] = React.useState("");
  const busy = React.useRef(false);
  const validId = Number.isInteger(id) && id > 0;
  const capsule = useQuery({
    queryKey: capsuleKey(actor.id, id),
    enabled: actor.role === "USER" && validId,
    queryFn: ({ signal }) => request<Capsule>(`/api/me/capsules/${id}`, actor.id, { signal }),
  });
  const items = useQuery({
    queryKey: ["capsule-detail-items", actor.id, id],
    enabled: actor.role === "USER" && capsule.isSuccess,
    queryFn: ({ signal }) => Promise.all((capsule.data?.itemIds ?? []).map((itemId) => request<WardrobeItem>(`${wardrobeItemsPath({ kind: "own" })}/${itemId}`, actor.id, { signal }))),
  });
  if (actor.role !== "USER") return <Denied />;
  if (!validId) return <main className={styles.main}><div role="alert" className={styles.error}>Некорректный номер капсулы.</div><Link className={styles.textButton} to="/capsules">← К капсулам</Link></main>;

  async function remove() {
    if (busy.current || pending || !capsule.data) return;
    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      await Promise.all([
        queryClient.cancelQueries({ queryKey: capsuleKey(actor.id, id) }),
        queryClient.cancelQueries({ queryKey: ["capsules", actor.id] }),
      ]);
      await request<void>(`/api/me/capsules/${id}`, actor.id, { method: "DELETE" });
      await Promise.all([
        queryClient.cancelQueries({ queryKey: capsuleKey(actor.id, id) }),
        queryClient.cancelQueries({ queryKey: ["capsule-detail-items", actor.id, id] }),
        queryClient.cancelQueries({ queryKey: ["capsules", actor.id] }),
      ]);
      queryClient.removeQueries({ queryKey: capsuleKey(actor.id, id), exact: true });
      queryClient.setQueriesData<Capsule[]>({ queryKey: ["capsules", actor.id] }, (rows) => rows?.filter((item) => item.id !== id));
      await queryClient.invalidateQueries({ queryKey: ["capsules", actor.id] });
      navigate("/capsules");
    } catch (error) {
      setFailure(`Не удалось удалить капсулу. ${resourceErrorMessage(error, true)}`);
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  if (capsule.isLoading) return <main className={styles.main}><p className={styles.state}>Загружаем капсулу…</p></main>;
  if (capsule.isError || !capsule.data) return <main className={styles.main}><ReadError error={capsule.error} retry={() => void capsule.refetch()} />
    <Link className={styles.textButton} to="/capsules">← К капсулам</Link></main>;
  const row = capsule.data;
  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>КАПСУЛА №{row.id}</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>СОЗДАНА {new Date(row.createdAt).toLocaleDateString("ru-RU")}</p>
      <h1>{row.name}</h1><p className={styles.muted}>{row.source === "AI" ? "Подбор с ИИ" : "Ручной набор"} · {row.itemIds.length} вещей · состав нельзя изменить. Для другого набора создайте новую капсулу.</p></div>
      <Link className={styles.textButton} to="/capsules" aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>← К капсулам</Link>
    </section>
    {row.source === "AI" && <CapsuleAiContext actor={actor} capsule={row} />}
    {items.isLoading && <p className={styles.state}>Загружаем вещи…</p>}
    {items.isError && <ReadError error={items.error} retry={() => void items.refetch()} />}
    {items.data?.length === 0 && <p className={styles.state}>В капсуле нет вещей.</p>}
    {!items.isError && <div className={styles.grid}>{items.data?.map((item) => <WardrobeItemCard key={item.id} actor={actor} item={item}
      base={wardrobeItemsPath({ kind: "own" })} context={{ kind: "own" }} to={`/wardrobe/${item.id}`} />)}</div>
    }
    {failure && <div className={styles.error} role="alert">{failure}</div>}
    <section className={styles.deleteSection}>{confirmDelete ? <div role="group" aria-label="Подтверждение удаления">
      <p>Удалить капсулу? Вещи останутся в гардеробе.</p>
      <button type="button" disabled={pending} onClick={() => void remove()}>Да, удалить</button>{" "}
      <button type="button" disabled={pending} onClick={() => setConfirmDelete(false)}>Отмена</button>
    </div> : <button className={styles.deleteButton} type="button" disabled={pending} onClick={() => setConfirmDelete(true)}>Удалить капсулу</button>}</section>
  </main>;
}
