/*
  UUID v4 from crypto.getRandomValues. crypto.randomUUID() only exists in secure
  contexts (https or localhost); the event range may serve plain http over WireGuard.
*/
export function uuid(): string {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** One key per user intent. Reuse it for retries of the same request; make a new one if anything changes. */
export const newIdempotencyKey = uuid;
