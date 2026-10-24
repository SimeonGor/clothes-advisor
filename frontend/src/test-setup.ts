import { webcrypto } from "node:crypto";
import { beforeEach } from "vitest";

Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
beforeEach(() => sessionStorage.clear());
