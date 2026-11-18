import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError, aiJobPage, request, resourceErrorMessage, type AiJob } from "./api";
import { getSession, getSessionEpoch, subscribeSession } from "./auth";
import { createAcceptedAiRequest, forgetRequestKey, requestKey } from "./idempotency";
import styles from "./site.module.css";

type Operation = "outfit" | "replacement" | "capsule";
type Active = { actorId: number; operation: Operation; sourceId?: number; jobId: string; key: string };
export function ambiguousAiPostFailure(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status >= 500 || error.status === 408 || error.status === 409 || error.status === 429;
}
const slot = (actor: number, operation: Operation, sourceId?: number) =>
  `ai-job:${actor}:${operation}:${sourceId ?? "new"}`;
const postPath = (operation: Operation, sourceId?: number) => operation === "outfit"
  ? "/api/v2/outfits/ai"
  : operation === "replacement" ? `/api/v2/outfits/${sourceId}/ai-replacements` : "/api/v2/me/capsules/ai";

function aiJobResultPath(job: AiJob): string | undefined {
  if (job.status !== "SUCCEEDED" || !job.result) return;
  if (job.result.type === "OUTFIT") return `/outfits/${job.result.id}`;
  if (job.result.type === "CAPSULE") return `/capsules/${job.result.id}`;
}

function jobStatusLabel(status: AiJob["status"]): string {
  return ({ QUEUED: "В очереди", RUNNING: "Выполняется", SUCCEEDED: "Готово", FAILED: "Ошибка", UNKNOWN: "Результат неизвестен" })[status];
}

const jobStatusProgress: Record<AiJob["status"], number> = {
  QUEUED: 0, RUNNING: 1, SUCCEEDED: 2, FAILED: 2, UNKNOWN: 2,
};

function readActive(actorId: number, operation: Operation, sourceId?: number): Active | null {
  try {
    const raw = sessionStorage.getItem(slot(actorId, operation, sourceId));
    if (!raw) return null;
    const saved = JSON.parse(raw) as Active;
    return saved.actorId === actorId && saved.operation === operation && saved.sourceId === sourceId ? saved : null;
  } catch { return null; }
}

