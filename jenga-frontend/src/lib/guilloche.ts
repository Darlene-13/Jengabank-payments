/*
  Guilloché: the engraved line patterns printed on banknotes and cheques.
  Every voucher draws its own pattern from its transfer ID, so no two receipts look alike.
*/

export function hashSeed(input: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export function seededRandom(seed: number): () => number {
  let a = seed || 1;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function gcd(a: number, b: number): number {
  return b === 0 ? a : gcd(b, a % b);
}

/** Closed hypotrochoid, scaled to fit inside `radius`. */
export function rosettePath(
  cx: number,
  cy: number,
  radius: number,
  R: number,
  r: number,
  d: number,
): string {
  const turns = r / gcd(R, r);
  const steps = Math.min(2400, Math.max(360, Math.round(turns * 140)));
  const total = Math.PI * 2 * turns;
  const scale = radius / (R - r + d);
  const k = (R - r) / r;
  let path = "";
  for (let i = 0; i <= steps; i++) {
    const t = (i / steps) * total;
    const x = cx + scale * ((R - r) * Math.cos(t) + d * Math.cos(k * t));
    const y = cy + scale * ((R - r) * Math.sin(t) - d * Math.sin(k * t));
    path += `${i === 0 ? "M" : "L"}${x.toFixed(2)} ${y.toFixed(2)}`;
  }
  return path;
}

export function rosetteLayers(seed: string, size: number, layers = 3): string[] {
  const rand = seededRandom(hashSeed(seed));
  const c = size / 2;
  const out: string[] = [];
  for (let i = 0; i < layers; i++) {
    const R = 60 + Math.floor(rand() * 40);
    let r = 7 + Math.floor(rand() * 17);
    if (R % r === 0) r += 1;
    const d = r * (0.6 + rand() * 1.1);
    out.push(rosettePath(c, c, c * (0.98 - i * 0.2), R, r, d));
  }
  return out;
}

/** Interlaced sine bands, like the border of a cheque. */
export function bandPaths(seed: string, width: number, height: number, count = 7): string[] {
  const rand = seededRandom(hashSeed(seed));
  const k1 = (2 + Math.floor(rand() * 4)) * ((Math.PI * 2) / width);
  const k2 = (7 + Math.floor(rand() * 9)) * ((Math.PI * 2) / width);
  const a1 = height * (0.22 + rand() * 0.12);
  const a2 = height * (0.06 + rand() * 0.08);
  const mid = height / 2;
  const steps = Math.ceil(width / 3);
  const out: string[] = [];
  for (let n = 0; n < count; n++) {
    const p1 = (n / count) * Math.PI * 2;
    const p2 = (n / count) * Math.PI * 4;
    let path = "";
    for (let i = 0; i <= steps; i++) {
      const x = (i / steps) * width;
      const y = mid + a1 * Math.sin(k1 * x + p1) + a2 * Math.sin(k2 * x + p2);
      path += `${i === 0 ? "M" : "L"}${x.toFixed(1)} ${y.toFixed(2)}`;
    }
    out.push(path);
  }
  return out;
}
