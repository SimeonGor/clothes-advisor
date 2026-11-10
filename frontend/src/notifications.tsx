import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError, request, resourceErrorMessage, type Actor } from "./api";
import { authenticatedFetch, getSession, getSessionContext, getSessionEpoch, subscribeSession } from "./auth";
import { Dialog } from "./dialog";
import styles from "./notifications.module.css";

type Resource = { type: string; id: string };
type Notice = { id: number; message: string; resource: Resource | null; createdAt: string; readAt: string | null };
type Destination = { path: string; label: string } | { jobId: string };
const base = "/api/me/notifications";
const pageSize = 10;
const numericId = (value: string) => /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value));

function destination(resource: Resource | null, role: string): Destination | null {
  if (!resource || typeof resource.id !== "string") return null;
  const { type, id } = resource;
  if (type === "OUTFIT" && numericId(id)) return { path: `/outfits/${id}`, label: "Открыть образ" };
  if (type === "CLIENT" && role === "STYLIST" && numericId(id)) {
    return { path: `/clients/${id}/wardrobe`, label: "Гардероб клиента" };
  }
  if (type === "CLIENT_REQUEST" && role === "STYLIST") {
    const ids = id.split("/");
    if (ids.length === 2 && ids.every(numericId)) return { path: `/clients/${ids[0]}/requests/${ids[1]}`, label: "Открыть запрос" };
  }
  if (type === "STYLIST_REQUEST" && role === "USER" && numericId(id)) {
    return { path: `/requests/${id}`, label: "Открыть запрос" };
  }
  if (type === "AI_JOB" && role === "USER" && /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(id)) return { jobId: id };
  return null;
}

type Job = { status: string; error: { message: string } | null; result: { id: number; type: string } | null };
const statuses = new Map([
  ["QUEUED", "В очереди"], ["RUNNING", "Выполняется"], ["SUCCEEDED", "Готово"],
  ["FAILED", "Не удалось выполнить"], ["UNKNOWN", "Результат неизвестен"],
]);

function JobDetails({ actor, jobId, context, close }: { actor: Actor; jobId: string; context: number; close: () => void }) {
  const job = useQuery({ queryKey: ["notification-job", actor.id, context, jobId],
    queryFn: ({ signal }) => request<Job>(`/api/v2/me/ai-jobs/${jobId}`, actor.id, { signal }) });
  if (job.isError) {
    const missing = job.error instanceof ApiError && [403, 404].includes(job.error.status);
    return <div role="alert">{missing ? "Задание недоступно." : resourceErrorMessage(job.error)}
      {!missing && <button type="button" onClick={() => void job.refetch()}>Повторить загрузку</button>}
    </div>;
  }
  if (!job.data) return <p role="status">Загружаем задание…</p>;
  const { status, error, result } = job.data;
  const resultPath = status === "SUCCEEDED" && result && Number.isSafeInteger(result.id) && result.id > 0
    ? result.type === "OUTFIT" ? `/outfits/${result.id}` : result.type === "CAPSULE" ? `/capsules/${result.id}` : null
    : null;
  return <section className={styles.job} aria-label="Задание AI">
    <h3>Задание AI</h3><p>{statuses.get(status) ?? "Статус неизвестен"}</p>
    {error?.message && <p>{error.message}</p>}
    {resultPath && <Link to={resultPath} onClick={close}>Открыть {result?.type === "OUTFIT" ? "образ" : "капсулу"}</Link>}
  </section>;
}

export function NotificationsHeader({ actor, pending }: { actor: Actor; pending: boolean }) {
  const context = React.useSyncExternalStore(subscribeSession, getSessionContext);
  if (getSession()?.actor.id !== actor.id || !["USER", "STYLIST"].includes(actor.role)) return null;
  return <NotificationCenter key={`${actor.id}:${context}`} actor={actor} context={context} pending={pending} />;
}

