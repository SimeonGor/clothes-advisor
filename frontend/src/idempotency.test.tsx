import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError, resourceErrorMessage } from "./api";
import { createRequest, NewAttempt } from "./idempotency";

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const response = (status: number, body: unknown) => ({ ok: status < 400, status, json: async () => body } as Response);
const key = (call: unknown[]) => new Headers((call[1] as RequestInit).headers).get("Idempotency-Key");

it.each(["/api/wardrobe/items", "/api/wardrobe/items/7/photos", "/api/outfits", "/api/stylist/clients/9/outfits", "/api/outfits/ai"])(
  "retains uncertain identity across module reload and clears confirmed success: %s", async (path) => {
    // given
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValue(response(201, { id: 7 }));
    vi.stubGlobal("fetch", fetch);
    await expect(createRequest(path, 1, '{"name":"private name"}')).rejects.toThrow("offline");
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain("private name");

    // when
    vi.resetModules();
    const reloaded = await import("./idempotency");
    await reloaded.createRequest(path, 1, '{"name":"private name"}');
    await reloaded.createRequest(path, 1, '{"name":"private name"}');

    // then
    expect(key(fetch.mock.calls[1])).toBe(key(fetch.mock.calls[0]));
    expect(key(fetch.mock.calls[2])).not.toBe(key(fetch.mock.calls[0]));
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

  // then
  const keys = fetch.mock.calls.map(key);
  expect(keys[0]).toBe(keys[1]);
  expect(new Set([keys[0], ...keys.slice(2)]).size).toBe(5);
  expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain("private");
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
