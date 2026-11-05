import { webcrypto } from "node:crypto";
import { beforeEach } from "vitest";
import { installSession } from "./auth";

Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
beforeEach(() => { sessionStorage.clear(); installSession(null); });