function NotificationCenter({ actor, context, pending }: { actor: Actor; context: number; pending: boolean }) {
  const client = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [page, setPage] = React.useState(0);
  const [jobId, setJobId] = React.useState<string | null>(null);
  const [marking, setMarking] = React.useState<number | null>(null);
  const [failure, setFailure] = React.useState("");
  const writer = React.useRef<AbortController | null>(null);
  const prefix = ["notifications", actor.id, context];
  const count = useQuery({ queryKey: [...prefix, "unread"],
    queryFn: ({ signal }) => request<{ count: number }>(`${base}/unread-count`, actor.id, { signal }),
    refetchInterval: 60_000 });
  const inbox = useQuery({ queryKey: [...prefix, "page", page], enabled: open && !jobId,
    queryFn: async ({ signal }) => {
      const response = await authenticatedFetch(`${base}?page=${page}&size=${pageSize}`, actor.id, { signal });
      const rows = await response.json() as Notice[];
      const total = response.headers.get("X-Total-Count");
      return { rows, next: total === null ? rows.length === pageSize : (page + 1) * pageSize < Number(total) };
    } });
  React.useEffect(() => () => writer.current?.abort(), []);

  function openInbox() {
    setPage(0); setJobId(null); setFailure(""); setOpen(true);
    void count.refetch();
  }
  async function markRead(id: number) {
    if (writer.current || !Number.isSafeInteger(id) || id < 1) return;
    const controller = new AbortController();
    const epoch = getSessionEpoch();
    writer.current = controller; setMarking(id); setFailure("");
    try {
      await request(`${base}/${id}/read`, actor.id, { method: "PUT", signal: controller.signal });
      if (!controller.signal.aborted && epoch === getSessionEpoch()) await client.invalidateQueries({ queryKey: prefix });
    } catch (error) {
      if (!controller.signal.aborted && epoch === getSessionEpoch()) setFailure(resourceErrorMessage(error, true));
    } finally {
      writer.current = null;
      if (!controller.signal.aborted) setMarking(null);
    }
  }
  const unread = count.isSuccess && Number.isSafeInteger(count.data?.count) ? count.data.count : null;
  const close = () => setOpen(false);
  return <header className={styles.header} aria-label="Уведомления аккаунта">
    <button className={styles.trigger} type="button" disabled={pending} onClick={openInbox} aria-haspopup="dialog"
      aria-label={unread === null ? "Уведомления" : `Уведомления, непрочитанных: ${unread}`}>
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zM10 20a2.5 2.5 0 0 0 4 0"/>
      </svg>Уведомления{unread !== null && unread > 0 && <span className={styles.badge}>{unread}</span>}
    </button>
    {open && <Dialog open title="Уведомления" onClose={close}>
      <div className={styles.inbox}>
        {jobId ? <>
          <button type="button" onClick={() => setJobId(null)}>← К уведомлениям</button>
          <JobDetails actor={actor} jobId={jobId} context={context} close={close} />
        </> : <>
          {inbox.isPending && <p role="status">Загружаем уведомления…</p>}
          {inbox.isError && <div role="alert"><p>{resourceErrorMessage(inbox.error)}</p>
            <button type="button" onClick={() => void inbox.refetch()}>Повторить загрузку</button></div>}
          {failure && <p role="alert">{failure}</p>}
          {inbox.isSuccess && <>
            {!inbox.data.rows.length && <p className={styles.empty}>Уведомлений пока нет.</p>}
            <ul className={styles.list}>{inbox.data.rows.map(row => {
              const target = destination(row.resource, actor.role);
              return <li key={row.id} className={styles.row}>
                <div className={styles.meta}><time dateTime={row.createdAt}>{new Date(row.createdAt).toLocaleString("ru-RU", { dateStyle: "short", timeStyle: "short" })}</time>
                  {row.readAt === null && <span>Новое</span>}</div>
                <p>{row.message}</p>
                <div className={styles.actions}>
                  {target && ("path" in target ? <Link to={target.path} onClick={close}>{target.label}</Link>
                    : <button type="button" onClick={() => setJobId(target.jobId)}>Открыть задание</button>)}
                  {row.resource && !target && <small>Ресурс недоступен.</small>}
                  {row.readAt === null && <button type="button" disabled={marking !== null || pending} onClick={() => void markRead(row.id)}>
                    {marking === row.id ? "Сохраняем…" : "Отметить прочитанным"}</button>}
                </div>
              </li>;
            })}</ul>
          </>}
          {(page > 0 || inbox.data?.next) && <nav className={styles.pages} aria-label="Страницы уведомлений">
            <button type="button" disabled={!page || marking !== null} onClick={() => { setFailure(""); setPage(page - 1); }}>Назад</button>
            <span>Страница {page + 1}</span>
            <button type="button" disabled={!inbox.data?.next || marking !== null} onClick={() => { setFailure(""); setPage(page + 1); }}>Далее</button>
          </nav>}
        </>}
      </div>
    </Dialog>}
  </header>;
}
