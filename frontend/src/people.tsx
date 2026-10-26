import React from "react";
import { Link, Navigate, Route, Routes, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { allPages, ApiError, request, resourceErrorMessage, type Account, type Actor, type WorkspaceContext } from "./api";
import { Wardrobe, WardrobeDetail } from "./wardrobe";
import { OutfitDetail, OutfitEditor, Outfits } from "./outfits";
import { ClientPreferences } from "./preferences";
import { StylistRequestDetail, StylistRequests } from "./stylist-requests";
import styles from "./site.module.css";

type Person = { id: number; login: string };
type AccountDetails = Account & { version: number; createdAt: string; modifiedAt: string };
type Props = { actor: Actor; pending: boolean; onPending: (value: boolean) => void };
const clientsKey = (actor: Actor) => ["clients", actor.id];
const accountKey = (actor: Actor, id: number) => ["account", actor.id, id];

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return <div className={styles.error} role="alert"><p>{resourceErrorMessage(error)}</p>
    <button type="button" onClick={retry}>Повторить загрузку</button></div>;
}

function Intro({ title, description }: { title: string; description: string }) {
  return <><header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ЛЮДИ И ДОСТУП</p>
      <h1>{title}</h1><p className={styles.muted}>{description}</p></div></section></>;
}

export function Access({ actor, pending, onPending }: Props) {
  const grants = useQuery({
    queryKey: ["stylist-access", actor.id],
    queryFn: ({ signal }) => allPages<Person>("/api/me/stylist-access", actor.id, signal),
  });
  const directory = useQuery({
    queryKey: ["access-directory", actor.id],
    queryFn: ({ signal }) => allPages<Account>("/api/admin/users", undefined, signal),
  });
  const [selected, setSelected] = React.useState("");
  const [confirmGrant, setConfirmGrant] = React.useState(false);
  const [revokeId, setRevokeId] = React.useState<number | null>(null);
  const [failure, setFailure] = React.useState("");
  const [success, setSuccess] = React.useState("");
  const busy = React.useRef(false);
  const candidates = (directory.data ?? []).filter((person) => person.role === "STYLIST"
    && person.status === "ACTIVE" && !grants.data?.some((grant) => grant.id === person.id));

  async function change(id: number, method: "PUT" | "DELETE") {
    if (busy.current || pending) return;
    if (method === "PUT" && !candidates.some((person) => person.id === id)) return;
    busy.current = true;
    onPending(true);
    setFailure("");
    setSuccess("");
    try {
      await request(`/api/me/stylist-access/${id}`, actor.id, { method });
      setSelected("");
      setConfirmGrant(false);
      setRevokeId(null);
      setSuccess(method === "PUT" ? "Доступ предоставлен." : "Доступ отозван.");
      await grants.refetch();
    } catch (error) { setFailure(resourceErrorMessage(error, true)); }
    finally { busy.current = false; onPending(false); }
  }

  return <main className={styles.main}>
    <Intro title="Доступ стилистам" description="Стилист с доступом видит ваш гардероб и предпочтения, составляет и оценивает образы." />
    {grants.isLoading && <p className={styles.state}>Загружаем доступы…</p>}
    {grants.isError && <ReadError error={grants.error} retry={() => void grants.refetch()} />}
    {directory.isError && <ReadError error={directory.error} retry={() => void directory.refetch()} />}
    <form className={styles.itemForm} onSubmit={(event) => { event.preventDefault(); setConfirmGrant(true); }}>
      <label className={styles.nameField}>СТИЛИСТ
        <select value={selected} required disabled={pending || !grants.isSuccess || !directory.isSuccess}
          onChange={(event) => { setSelected(event.target.value); setConfirmGrant(false); }}>
          <option value="">Выберите стилиста</option>
          {candidates.map((person) => <option key={person.id} value={person.id}>{person.login}</option>)}
        </select>
      </label>
      <button className={styles.create} type="submit" disabled={pending || !selected || !grants.isSuccess || !directory.isSuccess}>Предоставить доступ</button>
    </form>
    {confirmGrant && <div className={styles.confirmation} role="group" aria-label="Предоставление доступа">
      <span>Предоставить доступ стилисту {candidates.find((person) => person.id === Number(selected))?.login}?</span>
      <button type="button" disabled={pending || !grants.isSuccess || !directory.isSuccess} onClick={() => void change(Number(selected), "PUT")}>Подтвердить доступ</button>
      <button type="button" disabled={pending} onClick={() => setConfirmGrant(false)}>Отмена</button>
    </div>}
    {directory.isSuccess && grants.isSuccess && !candidates.length && <p className={styles.state}>Нет других активных стилистов.</p>}
    {success && <p role="status" className={styles.success}>{success}</p>}
    {failure && <p role="alert" className={styles.error}>{failure}</p>}
    <section className={styles.photoSection}><h2>Доступ уже есть</h2>
      {grants.data?.length === 0 && <p className={styles.state}>Вы ещё не предоставили доступ.</p>}
      {grants.data?.map((person) => <article key={person.id} className={styles.personRow}>
        <span>{person.login} <small>№{person.id}</small></span>
        {revokeId === person.id ? <div className={styles.confirmation} role="group" aria-label={`Отозвать доступ: ${person.login}`}>
          <span>Отозвать доступ?</span>
          <button type="button" disabled={pending} onClick={() => void change(person.id, "DELETE")}>Подтвердить отзыв</button>
          <button type="button" disabled={pending} onClick={() => setRevokeId(null)}>Отмена</button>
        </div> : <button type="button" className={styles.deleteButton} disabled={pending} onClick={() => setRevokeId(person.id)}>Отозвать доступ</button>}
      </article>)}
    </section>
  </main>;
}

