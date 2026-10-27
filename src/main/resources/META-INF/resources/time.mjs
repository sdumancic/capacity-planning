export function dayInZone(instant, zone) {
  const p = new Intl.DateTimeFormat("en-CA", {
    timeZone: zone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(instant));
  const get = (t) => p.find((v) => v.type === t).value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}
export function clock(instant, zone) {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: zone,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(instant));
}
// Convert wall time using the offset of the requested date, never the browser zone.
// Reject skipped/ambiguous wall times rather than choosing a different instant silently.
export function toUTC(day, time, zone) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || !/^\d{2}:\d{2}$/.test(time))
    throw Error("Enter a valid date and time.");
  const wall = Date.parse(`${day}T${time}:00Z`);
  const candidates = new Set();
  for (let h = -36; h <= 36; h += 6) {
    const probe = new Date(wall + h * 3600000);
    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: zone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    }).formatToParts(probe);
    const get = (t) => parts.find((p) => p.type === t).value;
    const asUTC = Date.parse(
      `${get("year")}-${get("month")}-${get("day")}T${get("hour")}:${get("minute")}:${get("second")}Z`,
    );
    const candidate = wall - (asUTC - probe.getTime());
    if (dayInZone(candidate, zone) === day && clock(candidate, zone) === time)
      candidates.add(candidate);
  }
  if (candidates.size !== 1)
    throw Error(
      "This local time is skipped or ambiguous because of a clock change. Choose another time.",
    );
  return new Date([...candidates][0]).toISOString();
}
// java.time.Duration writes negative values per part, e.g. "PT-1H-30M" (over capacity).
export function durationMinutes(value) {
  const m = /^PT(?:(-?\d+)H)?(?:(-?\d+)M)?(?:(-?\d+)S)?$/.exec(value || "PT0S");
  if (!m) throw Error("Invalid duration returned by server.");
  return Number(m[1] || 0) * 60 + Number(m[2] || 0) + Number(m[3] || 0) / 60;
}
export function hours(minutes) {
  const sign = minutes < 0 ? "−" : "";
  const n = Math.abs(minutes);
  return `${sign}${Math.floor(n / 60)}:${String(n % 60).padStart(2, "0")}`;
}
export function inputDuration(value) {
  const m = /^(\d{1,5}):([0-5]\d)$/.exec(value);
  if (!m) throw Error("Enter duration as h:mm, for example 2:30.");
  return `PT${Number(m[1]) * 60 + Number(m[2])}M`;
}

// Union scheduled time without counting overlapping employees more than once.
export function unionSlots(slots) {
  const sorted = slots.map(({ start, end }) => ({
    start: Date.parse(start),
    end: Date.parse(end),
  })).sort((a, b) => a.start - b.start);
  const merged = [];
  for (const slot of sorted) {
    const previous = merged.at(-1);
    if (previous && slot.start <= previous.end)
      previous.end = Math.max(previous.end, slot.end);
    else merged.push({ ...slot });
  }
  return merged.map(({ start, end }) => ({
    start: new Date(start).toISOString(),
    end: new Date(end).toISOString(),
  }));
}
