import { ApiError, request } from "./api";
import { authenticatedFetch, getSessionEpoch } from "./auth";

const storageKey = (actorId: number, path: string) => `creation:${actorId}:POST:${path}`;

type AttemptRecord = { attempts: Record<string, string>; latestFingerprint: string | null };
type LegacyAttempt = { fingerprint: string; key: string };

function readRecord(slot: string): AttemptRecord {
  const value = sessionStorage.getItem(slot);
  if (!value) return { attempts: {}, latestFingerprint: null };
  const saved = JSON.parse(value) as AttemptRecord | LegacyAttempt;
  return "fingerprint" in saved
    ? { attempts: { [saved.fingerprint]: saved.key }, latestFingerprint: saved.fingerprint }
    : saved;
}

function removeAttempt(slot: string, hash: string, key: string) {
  const saved = readRecord(slot);
  if (saved.attempts[hash] !== key) return;
  delete saved.attempts[hash];
  if (saved.latestFingerprint === hash) saved.latestFingerprint = null;
  if (Object.keys(saved.attempts).length) sessionStorage.setItem(slot, JSON.stringify(saved));
  else sessionStorage.removeItem(slot);
}

async function digest(bytes: ArrayBuffer): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function readBlob(blob: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
}

async function fingerprint(body: string | FormData): Promise<string> {
  const parts: unknown[] = [];
  if (typeof body === "string") parts.push(JSON.parse(body));
  else for (const [name, value] of body.entries()) {
    parts.push([name, typeof value === "string" ? value : await digest(await readBlob(value))]);
  }
  return digest(new TextEncoder().encode(JSON.stringify(parts)).buffer);
}

export async function createRequest<T>(path: string, actorId: number, body: string | FormData, retainKey = false): Promise<T> {
  const epoch = getSessionEpoch();
  const hash = await fingerprint(body);
  if (epoch !== getSessionEpoch()) throw new ApiError(401);
  const slot = storageKey(actorId, path);
  const saved = readRecord(slot);
  const key = saved.attempts[hash] ?? crypto.randomUUID();
  saved.attempts[hash] = key;
  saved.latestFingerprint = hash;
  sessionStorage.setItem(slot, JSON.stringify(saved));
  const result = await request<T>(path, actorId, {
    method: "POST", body, headers: { "Idempotency-Key": key },
  });
  if (!retainKey) removeAttempt(slot, hash, key);
  return result;
}

export async function requestKey(path: string, actorId: number, body: string | FormData): Promise<string | undefined> {
  const hash = await fingerprint(body);
  return readRecord(storageKey(actorId, path)).attempts[hash];
}

export function forgetRequestKey(path: string, actorId: number, key: string) {
  const slot = storageKey(actorId, path);
  const saved = readRecord(slot);
  for (const [hash, savedKey] of Object.entries(saved.attempts)) {
    if (savedKey === key) removeAttempt(slot, hash, key);
  }
}

export async function createAcceptedAiRequest(path: string, actorId: number, body: string, signal?: AbortSignal): Promise<{ receipt: { jobId: string; status: string }; key: string }> {
  const epoch = getSessionEpoch();
  const hash = await fingerprint(body);
  if (epoch !== getSessionEpoch()) throw new ApiError(401);
  const slot = storageKey(actorId, path);
  const saved = readRecord(slot);
  const key = saved.attempts[hash] ?? crypto.randomUUID();
  saved.attempts[hash] = key;
  saved.latestFingerprint = hash;
  sessionStorage.setItem(slot, JSON.stringify(saved));
  const response = await authenticatedFetch(path, actorId, {
    method: "POST", body, signal, headers: { "Content-Type": "application/json", "Idempotency-Key": key },
  });
  const receipt = await response.json() as { jobId?: unknown; status?: unknown };
  const location = response.headers.get("Location");
  if (epoch !== getSessionEpoch()) throw new ApiError(401);
  if (response.status !== 202 || typeof receipt.jobId !== "string" || !/^[0-9a-f-]{36}$/i.test(receipt.jobId)
    || !["QUEUED", "RUNNING"].includes(String(receipt.status))
    || location !== `/api/v2/me/ai-jobs/${receipt.jobId}`) throw new Error("Invalid AI job receipt");
  return { receipt: receipt as { jobId: string; status: string }, key };
}

export function NewAttempt({ actorId, path, disabled, onReset }: {
  actorId: number; path: string; disabled: boolean; onReset: () => void;
}) {
  return <button type="button" disabled={disabled} onClick={() => {
    if (window.confirm("Предыдущая попытка могла выполниться. Новый запрос может создать дубликаты, повторно загрузить фото или потратить лимит AI. Начать новую попытку?")) {
      const slot = storageKey(actorId, path);
      const saved = readRecord(slot);
      if (saved.latestFingerprint !== null) {
        removeAttempt(slot, saved.latestFingerprint, saved.attempts[saved.latestFingerprint]);
      }
      onReset();
    }
  }}>Новая попытка</button>;
}