export function useAiJob(actorId: number, operation: Operation, sourceId?: number) {
  const [active, setActive] = React.useState<Active | null>(() => readActive(actorId, operation, sourceId));
  const [job, setJob] = React.useState<AiJob | null>(null);
  const [jobOwner, setJobOwner] = React.useState<number | null>(actorId);
  const [failure, setFailure] = React.useState("");
  const [error, setError] = React.useState<unknown>(null);
  const [canStartNew, setCanStartNew] = React.useState(false);
  const [canRetryPolling, setCanRetryPolling] = React.useState(false);
  const [pollRevision, setPollRevision] = React.useState(0);
  const [busy, setBusy] = React.useState(false);
  const epoch = React.useRef(getSessionEpoch());
  const postController = React.useRef<AbortController | null>(null);
  const failedPostKey = React.useRef<string | undefined>(undefined);
  const sessionEpoch = React.useSyncExternalStore(subscribeSession, getSessionEpoch);
  const key = slot(actorId, operation, sourceId);

  React.useEffect(() => {
    postController.current?.abort();
    epoch.current = getSessionEpoch();
    const current = getSession()?.actor.id === actorId ? readActive(actorId, operation, sourceId) : null;
    setActive(current);
    setJob(null);
    setJobOwner(getSession()?.actor.id === actorId ? actorId : null);
    setFailure("");
    setError(null);
    setCanStartNew(false);
    setCanRetryPolling(false);
    failedPostKey.current = undefined;
  }, [actorId, operation, sourceId, sessionEpoch]);

  const visibleActive = getSession()?.actor.id === actorId && active?.actorId === actorId ? active : null;
  const visibleJob = getSession()?.actor.id === actorId && jobOwner === actorId ? job : null;

  React.useEffect(() => {
    if (!visibleActive) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const controller = new AbortController();
    const poll = async () => {
      try {
        const result = await request<AiJob>(`/api/v2/me/ai-jobs/${encodeURIComponent(visibleActive.jobId)}`, actorId, { signal: controller.signal });
        if (cancelled || epoch.current !== getSessionEpoch()) return;
        setJob(result);
        setJobOwner(actorId);
        setFailure("");
        setCanRetryPolling(false);
        setCanStartNew(false);
        if (["QUEUED", "RUNNING"].includes(result.status)) timer = setTimeout(poll, 3000);
      } catch (error) {
        if (!cancelled && epoch.current === getSessionEpoch()) {
          const permanentClientError = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429;
          setFailure(error instanceof ApiError
            ? permanentClientError
              ? `Не удалось проверить задание (${error.status}). Проверьте доступ к заданию или начните новую попытку.`
              : `Не удалось проверить задание (${error.status}). Проверку можно повторить.`
            : "Не удалось проверить задание из-за сбоя связи. Проверку можно повторить.");
          setCanRetryPolling(!permanentClientError);
          setCanStartNew(permanentClientError);
          if (!permanentClientError) timer = setTimeout(poll, 3000);
        }
      }
    };
    void poll();
    return () => { cancelled = true; controller.abort(); clearTimeout(timer); };
  }, [visibleActive, actorId, sessionEpoch, pollRevision]);

  async function submit(body: string) {
    setBusy(true); setFailure(""); setError(null);
    setCanStartNew(false);
    const requestEpoch = getSessionEpoch();
    const controller = new AbortController();
    postController.current = controller;
    const path = postPath(operation, sourceId);
    try {
      const { receipt, key: idemKey } = await createAcceptedAiRequest(path, actorId, body, controller.signal);
      if (requestEpoch !== getSessionEpoch()) return false;
      const next = { actorId, operation, ...(sourceId === undefined ? {} : { sourceId }), jobId: receipt.jobId, key: idemKey };
      sessionStorage.setItem(key, JSON.stringify(next));
      setActive(next);
      return true;
    } catch (error) {
      const failedKey = await requestKey(path, actorId, body).catch(() => undefined);
      postController.current = null;
      if (failedKey) failedPostKey.current = failedKey;
      setFailure(error instanceof ApiError ? `Не удалось получить подтверждение (${error.status}). Повторите с тем же ключом.` : "Не удалось получить подтверждение. Повторите с тем же ключом.");
      setError(error);
      setCanStartNew(!(error instanceof ApiError) || error.status >= 500
        || (error.status >= 400 && error.status < 500 && error.status !== 401 && error.status !== 403 && error.status !== 429)
        || Boolean(error.code?.startsWith("idempotency_")));
      return { error };
    } finally { if (postController.current === controller) postController.current = null; setBusy(false); }
  }

  function startNewAttempt() {
    if (!window.confirm("Предыдущая попытка могла выполниться. Новый запрос может создать дубликаты и повторно потратить лимит AI. Начать новую попытку?")) return false;
    const failedKey = failedPostKey.current;
    if (visibleActive) forgetRequestKey(postPath(operation, sourceId), actorId, visibleActive.key);
    else if (failedKey) forgetRequestKey(postPath(operation, sourceId), actorId, failedKey);
    failedPostKey.current = undefined;
    sessionStorage.removeItem(key);
    setActive(null); setJob(null); setFailure(""); setError(null); setCanStartNew(false); setCanRetryPolling(false);
    return true;
  }

  function clearTerminal() {
    if (visibleActive) forgetRequestKey(postPath(operation, sourceId), actorId, visibleActive.key);
    sessionStorage.removeItem(key); setActive(null);
  }
  function retryPolling() {
    setFailure("");
    setCanRetryPolling(false);
    setCanStartNew(false);
    setPollRevision((revision) => revision + 1);
  }
  const resultPath = visibleJob ? aiJobResultPath(visibleJob) : undefined;
  return { active: visibleActive, job: visibleJob, failure, error, canStartNew, canRetryPolling, busy, submit, startNewAttempt, clearTerminal, retryPolling, resultPath, setFailure };
}

export function AiJobStatus({ job, failure, active, canStartNew, canRetryPolling, newAttemptDisabled, resultPath, onUnknown, onRetry }: {
  job: AiJob | null; failure: string; active?: boolean; canStartNew?: boolean; canRetryPolling?: boolean; newAttemptDisabled?: boolean; resultPath?: string; onUnknown: () => void; onRetry?: () => void;
}) {
  if (failure) return <div role="alert"><p>{failure}</p>
    {canRetryPolling && <button type="button" onClick={onRetry}>Повторить проверку</button>}
    {canStartNew && <button type="button" disabled={newAttemptDisabled} onClick={onUnknown}>Новая попытка</button>}
  </div>;
  if (!job && !active && canStartNew) return <button type="button" disabled={newAttemptDisabled} onClick={onUnknown}>Новая попытка</button>;
  if (!job) return active ? <p role="status">Восстанавливаем состояние задания…</p> : null;
  if (job.status === "QUEUED" || job.status === "RUNNING") return <p role="status">{job.status === "QUEUED" ? "Задание принято, ожидает выполнения…" : "Подбираем…"}</p>;
  if (job.status === "SUCCEEDED") return <p role="status">Готово: <a href={resultPath}>Открыть сохранённый результат</a></p>;
  if (job.status === "UNKNOWN") return <div role="alert"><p>{job.error?.code}: {job.error?.message}</p><p>Повторная попытка может снова потратить квоту.</p><button type="button" onClick={onUnknown}>Новая попытка</button></div>;
  return <div role="alert"><p>{job.error ? `${job.error.code}: ${job.error.message}` : "Не удалось выполнить подбор."}</p>
    <button type="button" disabled={newAttemptDisabled} onClick={onUnknown}>Новая попытка</button></div>;
}

