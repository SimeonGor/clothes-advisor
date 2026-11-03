import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError, resourceErrorMessage } from "./api";
import { createRequest, NewAttempt } from "./idempotency";

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const response = (status: number, body: unknown) => ({ ok: status < 400, status, json: async () => body } as Response);
const key = (call: unknown[]) => new Headers((call[1] as RequestInit).headers).get("Idempotency-Key");

it.each(["/api/wardrobe/items", "/api/wardrobe/items/7/photos", "/api/outfits", "/api/stylist/clients/9/outfits", "/api/outfits/ai"])(
  "retains A→B→A identity across module reload and clears only confirmed success: %s", async (path) => {
    // given
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError("offline"))
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValue(response(201, { id: 7 }));
    vi.stubGlobal("fetch", fetch);
    await expect(createRequest(path, 1, '{"name":"private name"}')).rejects.toThrow("offline");
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain("private name");

    // when
    await expect(createRequest(path, 1, '{"name":"other private name"}')).rejects.toThrow("offline");
    vi.resetModules();
    const reloaded = await import("./idempotency");
    await reloaded.createRequest(path, 1, '{"name":"private name"}');
    await reloaded.createRequest(path, 1, '{"name":"private name"}');

    // then
    expect(key(fetch.mock.calls[2])).toBe(key(fetch.mock.calls[0]));
    expect(key(fetch.mock.calls[3])).not.toBe(key(fetch.mock.calls[0]));
    expect(sessionStorage.length).toBe(1);
    await reloaded.createRequest(path, 1, '{"name":"other private name"}');
    expect(key(fetch.mock.calls[4])).toBe(key(fetch.mock.calls[1]));
    expect(sessionStorage.length).toBe(0);
  },
);

it("separates payload, actor and target while hashing ordered file contents", async () => {
  // given
  const fetch = vi.fn().mockRejectedValue(new TypeError("offline"));
  vi.stubGlobal("fetch", fetch);
  const form = (bytes: string[]) => {
    const data = new FormData();
    data.append("item", new Blob(['{"name":"private"}'], { type: "application/json" }));
    bytes.forEach((value) => data.append("photos", new File([value], "same.png", { type: "image/png" })));
    return data;
  };
  const send = async (actor: number, path: string, bytes: string[]) => {
    await expect(createRequest(path, actor, form(bytes))).rejects.toThrow("offline");
  };

  // when
  await send(1, "/api/wardrobe/items", ["a", "b"]);
  await send(1, "/api/wardrobe/items", ["a", "b"]);
  await send(1, "/api/wardrobe/items", ["b", "a"]);
  await send(1, "/api/wardrobe/items", ["a", "c"]);
  await send(2, "/api/wardrobe/items", ["a", "b"]);
  await send(1, "/api/wardrobe/items/7/photos", ["a", "b"]);
  await send(1, "/api/wardrobe/items", ["a", "b"]);

  // then
  const keys = fetch.mock.calls.map(key);
  expect(keys[0]).toBe(keys[1]);
  expect(new Set([keys[0], ...keys.slice(2, 6)]).size).toBe(5);
  expect(keys[6]).toBe(keys[0]);
  expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain("private");
});

it.each(['{"name":"A"}', '{"name":"B"}'])("preserves a legacy key when first accessed with %s", async (firstBody) => {
  // given
  const fetch = vi.fn().mockRejectedValue(new TypeError("offline"));
  vi.stubGlobal("fetch", fetch);
  await createRequest("/api/outfits", 1, '{"name":"A"}').catch(() => undefined);
  const slot = sessionStorage.key(0)!;
  const fingerprint = JSON.parse(sessionStorage.getItem(slot)!).latestFingerprint as string;
  sessionStorage.setItem(slot, JSON.stringify({ fingerprint, key: "legacy-key" }));

  // when
  await createRequest("/api/outfits", 1, firstBody).catch(() => undefined);
  vi.resetModules();
  const reloaded = await import("./idempotency");
  await reloaded.createRequest("/api/outfits", 1, '{"name":"A"}').catch(() => undefined);

  // then
  expect(key(fetch.mock.calls[2])).toBe("legacy-key");
  if (firstBody === '{"name":"A"}') expect(key(fetch.mock.calls[1])).toBe("legacy-key");
  else expect(key(fetch.mock.calls[1])).not.toBe("legacy-key");
  expect(sessionStorage.getItem(slot)).not.toContain('"fingerprint":');
});

it("shares an identity for simultaneous submissions of the same payload", async () => {
  // given
  const finish: Array<(value: Response) => void> = [];
  const fetch = vi.fn(() => new Promise<Response>((resolve) => finish.push(resolve)));
  vi.stubGlobal("fetch", fetch);

  // when
  const first = createRequest("/api/outfits", 1, "{}");
  const second = createRequest("/api/outfits", 1, "{}");
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  finish.forEach((resolve) => resolve(response(201, { id: 7 })));
  await Promise.all([first, second]);

  // then
  expect(key(fetch.mock.calls[1])).toBe(key(fetch.mock.calls[0]));
  expect(sessionStorage.length).toBe(0);
});

