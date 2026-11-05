import { afterEach, expect, it, vi } from "vitest";
import { allPages, request } from "./api";
import { authenticatedFetch, getSession, login, logout, refreshSession } from "./auth";
import { authenticatedActor } from "./auth-test-fixture";
import { createRequest } from "./idempotency";

const user = { id: 11, login: "user", role: "USER" };
const admin = { id: 33, login: "admin", role: "ADMIN" };
const json = (body: unknown, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers });
const token = () => json({ accessToken: "renewed", tokenType: "Bearer", expiresIn: 900 });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); Reflect.deleteProperty(navigator, "locks"); });

it("keeps credentials private until me succeeds, preserving raw password", async () => {
  // given
  const me = deferred<Response>();
  const storage = { setItem: vi.fn() }; vi.stubGlobal("localStorage", storage);
  const fetch = vi.fn().mockResolvedValueOnce(token()).mockReturnValueOnce(me.promise);
  vi.stubGlobal("fetch", fetch);
  // when
  const signingIn = login(" user ", " password with spaces ");
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  expect(getSession()).toBeNull(); me.resolve(json(user)); await signingIn;
  // then
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ login: " user ", password: " password with spaces " });
  expect(new Headers(fetch.mock.calls[0][1].headers).get("X-Auth-Request")).toBe("1");
  expect(new Headers(fetch.mock.calls[0][1].headers).has("Authorization")).toBe(false);
  expect(new Headers(fetch.mock.calls[1][1].headers).get("Authorization")).toBe("Bearer renewed");
  expect(getSession()?.actor).toEqual(user);
  expect(sessionStorage.length).toBe(0); expect(storage.setItem).not.toHaveBeenCalled();
});

it("does not install a canceled login after its delayed me response", async () => {
  // given
  const me = deferred<Response>(); const controller = new AbortController();
  const fetch = vi.fn().mockResolvedValueOnce(token()).mockReturnValueOnce(me.promise);
  vi.stubGlobal("fetch", fetch);
  const result = login("user", "password", controller.signal);
  const failure = expect(result).rejects.toMatchObject({ name: "AbortError" });
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  // when
  controller.abort(); me.resolve(json(user));
  // then
  await failure; expect(getSession()).toBeNull();
});

it("uses Bearer for JSON, multipart, every page and photo without identity headers", async () => {
  // given
  authenticatedActor(user); let page = 0;
  vi.stubGlobal("fetch", vi.fn(async (path: string) => path.includes("page=")
    ? json([{ id: ++page }], 200, { "X-Total-Count": "2" }) : json({ id: 1 })));
  const body = new FormData(); body.set("photo", new Blob(["image"]));
  // when
  await request("/api/outfits", user.id, { method: "POST", body: "{}" });
  await request("/api/wardrobe/items/1/photos", user.id, { method: "POST", body });
  expect(await allPages("/api/stylists", user.id)).toHaveLength(2);
  await authenticatedFetch("/api/wardrobe/items/1/photos/2/content", user.id);
  // then
  for (const [, init] of vi.mocked(fetch).mock.calls) {
    const headers = new Headers(init?.headers);
    expect(headers.get("Authorization")).toBe("Bearer test-token-11");
    expect(headers.has("X-User-Id")).toBe(false); expect(headers.has("X-Auth-Request")).toBe(false);
  }
  expect(new Headers(vi.mocked(fetch).mock.calls[1][1]?.headers).has("Content-Type")).toBe(false);
});

it("deduplicates refresh and retries writes once with original body, key and signal", async () => {
  // given
  authenticatedActor(user); const refresh = deferred<Response>();
  const calls: Array<{ path: string; init?: RequestInit }> = [];
  vi.stubGlobal("fetch", vi.fn(async (path: string, init?: RequestInit) => {
    calls.push({ path, init });
    if (path === "/api/auth/refresh") return refresh.promise;
    if (path === "/api/auth/me") return json(user);
    return new Headers(init?.headers).get("Authorization") === "Bearer renewed" ? json({ id: 7 }) : json({}, 401);
  }));
  const body = new FormData(); body.set("photo", new Blob(["photo"]));
  const signal = new AbortController().signal;
  const options = { method: "POST", body, signal, headers: { "Idempotency-Key": "unchanged" } };
  // when
  const results = Promise.all([request("/api/outfits", user.id, options), request("/api/outfits", user.id, options)]);
  await vi.waitFor(() => expect(calls.filter(({ path }) => path === "/api/auth/refresh")).toHaveLength(1));
  refresh.resolve(token()); await results;
  // then
  const writes = calls.filter(({ path }) => path === "/api/outfits"); expect(writes).toHaveLength(4);
  for (const { init } of writes) {
    expect(init?.body).toBe(body); expect(init?.signal).toBe(signal);
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe("unchanged");
  }
  expect(calls.filter(({ path }) => path === "/api/auth/refresh")).toHaveLength(1);
});

