const TZ = "Africa/Nairobi";

const dateTime = new Intl.DateTimeFormat("en-KE", {
  timeZone: TZ,
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

const dateOnly = new Intl.DateTimeFormat("en-KE", {
  timeZone: TZ,
  day: "numeric",
  month: "short",
});

const timeOnly = new Intl.DateTimeFormat("en-KE", {
  timeZone: TZ,
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

function safe(iso: string, fmt: Intl.DateTimeFormat): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "\u2014" : fmt.format(d);
}

export const formatDateTime = (iso: string) => safe(iso, dateTime);
export const formatDate = (iso: string) => safe(iso, dateOnly);
export const formatTime = (iso: string) => safe(iso, timeOnly);

export function greeting(now = new Date()): string {
  const hour = Number(
    new Intl.DateTimeFormat("en-GB", { timeZone: TZ, hour: "numeric", hourCycle: "h23" }).format(now),
  );
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}
