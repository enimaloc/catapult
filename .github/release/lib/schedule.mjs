// Scheduled releases: the date typed in a promotion PR, and the App's comments that make it active.

const ZONE = "Europe/Paris";
const DATE = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/;
const MARKER = /<!-- release-schedule: (?:none|(\S+) ([0-9a-f]{40})) -->/;
const UTC_OFFSETS = [1, 2]; // Paris is UTC+1 in winter, UTC+2 in summer

export const NO_SCHEDULE = "<!-- release-schedule: none -->";
export const scheduleMarker = (at, head) => `<!-- release-schedule: ${at.toISOString()} ${head} -->`;

/** A date as `AAAA-MM-JJ HH:MM`, in Paris time. */
export function formatParis(date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: ZONE,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(date);
  const part = (type) => parts.find((p) => p.type === type).value;
  return `${part("year")}-${part("month")}-${part("day")} ${part("hour")}:${part("minute")}`;
}

/**
 * The instant of a Paris `AAAA-MM-JJ HH:MM`, or null when it is malformed, does not exist, or happens
 * twice (the hours skipped and repeated by the clock changes).
 */
export function parseScheduleDate(text) {
  const match = DATE.exec(text);
  if (!match) return null;
  const [year, month, day, hour, minute] = match.slice(1).map(Number);
  const instants = UTC_OFFSETS.map((offset) => new Date(Date.UTC(year, month - 1, day, hour - offset, minute))).filter(
    (instant) => formatParis(instant) === text,
  );
  return instants.length === 1 ? instants[0] : null;
}

/**
 * The active schedule {at, head}, or null: the last marker wins, and only an untouched comment of the
 * App counts, never the PR body.
 */
export function activeSchedule(comments, appSlug) {
  const markers = comments.filter(
    (c) => c.author === `${appSlug}[bot]` && c.updatedAt === c.createdAt && MARKER.test(c.body),
  );
  if (markers.length === 0) return null;
  const [, at, head] = MARKER.exec(markers[markers.length - 1].body);
  return at ? { at: new Date(at), head } : null;
}
