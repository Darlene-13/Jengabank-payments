/*
  Money stays in integer cents end to end. Formatting and parsing use string
  arithmetic so no floating-point value ever touches an amount.
*/

const MAX_WHOLE_DIGITS = 11;

function groupThousands(n: number): string {
  return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

export function splitMinor(minor: number): { whole: string; cents: string; negative: boolean } {
  const negative = minor < 0;
  const abs = Math.abs(Math.trunc(minor));
  return {
    negative,
    whole: groupThousands(Math.floor(abs / 100)),
    cents: (abs % 100).toString().padStart(2, "0"),
  };
}

export function formatMinor(
  minor: number,
  opts: { currency?: boolean; sign?: boolean } = {},
): string {
  const { negative, whole, cents } = splitMinor(minor);
  const sign = negative ? "\u2212" : opts.sign && minor !== 0 ? "+" : "";
  const value = `${sign}${whole}.${cents}`;
  return opts.currency === false ? value : `KES ${value}`;
}

/** Parses "12,500.5" into 1250050. Returns null for anything invalid or not positive. */
export function parseAmountToMinor(input: string): number | null {
  const cleaned = input.replace(/[,\s]/g, "");
  const pattern = new RegExp(`^\\d{1,${MAX_WHOLE_DIGITS}}(\\.\\d{0,2})?$`);
  if (!pattern.test(cleaned)) return null;
  const [whole, fraction = ""] = cleaned.split(".");
  const minor = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return Number.isSafeInteger(minor) && minor > 0 ? minor : null;
}