it("reuses a completed refresh for a delayed old-token 401", async () => {
  // given
  authenticatedActor(user); const old = deferred<Response>();
  const fetch = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(token())
    .mockResolvedValueOnce(json(user)).mockResolvedValueOnce(json({ id: 1 }));
  vi.stubGlobal("fetch", fetch); const reading = request("/api/outfits", user.id);
  // when
  await refreshSession(); old.resolve(json({}, 401)); await reading;
  // then
  expect(fetch.mock.calls.filter(([path]) => path === "/api/auth/refresh")).toHaveLength(1);
  expect(new Headers(fetch.mock.calls[3][1].headers).get("Authorization")).toBe("Bearer renewed");
});

it("blocks old actors and late 401s without clearing or borrowing a new login", async () => {
  // given
  authenticatedActor(user); const old = deferred<Response>();
  const fetch = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(token()).mockResolvedValueOnce(json(admin));
  vi.stubGlobal("fetch", fetch);
  const writing = request("/api/outfits", user.id, { method: "POST", body: "{}" });
  const failure = expect(writing).rejects.toMatchObject({ status: 401 });
  // when
  await login("admin", "password"); old.resolve(json({}, 401)); await failure;
  // then
  await expect(request("/api/outfits", user.id, { method: "POST" })).rejects.toMatchObject({ status: 401 });
  expect(fetch).toHaveBeenCalledTimes(3); expect(getSession()?.actor).toEqual(admin);
});

it("blocks a prepared mutation when its session changes during fingerprinting", async () => {
  // given
  authenticatedActor(user);
  const hash = deferred<ArrayBuffer>();
  vi.spyOn(crypto.subtle, "digest").mockReturnValueOnce(hash.promise);
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  const creating = createRequest("/api/outfits", user.id, "{}");
  const failure = expect(creating).rejects.toMatchObject({ status: 401 });
  // when: even a new session of the same account cannot receive the old write.
  authenticatedActor(user); hash.resolve(new ArrayBuffer(32));
  // then
  await failure; expect(fetch).not.toHaveBeenCalled(); expect(sessionStorage.length).toBe(0);
});

it("does not install a delayed refresh over a new identity", async () => {
  // given
  authenticatedActor(user); const refresh = deferred<Response>();
  const fetch = vi.fn().mockReturnValueOnce(refresh.promise); vi.stubGlobal("fetch", fetch);
  const renewing = refreshSession(); const failure = expect(renewing).rejects.toMatchObject({ status: 401 });
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  // when
  authenticatedActor(admin); refresh.resolve(token());
  // then
  await failure; expect(getSession()?.actor).toEqual(admin); expect(fetch).toHaveBeenCalledTimes(1);
});

it.each([user, { ...user, role: "ADMIN" }, admin])("handles refreshed identity $id/$role without replaying an old context", async (actor) => {
  // given
  authenticatedActor(user);
  const fetch = vi.fn().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(token())
    .mockResolvedValueOnce(json(actor)).mockResolvedValueOnce(json({}, 401));
  vi.stubGlobal("fetch", fetch);
  // when
  await expect(request("/api/outfits", user.id, { method: "POST" })).rejects.toMatchObject({ status: 401 });
  // then
  expect(fetch).toHaveBeenCalledTimes(actor.role === "USER" ? 4 : 3);
  expect(getSession()).toEqual(actor.id === user.id && actor.role === "ADMIN" ? { actor, accessToken: "renewed" } : null);
});

it.each([503, "network"])("never automatically retries ambiguous refresh: %s", async (failure) => {
  // given
  authenticatedActor(user);
  const fetch = vi.fn(async (path: string) => {
    if (path === "/api/auth/refresh") {
      if (failure === "network") throw new TypeError("offline"); return json({}, Number(failure));
    }
    return json({}, 401);
  });
  vi.stubGlobal("fetch", fetch);
  // when
  await expect(request("/api/outfits", user.id, { method: "POST" })).rejects.toThrow();
  await expect(request("/api/outfits", user.id, { method: "POST" })).rejects.toThrow();
  // then
  expect(fetch.mock.calls.filter(([path]) => path === "/api/auth/refresh")).toHaveLength(1);
  expect(getSession()?.actor).toEqual(user);
});