export function AiJobHistory({ actorId }: { actorId: number }) {
  const queryClient = useQueryClient();
  const sessionEpoch = React.useSyncExternalStore(subscribeSession, getSessionEpoch);
  const ownsSession = getSession()?.actor.id === actorId;
  const [page, setPage] = React.useState(0);
  const [selection, setSelection] = React.useState<{ actorId: number; jobId: string } | null>(null);
  const selectedId = ownsSession && selection?.actorId === actorId ? selection.jobId : null;
  const jobs = useQuery({
    queryKey: ["my-ai-jobs", actorId, page, sessionEpoch],
    queryFn: ({ signal }) => aiJobPage(actorId, page, signal),
    enabled: ownsSession,
  });
  const selected = useQuery({
    queryKey: ["my-ai-job", actorId, selectedId, sessionEpoch],
    queryFn: ({ signal }) => request<AiJob>(`/api/v2/me/ai-jobs/${encodeURIComponent(selectedId!)}`, actorId, { signal }),
    enabled: Boolean(selectedId),
    refetchInterval: (query) => query.state.data && ["QUEUED", "RUNNING"].includes(query.state.data.status) ? 3000 : false,
  });
  React.useEffect(() => {
    setPage(0);
    setSelection(null);
  }, [actorId, sessionEpoch]);
  React.useEffect(() => {
    const detail = selected.data;
    if (!ownsSession || !detail || detail.jobId !== selectedId || getSessionEpoch() !== sessionEpoch || getSession()?.actor.id !== actorId) return;
    queryClient.setQueriesData<{ jobs: AiJob[]; total: number }>({ queryKey: ["my-ai-jobs", actorId] }, (current) => {
      if (!current) return current;
      let changed = false;
      const jobs = current.jobs.map((row) => {
        if (row.jobId !== detail.jobId || jobStatusProgress[detail.status] <= jobStatusProgress[row.status]) return row;
        changed = true;
        return { ...row, status: detail.status };
      });
      return changed ? { ...current, jobs } : current;
    });
  }, [actorId, ownsSession, selected.data, selectedId, sessionEpoch, queryClient]);
  const rows = ownsSession ? jobs.data?.jobs ?? [] : [];
  const total = jobs.data?.total ?? 0;
  return <section className={styles.photoSection} aria-labelledby="my-ai-jobs-heading">
    <h2 id="my-ai-jobs-heading">Мои AI-задания</h2>
    <p className={styles.muted}>Недавние подборы и их результаты.</p>
    {!ownsSession && <p className={styles.state} role="status">Задания доступны только в текущем аккаунте.</p>}
    {ownsSession && jobs.isLoading && <p className={styles.state}>Загружаем задания…</p>}
    {ownsSession && jobs.isError && <div className={styles.error} role="alert">
      <p>{resourceErrorMessage(jobs.error)}</p><button type="button" onClick={() => void jobs.refetch()}>Повторить загрузку</button>
    </div>}
    {ownsSession && jobs.isSuccess && rows.length === 0 && <p className={styles.state}>AI-заданий пока нет.</p>}
    {rows.length > 0 && <ul className={styles.aiJobList}>
      {rows.map((row) => <li key={row.jobId}><button type="button" aria-pressed={selectedId === row.jobId}
        onClick={() => setSelection({ actorId, jobId: row.jobId })}>
        <span>{row.type === "OUTFIT_SELECTION" ? "Подбор образа" : row.type === "OUTFIT_REPLACEMENT" ? "Замена вещей" : "AI-капсула"}</span>
        <span>{jobStatusLabel(row.status)}</span><time dateTime={row.createdAt}>{new Date(row.createdAt).toLocaleString("ru-RU")}</time>
      </button></li>)}
    </ul>}
    {jobs.isSuccess && (total > 0 || page > 0) && <div className={styles.aiJobPages}>
      <button type="button" disabled={page === 0 || jobs.isFetching} onClick={() => setPage((value) => Math.max(0, value - 1))}>← Назад</button>
      <span>Страница {page + 1} · {total} заданий</span>
      <button type="button" disabled={jobs.isFetching || (page + 1) * 50 >= total} onClick={() => setPage((value) => value + 1)}>Далее →</button>
    </div>}
    {selectedId && <div className={styles.aiJobDetail} aria-live="polite">
      {ownsSession && selectedId && selected.isLoading && <p className={styles.state}>Загружаем состояние задания…</p>}
      {ownsSession && selectedId && selected.isError && <div role="alert"><p>{resourceErrorMessage(selected.error)}</p>
        <button type="button" onClick={() => void selected.refetch()}>Повторить проверку</button>
      </div>}
      {ownsSession && selectedId && selected.data && <>
        <h3>{jobStatusLabel(selected.data.status)}</h3>
        {selected.data.error && <p role="alert">{selected.data.error.code}: {selected.data.error.message}</p>}
        {selected.data.status === "UNKNOWN" && <p className={styles.notice}>Новая попытка может повторно потратить квоту.</p>}
        {aiJobResultPath(selected.data) && <Link to={aiJobResultPath(selected.data)!}>Открыть сохранённый результат ↗</Link>}
      </>}
    </div>}
  </section>;
}
