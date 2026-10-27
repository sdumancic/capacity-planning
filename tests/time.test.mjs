import { test } from "node:test";
import assert from "node:assert/strict";
import {
  toUTC,
  clock,
  dayInZone,
  inputDuration,
  durationMinutes,
  hours,
  unionSlots,
} from "../src/main/resources/META-INF/resources/time.mjs";
test("business-zone offsets follow the requested date through DST", () => {
  assert.equal(
    toUTC("2026-10-23", "14:00", "Europe/Zagreb"),
    "2026-10-23T12:00:00.000Z",
  );
  assert.equal(
    toUTC("2026-10-26", "13:00", "Europe/Zagreb"),
    "2026-10-26T12:00:00.000Z",
  );
  assert.equal(clock("2026-10-23T12:00:00Z", "Europe/Sofia"), "15:00");
  assert.equal(
    dayInZone("2026-10-22T23:00:00Z", "Europe/Zagreb"),
    "2026-10-23",
  );
});
test("team schedule unions overlapping and adjacent slots without mutating input", () => {
  const slot = (start, end) => ({ start: `2026-10-23T${start}:00Z`, end: `2026-10-23T${end}:00Z` });
  const input = [slot("10:00", "11:00"), slot("08:30", "09:30"), slot("08:00", "09:00"), slot("09:30", "10:00"), slot("08:15", "08:45"), slot("12:00", "12:15")];
  const original = structuredClone(input);
  assert.deepEqual(unionSlots(input), [
    { start: "2026-10-23T08:00:00.000Z", end: "2026-10-23T11:00:00.000Z" },
    { start: "2026-10-23T12:00:00.000Z", end: "2026-10-23T12:15:00.000Z" },
  ]);
  assert.deepEqual(input, original);
  assert.deepEqual(unionSlots([]), []);
});
test("ambiguous and nonexistent local times are rejected", () => {
  assert.throws(
    () => toUTC("2026-10-25", "02:30", "Europe/Zagreb"),
    /ambiguous/,
  );
  assert.throws(() => toUTC("2026-03-29", "02:30", "Europe/Zagreb"), /skipped/);
});
test("duration input preserves non-grid minutes", () => {
  assert.equal(inputDuration("1:37"), "PT97M");
  assert.equal(hours(-97), "−1:37");
  assert.equal(durationMinutes("PT-1H-30M"), -90);
  assert.equal(hours(durationMinutes("PT-45M")), "−0:45");
  assert.throws(() => inputDuration("1:75"));
});
