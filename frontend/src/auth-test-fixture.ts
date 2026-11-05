import type { Actor } from "./api";
import { installSession } from "./auth";

export function authenticatedActor(actor: Actor) {
  installSession({ actor, accessToken: `test-token-${actor.id}` });
}