it.each([503, "network"])("never automatically retries business writes on %s", async (failure) => {
  // given
  authenticatedActor(user);
  const fetch = vi.fn(async () => { if (failure === "network") throw new TypeError("offline"); return json({}, Number(failure)); });
  vi.stubGlobal("fetch", fetch);
  // when
  await expect(request("/api/outfits", user.id, { method: "POST", body: "{}" })).rejects.toThrow();
  // then
  expect(fetch).toHaveBeenCalledTimes(1);
});

it("keeps ambiguous refresh blocked after failed logout, and unblocks only explicit recovery", async () => {
  // given
  authenticatedActor(user); let refreshes = 0;
  const fetch = vi.fn(async (path: string) => {
    if (path === "/api/auth/logout") return json({}, 503);
    if (path === "/api/auth/me") return json(user);
    refreshes++; return refreshes === 1 ? json({}, 503) : token();
  });
  vi.stubGlobal("fetch", fetch);
  await expect(refreshSession()).rejects.toMatchObject({ status: 503 });
  // when
  await expect(logout()).rejects.toMatchObject({ status: 503 });
  await expect(refreshSession()).rejects.toMatchObject({ status: 503 });
  expect(refreshes).toBe(1);
  await refreshSession(true); await refreshSession();
  // then
  expect(refreshes).toBe(3); expect(getSession()?.actor).toEqual(user);
});

it("does not continue pagination after a session change during body decoding", async () => {
  // given
  authenticatedActor(user); const rows = deferred<unknown>();
  const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, headers: new Headers({ "X-Total-Count": "2" }), json: () => rows.promise });
  vi.stubGlobal("fetch", fetch);
  const reading = allPages("/api/stylists", user.id);
  const failure = expect(reading).rejects.toMatchObject({ status: 401 });
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  // when
  authenticatedActor(user); rows.resolve([{ id: 1 }]);
  // then
  await failure; expect(fetch).toHaveBeenCalledTimes(1);
});

it("clears invalid refresh and never aborts shared refresh with one reader", async () => {
  // given
  authenticatedActor(user); const refresh = deferred<Response>(); const controller = new AbortController();
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === "/api/auth/refresh") { expect(init?.signal).toBeUndefined(); return refresh.promise; }
    if (path === "/api/auth/me") return json(user);
    return new Headers(init?.headers).get("Authorization") === "Bearer renewed" ? json([]) : json({}, 401);
  });
  vi.stubGlobal("fetch", fetch);
  const first = request("/api/outfits", user.id, { signal: controller.signal });
  const canceled = expect(first).rejects.toMatchObject({ name: "AbortError" });
  const second = request("/api/outfits", user.id);
  await vi.waitFor(() => expect(fetch.mock.calls.some(([path]) => path === "/api/auth/refresh")).toBe(true));
  // when
  controller.abort(); refresh.resolve(token()); await canceled; await second;
  fetch.mockResolvedValue(json({}, 401)); await expect(refreshSession()).rejects.toMatchObject({ status: 401 });
  // then
  expect(getSession()).toBeNull(); expect(fetch.mock.calls.filter(([path]) => path === "/api/outfits")).toHaveLength(3);
});

it.each([true, false])("serializes cookie updates and retains session on failed logout (Web Locks: %s)", async (webLocks) => {
  // given
  authenticatedActor(user); let queue: Promise<unknown> = Promise.resolve();
  const lock = vi.fn((_name: string, action: () => Promise<Response>) => {
    const result = queue.then(action); queue = result.catch(() => undefined); return result;
  });
  if (webLocks) Object.defineProperty(navigator, "locks", { configurable: true, value: { request: lock } });
  const refresh = deferred<Response>();
  const fetch = vi.fn().mockReturnValueOnce(refresh.promise).mockResolvedValueOnce(json({}, 503))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetch);
  const renewing = refreshSession(); const stale = expect(renewing).rejects.toMatchObject({ status: 401 });
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  // when
  const exiting = logout(); const failed = expect(exiting).rejects.toMatchObject({ status: 503 });
  expect(fetch).toHaveBeenCalledTimes(1); refresh.resolve(token()); await stale; await failed;
  expect(getSession()?.actor).toEqual(user); await logout();
  // then
  expect(getSession()).toBeNull();
  expect(lock.mock.calls.map(([name]) => name)).toEqual(Array(webLocks ? 3 : 0).fill("clothes-advisor-auth"));
  for (const [, init] of fetch.mock.calls) {
    expect(new Headers(init.headers).get("X-Auth-Request")).toBe("1");
    expect(new Headers(init.headers).has("Authorization")).toBe(false);
  }
});

it("rejects foreign URLs before sending credentials", async () => {
  // given
  authenticatedActor(user); const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  // when
  await expect(authenticatedFetch("https://foreign.invalid/api/outfits")).rejects.toThrow();
  // then
  expect(fetch).not.toHaveBeenCalled();
});