export function Clients({ actor, pending }: Props) {
  const query = useQuery({ queryKey: clientsKey(actor), queryFn: ({ signal }) => allPages<Person>("/api/stylist/clients", actor.id, signal) });
  return <main className={styles.main}>
    <Intro title="Клиенты" description="Пользователи, которые предоставили вам доступ к своему гардеробу и предпочтениям." />
    {query.isLoading && <p className={styles.state}>Загружаем клиентов…</p>}
    {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
    {query.data?.length === 0 && <p className={styles.state}>Пока нет клиентов. Пользователь может предоставить доступ в разделе «Доступ стилистам».</p>}
    {query.data?.map((person) => <Link className={styles.account} key={person.id} to={`/clients/${person.id}/wardrobe`}
      aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}><span>{person.login}</span><small>Открыть гардероб ↗</small></Link>)}
  </main>;
}

export function Accounts({ actor, pending }: Props) {
  const query = useQuery({ queryKey: ["account-directory", actor.id], queryFn: ({ signal }) => allPages<Account>("/api/admin/users", undefined, signal) });
  return <main className={styles.main}>
    <Intro title="Аккаунты" description="Управление ролями, статусами и модерация содержимого." />
    {query.isLoading && <p className={styles.state}>Загружаем аккаунты…</p>}
    {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
    {query.data?.length === 0 && <p className={styles.state}>Аккаунтов пока нет.</p>}
    {query.data?.map((person) => <Link className={styles.account} key={person.id} to={`/accounts/${person.id}`}
      aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}><span>{person.login}</span><small>{person.role} · {person.status}</small></Link>)}
  </main>;
}

