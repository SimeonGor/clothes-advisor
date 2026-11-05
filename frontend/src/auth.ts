import type { Actor } from "./api";
import { ApiError, checkResponse } from "./api-error";

export type Session = { actor: Actor; accessToken: string };
let session: Session | null = null;
let epoch = 0;
let context = 0;
let authQueue: Promise<unknown> = Promise.resolve();
let refreshFlight: { epoch: number; promise: Promise<Session> } | undefined;
let refreshFailure: { session: Session | null; error: unknown } | undefined;
const listeners = new Set<() => void>();
export const getSession = () => session;
export const getSessionEpoch = () => epoch;
export const getSessionContext = () => context;
export const needsSessionRecovery = () => Boolean(refreshFailure && refreshFailure.session === session);
export const subscribeSession = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};

export function installSession(next: Session | null) {
  epoch += 1;
  context += 1;
  session = next;
  refreshFailure = undefined;
  listeners.forEach((listener) => listener());
}

function assertCurrent(expected: number, signal?: AbortSignal | null) {
  signal?.throwIfAborted();
  if (epoch !== expected) throw new ApiError(401);
}

async function authPost(path: "login" | "refresh" | "logout", expected: number, body?: string,
  signal?: AbortSignal): Promise<Response> {
  const send = async () => {
    assertCurrent(expected, signal);
    return checkResponse(await fetch(`/api/auth/${path}`, {
      method: "POST", credentials: "same-origin", cache: "no-store",
      headers: { "X-Auth-Request": "1", ...(body ? { "Content-Type": "application/json" } : {}) },
      body,
      // Cookie rotation must finish even when an individual view unmounts.
    }));
  };
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request("clothes-advisor-auth", send);
  }
  const result = authQueue.then(send);
  authQueue = result.catch(() => undefined);
  return result;
}

async function candidate(response: Response, expected: number, signal?: AbortSignal): Promise<Session> {
  const { accessToken } = await response.json() as { accessToken: string };
  assertCurrent(expected, signal);
  const me = await checkResponse(await fetch("/api/auth/me", {
    headers: { Authorization: `Bearer ${accessToken}` }, credentials: "same-origin", cache: "no-store", signal,
  }));
  const actor = await me.json() as Actor;
  assertCurrent(expected, signal);
  return { accessToken, actor };
}

export async function login(login: string, password: string, signal?: AbortSignal): Promise<Actor> {
  const expected = ++epoch;
  const next = await candidate(await authPost("login", expected, JSON.stringify({ login, password }), signal), expected, signal);
  installSession(next);
  return next.actor;
}

export async function logout(): Promise<void> {
  const expected = ++epoch;
  await authPost("logout", expected);
  if (epoch === expected) installSession(null);
}

export function refreshSession(explicit = false): Promise<Session> {
  const expected = epoch;
  if (refreshFlight?.epoch === expected) return refreshFlight.promise;
  if (!explicit && refreshFailure && refreshFailure.session === session) return Promise.reject(refreshFailure.error);
  const previous = session;
  const promise = (async () => {
    try {
      const next = await candidate(await authPost("refresh", expected), expected);
      // Another tab may have changed the cookie's owner. Never silently switch accounts.
      if (previous && previous.actor.id !== next.actor.id) {
        installSession(null);
        throw new ApiError(401);
      }
      if (previous && previous.actor.role === next.actor.role && previous.actor.login === next.actor.login) {
        session = next;
        refreshFailure = undefined;
        listeners.forEach((listener) => listener());
      } else installSession(next);
      return next;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        if (epoch === expected) installSession(null);
      } else if (session === previous) {
        refreshFailure = { session: previous, error };
        listeners.forEach((listener) => listener());
      }
      throw error;
    } finally { if (refreshFlight?.epoch === expected) refreshFlight = undefined; }
  })();
  refreshFlight = { epoch: expected, promise };
  return promise;
}

export async function authenticatedFetch(path: string, actorId?: number, options: RequestInit = {}): Promise<Response> {
  // This transport only accepts local API paths; credentials must never reach another origin.
  if (!path.startsWith("/api/") || path.startsWith("/api/auth/")) throw new Error("Invalid business API path");
  const expected = epoch;
  const initial = session;
  if (!initial || (actorId !== undefined && initial.actor.id !== actorId)) throw new ApiError(401);
  const send = (current: Session) => {
    assertCurrent(expected, options.signal);
    const headers = new Headers(options.headers);
    headers.delete("X-User-Id");
    headers.delete("X-Auth-Request");
    headers.set("Authorization", `Bearer ${current.accessToken}`);
    return fetch(path, { ...options, headers, credentials: "same-origin", cache: "no-store" });
  };
  let response = await send(initial);
  assertCurrent(expected, options.signal);
  if (response.status === 401) {
    // Reuse a refresh completed since this request started, or join the single flight.
    const renewed = session !== initial ? session! : await refreshSession();
    assertCurrent(expected, options.signal);
    if (renewed.actor.id !== initial.actor.id) throw new ApiError(401);
    response = await send(renewed);
    assertCurrent(expected, options.signal);
    if (response.status === 401 && session === renewed) installSession(null);
  }
  return checkResponse(response);
}
