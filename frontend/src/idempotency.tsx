import { request } from "./api";

const storageKey = (actorId: number, path: string) => `creation:${actorId}:POST:${path}`;

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

export async function createRequest<T>(path: string, actorId: number, body: string | FormData): Promise<T> {
  const hash = await fingerprint(body);
  const slot = storageKey(actorId, path);
  const previous = sessionStorage.getItem(slot);
  const saved = previous ? JSON.parse(previous) as { fingerprint: string; key: string } : null;
  const command = saved?.fingerprint === hash ? saved : { fingerprint: hash, key: crypto.randomUUID() };
  sessionStorage.setItem(slot, JSON.stringify(command));
  const result = await request<T>(path, actorId, {
    method: "POST", body, headers: { "Idempotency-Key": command.key },
  });
  if (sessionStorage.getItem(slot) === JSON.stringify(command)) sessionStorage.removeItem(slot);
  return result;
}

export function NewAttempt({ actorId, path, disabled, onReset }: {
  actorId: number; path: string; disabled: boolean; onReset: () => void;
}) {
  return <button type="button" disabled={disabled} onClick={() => {
    if (window.confirm("Предыдущая попытка могла выполниться. Новый запрос может создать дубликаты, повторно загрузить фото или потратить лимит AI. Начать новую попытку?")) {
      sessionStorage.removeItem(storageKey(actorId, path));
      onReset();
    }
  }}>Новая попытка</button>;
}