it.each([0, 1])("success of pending operation %s preserves the other pending identity", async (completed) => {
  // given
  const finish: Array<(value: Response) => void> = [];
  const fetch = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => new Promise<Response>((resolve) => finish.push(resolve)));
  vi.stubGlobal("fetch", fetch);
  const bodies = ['{"name":"A"}', '{"name":"B"}'];
  const first = createRequest("/api/outfits", 1, bodies[0]);
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  const second = createRequest("/api/outfits", 1, bodies[1]);
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  const pending = [first, second];

  // when
  finish[completed](response(201, { id: 7 }));
  await pending[completed];
  const other = 1 - completed;
  const failure = expect(pending[other]).rejects.toThrow("HTTP 503");
  finish[other](response(503, {}));
  await failure;
  fetch.mockResolvedValue(response(201, { id: 8 }));
  await createRequest("/api/outfits", 1, bodies[other]);
  await createRequest("/api/outfits", 1, bodies[completed]);

  // then
  expect(key(fetch.mock.calls[2])).toBe(key(fetch.mock.calls[other]));
  expect(key(fetch.mock.calls[3])).not.toBe(key(fetch.mock.calls[completed]));
  expect(sessionStorage.length).toBe(0);
});

it("resets only the most recently submitted fingerprint in the selected actor and target", async () => {
  // given
  const fetch = vi.fn().mockRejectedValue(new TypeError("offline"));
  vi.stubGlobal("fetch", fetch);
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const commands = [
    { path: "/api/outfits", actor: 1, body: '{"name":"A"}' },
    { path: "/api/outfits", actor: 1, body: '{"name":"B"}' },
    { path: "/api/outfits", actor: 2, body: '{"name":"B"}' },
    { path: "/api/me/capsules", actor: 1, body: '{"name":"B"}' },
  ];
  for (const command of commands) {
    await createRequest(command.path, command.actor, command.body).catch(() => undefined);
  }
  // A was created first, but is now the last submitted attempt.
  await createRequest("/api/outfits", 1, commands[0].body).catch(() => undefined);
  render(<NewAttempt actorId={1} path="/api/outfits" disabled={false} onReset={vi.fn()} />);

  // when
  fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
  for (const command of commands) {
    await createRequest(command.path, command.actor, command.body).catch(() => undefined);
  }

  // then
  expect(key(fetch.mock.calls[4])).toBe(key(fetch.mock.calls[0]));
  expect(key(fetch.mock.calls[5])).not.toBe(key(fetch.mock.calls[0]));
  for (let index = 1; index < commands.length; index += 1) {
    expect(key(fetch.mock.calls[index + 5])).toBe(key(fetch.mock.calls[index]));
  }
});

it("does not reset an older unresolved attempt after the latest attempt succeeds", async () => {
  // given
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError("offline"))
    .mockResolvedValue(response(201, { id: 7 }));
  vi.stubGlobal("fetch", fetch);
  vi.spyOn(window, "confirm").mockReturnValue(true);
  await createRequest("/api/outfits", 1, '{"name":"A"}').catch(() => undefined);
  await createRequest("/api/outfits", 1, '{"name":"B"}');
  render(<NewAttempt actorId={1} path="/api/outfits" disabled={false} onReset={vi.fn()} />);

  // when
  fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
  await createRequest("/api/outfits", 1, '{"name":"A"}');

  // then
  expect(key(fetch.mock.calls[2])).toBe(key(fetch.mock.calls[0]));
});

it("does not clear a replacement key when an older request succeeds after reset", async () => {
  // given
  let finish!: (value: Response) => void;
  const fetch = vi.fn().mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve; }))
    .mockRejectedValue(new TypeError("offline"));
  vi.stubGlobal("fetch", fetch);
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const first = createRequest("/api/outfits", 1, "{}");
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  render(<NewAttempt actorId={1} path="/api/outfits" disabled={false} onReset={vi.fn()} />);

  // when
  fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
  await createRequest("/api/outfits", 1, "{}").catch(() => undefined);
  finish(response(201, { id: 7 }));
  await first;
  await createRequest("/api/outfits", 1, "{}").catch(() => undefined);

  // then
  expect(key(fetch.mock.calls[1])).not.toBe(key(fetch.mock.calls[0]));
  expect(key(fetch.mock.calls[2])).toBe(key(fetch.mock.calls[1]));
});

it.each(["idempotency_in_progress", "idempotency_failed"])("keeps %s key and requires confirmation to start again", async (code) => {
  // given
  const fetch = vi.fn().mockResolvedValue(response(409, { error: code }));
  vi.stubGlobal("fetch", fetch);
  const error = await createRequest("/api/outfits/ai", 1, "{}").catch((failure: unknown) => failure);
  expect(error).toBeInstanceOf(ApiError);
  expect(resourceErrorMessage(error, true)).not.toContain("Данные изменились");
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const reset = vi.fn();
  render(<NewAttempt actorId={1} path="/api/outfits/ai" disabled={false} onReset={reset} />);

  // when
  fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
  await createRequest("/api/outfits/ai", 1, "{}").catch(() => undefined);
  confirm.mockReturnValue(true);
  fireEvent.click(screen.getByRole("button", { name: "Новая попытка" }));
  await createRequest("/api/outfits/ai", 1, "{}").catch(() => undefined);

  // then
  expect(key(fetch.mock.calls[1])).toBe(key(fetch.mock.calls[0]));
  expect(key(fetch.mock.calls[2])).not.toBe(key(fetch.mock.calls[0]));
  expect(confirm.mock.calls[0][0]).toMatch(/дубликаты.*лимит AI/);
  expect(reset).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledTimes(3);
});

it("does not expose arbitrary server error text as an API code", async () => {
  // given
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(409, { error: "<script>private server details</script>" })));

  // when
  const error = await createRequest("/api/outfits", 1, "{}").catch((failure: unknown) => failure);

  // then
  expect(error).toBeInstanceOf(ApiError);
  expect((error as ApiError).code).toBeUndefined();
});