export function OwnerWorkspace({ kind, actor, pending, onPending }: Props & { kind: "client" | "admin" }) {
  const { ownerId } = useParams();
  const id = Number(ownerId);
  const valid = Number.isSafeInteger(id) && id > 0;
  const clients = useQuery({ queryKey: clientsKey(actor), enabled: kind === "client" && valid,
    queryFn: ({ signal }) => allPages<Person>("/api/stylist/clients", actor.id, signal) });
  const account = useQuery({ queryKey: accountKey(actor, id), enabled: kind === "admin" && valid,
    queryFn: ({ signal }) => request<AccountDetails>(`/api/admin/users/${id}`, undefined, { signal }) });
  const query = kind === "client" ? clients : account;
  const owner = kind === "client" ? clients.data?.find((person) => person.id === id) : account.data;
  const transientError = !(query.error instanceof ApiError) || query.error.status >= 500;
  const directory = kind === "client" ? "/clients" : "/accounts";
  if (!valid) return <main className={styles.main}><p role="alert">Некорректный номер владельца.</p><Link to={directory}>← К списку</Link></main>;
  if (query.isLoading) return <main className={styles.main}><p className={styles.state}>Проверяем доступ…</p></main>;
  if (!owner || (query.isError && !transientError)) return <main className={styles.main}>
    <ReadError error={query.error ?? new ApiError(404)} retry={() => void query.refetch()} />
    <Link to={directory}>← К списку</Link>
  </main>;
  const context: WorkspaceContext = { kind, ownerId: id };
  const base = `${directory}/${id}`;
  const workspacePending = pending || query.isFetching || query.isError;
  const props = { actor, pending: workspacePending, onPending, context };
  return <>
    <section className={styles.contextHeader} aria-label="Владелец содержимого">
      <Link className={styles.textButton} to={directory} aria-disabled={pending}>← {kind === "client" ? "К клиентам" : "К аккаунтам"}</Link>
      <p>{kind === "client" ? "Клиент" : "Аккаунт"}: <strong>{owner.login}</strong> · №{id}</p>
      {query.isError && <ReadError error={query.error} retry={() => void query.refetch()} />}
      {query.isFetching && <p className={styles.state}>Проверяем доступ… Запись временно недоступна.</p>}
      <nav className={styles.contextTabs} aria-label="Разделы владельца">
        {kind === "admin" && <Link to={base} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Роль и статус</Link>}
        <Link to={`${base}/wardrobe`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Гардероб владельца</Link>
        <Link to={`${base}/outfits`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Образы владельца</Link>
        {kind === "client" && <Link to={`${base}/requests`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Запросы</Link>}
        {kind === "client" && <Link to={`${base}/preferences`} aria-disabled={pending} onClick={(event) => { if (pending) event.preventDefault(); }}>Предпочтения клиента</Link>}
      </nav>
    </section>
    <Routes>
      <Route index element={kind === "admin" ? <AccountEditor actor={actor} pending={workspacePending} onPending={onPending} owner={account.data!} /> : <Navigate to={`${base}/wardrobe`} replace />} />
      <Route path="wardrobe" element={<Wardrobe {...props} />} />
      <Route path="wardrobe/:itemId" element={<WardrobeDetail {...props} />} />
      <Route path="outfits" element={<Outfits {...props} />} />
      {kind === "client" && <Route path="outfits/new" element={<OutfitEditor {...props} />} />}
      {kind === "client" && <Route path="preferences" element={query.isSuccess && !query.isFetching
        ? <ClientPreferences actor={actor} ownerId={id} />
        : <main className={styles.main}><p className={styles.state}>Предпочтения недоступны до успешной проверки доступа.</p></main>} />}
      <Route path="outfits/:outfitId" element={<OutfitDetail {...props} />} />
      {kind === "client" && <Route path="requests" element={<StylistRequests {...props} ownerId={id} />} />}
      {kind === "client" && <Route path="requests/:requestId" element={<StylistRequestDetail {...props} ownerId={id} />} />}
      <Route path="*" element={<Navigate to={base} replace />} />
    </Routes>
  </>;
}

function AccountEditor({ actor, pending, onPending, owner }: Props & { owner: AccountDetails }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = React.useState(owner);
  const [failure, setFailure] = React.useState("");
  const [success, setSuccess] = React.useState("");
  const [conflict, setConflict] = React.useState(false);
  const [reloading, setReloading] = React.useState(false);
  const busy = React.useRef(false);
  const ownAccount = actor.id === owner.id;
  const path = `/api/admin/users/${owner.id}`;

  async function reload() {
    if (busy.current) return;
    busy.current = true;
    setReloading(true);
    try {
      const fresh = await request<AccountDetails>(path);
      setDraft(fresh);
      queryClient.setQueryData(accountKey(actor, owner.id), fresh);
      setConflict(false);
      setFailure("");
      setSuccess("Данные обновлены.");
    } catch (error) { setFailure(resourceErrorMessage(error)); }
    finally { busy.current = false; setReloading(false); }
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending || conflict || (ownAccount && (draft.role !== "ADMIN" || draft.status !== "ACTIVE"))) return;
    busy.current = true;
    onPending(true);
    setFailure("");
    setSuccess("");
    try {
      const saved = await request<AccountDetails>(path, actor.id, {
        method: "PUT", body: JSON.stringify({ role: draft.role, status: draft.status, version: draft.version }),
      });
      setDraft(saved);
      queryClient.setQueryData(accountKey(actor, owner.id), saved);
      await queryClient.invalidateQueries({ queryKey: ["account-directory", actor.id] });
      setSuccess("Роль и статус сохранены.");
    } catch (error) {
      setFailure(resourceErrorMessage(error, true));
      setConflict(error instanceof ApiError && error.status === 409);
    } finally { busy.current = false; onPending(false); }
  }
  return <main className={styles.main}>
    <Intro title={owner.login} description={`№${owner.id} · Текущая роль: ${owner.role} · Статус: ${owner.status}`} />
    <p className={styles.muted}>Создан: {new Date(owner.createdAt).toLocaleString("ru-RU")} · Изменён: {new Date(owner.modifiedAt).toLocaleString("ru-RU")}</p>
    {ownAccount && <p className={styles.notice}>Нельзя заблокировать собственный аккаунт или понизить свою роль.</p>}
    <form className={styles.itemForm} onSubmit={(event) => void submit(event)}>
      <label className={styles.nameField}>РОЛЬ
        <select value={draft.role} disabled={pending || reloading || ownAccount} onChange={(event) => setDraft({ ...draft, role: event.target.value })}>
          <option value="USER">USER</option><option value="STYLIST">STYLIST</option><option value="ADMIN">ADMIN</option>
        </select>
      </label>
      <label className={styles.nameField}>СТАТУС
        <select value={draft.status} disabled={pending || reloading || ownAccount} onChange={(event) => setDraft({ ...draft, status: event.target.value })}>
          <option value="ACTIVE">ACTIVE</option><option value="BLOCKED">BLOCKED</option>
        </select>
      </label>
      <button className={styles.create} type="submit" disabled={pending || reloading || conflict || ownAccount}>Сохранить роль и статус</button>
    </form>
    {failure && <p className={styles.error} role="alert">{failure} Значения формы сохранены.</p>}
    {success && <p className={styles.success} role="status">{success}</p>}
    {conflict && <div className={styles.error}><p>Загрузите актуальные данные: это заменит значения формы.</p>
      <button type="button" disabled={pending || reloading} onClick={() => void reload()}>Обновить данные</button></div>}
  </main>;
}
