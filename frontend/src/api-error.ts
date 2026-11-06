export class ApiError extends Error {
  constructor(readonly status: number, readonly code?: string) { super(`HTTP ${status}`); }
}

export async function checkResponse(response: Response): Promise<Response> {
  if (response.ok) return response;
  const body: unknown = await response.json().catch(() => null);
  const code = body && typeof body === "object" && "error" in body
    && typeof body.error === "string" && /^[a-z_]{1,80}$/.test(body.error) ? body.error : undefined;
  throw new ApiError(response.status, code);
}
