import type { ApiErrorBody } from "../types/api";
import { uuid } from "./idempotency";

/*
  Single fetch wrapper for the whole app.
  - Token lives in memory only (never localStorage), so XSS can't lift a stored session.
  - Every request carries an X-Request-Id so frontend reports line up with backend logs.
  - Errors always come back as ApiError with the backend's code, message and requestId.
*/

let token: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setToken(value: string | null): void {
  token = value;
}

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;
  readonly reasons: string[];

  constructor(status: number, code: string, message: string, requestId: string | null, reasons: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.reasons = reasons;
  }
}

export function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) return e;
  return new ApiError(0, "CLIENT_ERROR", "Something went wrong in the app. Reload and try again.", null);
}

interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const requestId = uuid();
  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Request-Id": requestId,
  };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(path, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new ApiError(0, "NETWORK", "Can't reach JengaBank. Check your connection and try again.", requestId);
  }

  const isJson = res.headers.get("content-type")?.includes("application/json") ?? false;
  const payload: unknown = isJson && res.status !== 204 ? await res.json().catch(() => null) : null;

  if (!res.ok) {
    const body = (payload ?? {}) as Partial<ApiErrorBody>;
    const err = new ApiError(
      res.status,
      typeof body.code === "string" ? body.code : `HTTP_${res.status}`,
      typeof body.message === "string" ? body.message : defaultMessage(res.status),
      typeof body.requestId === "string" ? body.requestId : res.headers.get("X-Request-Id") ?? requestId,
      Array.isArray(body.reasons) ? body.reasons.filter((r): r is string => typeof r === "string") : [],
    );
    if (res.status === 401 && token && onUnauthorized) onUnauthorized();
    throw err;
  }

  return payload as T;
}

function defaultMessage(status: number): string {
  switch (status) {
    case 400: return "Some details aren't valid. Check the form and try again.";
    case 401: return "Your session has ended. Sign in again.";
    case 403: return "Your role can't do this.";
    case 404: return "We couldn't find that.";
    case 409: return "This request conflicts with an earlier one.";
    case 422: return "This was stopped by a bank rule.";
    case 423: return "This account is locked.";
    case 429: return "Too many requests. Wait a moment and try again.";
    default: return "The server hit a problem. Try again in a moment.";
  }
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

export const seg = (value: string) => encodeURIComponent(value);
