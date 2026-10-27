// Browser-boundary regression with explicit API doubles; no real plans are moved.
export default async function teamDragSmoke(page) {
  page.setDefaultTimeout(5000);
  await page.unrouteAll({ behavior: "ignoreErrors" });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const calls = [];
  let forceSlotConflict = false;
  const capacity = {
    capacityMinutes: 480,
    bookedMinutes: 0,
    availableMinutes: 480,
  };
  const member = (id, name) => ({
    id,
    userId: id,
    name,
    enabled: true,
    capacity,
    freeIntervals: [
      { start: "2026-10-23T05:00:00Z", end: "2026-10-23T10:00:00Z" },
    ],
  });
  const fixture = {
    day: "2026-10-23",
    businessZone: "Europe/Zagreb",
    dealers: [{ id: 1, name: "Demo workshop" }],
    teams: [
      {
        id: 53,
        dealerId: 1,
        name: "Toyota 1",
        enabled: true,
        capacity,
        members: [member(452, "Ana")],
      },
      {
        id: 61,
        dealerId: 1,
        name: "Toyota 2",
        enabled: true,
        capacity,
        members: [member(470, "Ivan")],
      },
    ],
    orders: [
      {
        id: 1505,
        dealerId: 1,
        title: "Tyre replacement",
        dropoffAt: "2026-10-23T05:00:00Z",
        pickupAt: "2026-10-23T14:00:00Z",
      },
    ],
    timePlans: [
      {
        timePlanId: 101,
        workOrderId: 1505,
        teamId: 53,
        memberId: null,
        day: "2026-10-23",
        version: 2,
        plannedDuration: "PT1H30M",
        unscheduledDuration: "PT1H30M",
        slots: [],
      },
    ],
    absences: [],
  };
  await page.route("**/v3/**", async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    if (request.method() === "GET")
      return route.fulfill({
        json: path.endsWith("/config")
          ? { businessZone: "Europe/Zagreb" }
          : fixture,
      });
    const body = request.postDataJSON();
    calls.push({ path, body });
    if (path.endsWith("/preview"))
      return route.fulfill({
        json: {
          source: {
            version: 2,
            plannedDuration: "PT1H30M",
            scheduledDuration: "PT0S",
            unscheduledDuration: "PT1H30M",
          },
          // Ana is nearly full; Ivan already has this work order that day.
          target: body.targetMemberId === 452
            ? { gains: true, bookedDuration: "PT7H", availableDuration: "PT1H", fits: false, existingTimePlan: null }
            : body.targetMemberId === 470
              ? {
                  gains: true, bookedDuration: "PT2H", availableDuration: "PT6H", fits: true,
                  existingTimePlan: { timePlanId: 77, plannedDuration: "PT30M", plannedAfterDuration: "PT2H" },
                }
              : { gains: true, bookedDuration: "PT0S", availableDuration: "PT8H", fits: true, existingTimePlan: null },
          slots: {
            outcome: body.slots
              ? forceSlotConflict
                ? "CONFLICT"
                : "KEEP"
              : "NONE",
            count: body.slots?.length || 0,
            conflicts: [],
          },
        },
      });
    return route.fulfill({
      json: { timePlans: [], warnings: [], hubSync: [] },
    });
  });
  await page.setViewportSize({ width: 1440, height: 1600 });
  await page.goto("http://127.0.0.1:8081");
  const card = page.locator('.team-work-card[data-plan-id="101"]');
  await card.waitFor();
  async function drag(target) {
    // Dispatch browser drag events deterministically; native pointer gestures are checked separately.
    const transfer = await page.evaluateHandle(() => new DataTransfer());
    await card.dispatchEvent("dragstart", { dataTransfer: transfer });
    await target.dispatchEvent("dragover", { dataTransfer: transfer });
    await target.dispatchEvent("drop", { dataTransfer: transfer });
    await card.dispatchEvent("dragend", { dataTransfer: transfer });
    await transfer.dispose();
  }
  const moves = () => calls.filter((c) => c.path.endsWith("/move"));
  const previews = () => calls.filter((c) => c.path.endsWith("/preview"));

  const text = (id) => page.locator(id).textContent();
  // Drop on a member of the same team: the dialog previews once, and cancelling never moves work.
  await drag(page.locator('[data-member-id="452"]'));
  await page.getByRole("heading", { name: "Assign team work" }).waitFor();
  if ((await page.locator("#assign-duration").inputValue()) !== "1:30")
    throw Error("Amount must default to all remaining time");
  if (previews().at(-1).body.duration)
    throw Error("The amount dialog previews the whole remaining time");
  // Lines come from the typed amount: 1:30 does not fit Ana's 1:00.
  if ((await text("#assign-left")) !== "0:00" || (await text("#assign-planned")) !== "8:30" ||
      (await text("#assign-available")) !== "−0:30")
    throw Error("Amount lines must be computed from the full amount");
  if (!(await page.locator("#assign-available").evaluate((n) => n.classList.contains("error"))))
    throw Error("Negative available time must be red");
  if ((await page.locator("#modal-submit").textContent()) !== "Move anyway")
    throw Error("Overbooking must say Move anyway");
  if (await page.locator("#assign-existing").count())
    throw Error("No existing-plan line when the member has no plan of the order");
  await page.locator("#cancel-modal").click();
  if (moves().length) throw Error("Cancel moved work");

  await drag(page.locator('[data-member-id="452"]'));
  await page.getByRole("heading", { name: "Assign team work" }).waitFor();
  await page.locator("#modal-submit").click();
  await page.locator("#modal").waitFor({ state: "hidden" });
  if (
    moves()[0]?.body.targetTeamId !== 53 ||
    moves()[0]?.body.targetMemberId !== 452 ||
    moves()[0]?.body.expectedVersion !== 2 ||
    moves()[0]?.body.duration ||
    moves()[0]?.body.allowOverbooking !== true
  )
    throw Error("Same-team full assignment payload incorrect");

  // Another team's employee can receive just part of the team workload; the lines follow the typing.
  await drag(page.locator('[data-member-id="470"]'));
  await page.getByRole("heading", { name: "Assign team work" }).waitFor();
  await page.locator("#assign-duration").fill("0:30");
  if ((await text("#assign-left")) !== "1:00" || (await text("#assign-planned")) !== "2:30" ||
      (await text("#assign-available")) !== "5:30" || (await text("#assign-existing")) !== "1:00")
    throw Error("Partial amount lines incorrect");
  if ((await page.locator("#modal-submit").textContent()) !== "Assign")
    throw Error("A fitting amount must say Assign");
  await page.locator("#assign-duration").fill("2:00");
  if (!(await page.locator("#modal-submit").isDisabled()))
    throw Error("More than the remaining time must disable the button");
  await page.locator("#assign-duration").fill("0:30");
  await page.locator("#modal-submit").click();
  await page.locator("#modal").waitFor({ state: "hidden" });
  if (
    moves()[1]?.body.targetTeamId !== 61 ||
    moves()[1]?.body.targetMemberId !== 470 ||
    moves()[1]?.body.duration !== "PT30M" ||
    moves()[1]?.body.allowOverbooking
  )
    throw Error("Cross-team partial assignment payload incorrect");

  // Team-to-team drop previews a full transfer directly.
  await drag(page.locator('.team-heading[data-drop-team="61"]'));
  await page.getByRole("heading", { name: "Confirm move" }).waitFor();
  if (moves().length !== 2)
    throw Error("Team transfer saved before confirmation");
  await page.locator("#modal-submit").click();
  await page.locator("#modal").waitFor({ state: "hidden" });
  if (
    moves()[2]?.body.targetTeamId !== 61 ||
    moves()[2]?.body.targetMemberId ||
    moves()[2]?.body.duration
  )
    throw Error("Team transfer must preserve unassigned whole plan");

  const count = previews().length;
  await drag(page.locator('.team-heading[data-drop-team="53"]'));
  if (previews().length !== count)
    throw Error("Drop on own team should do nothing");
  if (
    await page.locator("body[data-drag-kind],.drop-active,.is-dragging").count()
  )
    throw Error("Drag state left behind");

  // Dropping on a concrete cell assigns and schedules in the business zone.
  await drag(
    page.getByRole("button", {
      name: "Ivan, 09:00, add scheduled time",
      exact: true,
    }),
  );
  await page.getByRole("heading", { name: "Confirm move" }).waitFor();
  const scheduled = previews().at(-1).body;
  if (
    scheduled.slots?.[0]?.start !== "2026-10-23T07:00:00.000Z" ||
    scheduled.targetMemberId !== 470
  )
    throw Error("Cell drop must propose a business-zone slot");
  await page.locator("#cancel-modal").click();
  // A conflicted team-chip proposal must omit new slots, never send member-only DROP.
  forceSlotConflict = true;
  await drag(
    page.getByRole("button", {
      name: "Ivan, 09:00, add scheduled time",
      exact: true,
    }),
  );
  await page.getByRole("heading", { name: "Confirm move" }).waitFor();
  await page.locator("#modal-submit").click();
  await page.locator("#modal").waitFor({ state: "hidden" });
  const plannedOnly = moves().at(-1).body;
  if (
    plannedOnly.slots ||
    plannedOnly.slotHandling === "DROP" ||
    plannedOnly.targetMemberId !== 470
  )
    throw Error("Team-chip conflict must assign without slots or DROP");
  await page.setViewportSize({ width: 390, height: 844 });
  if (
    await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)
  )
    throw Error("Mobile page overflow");
  if (errors.length) throw Error(errors.join("\n"));
  return {
    passed: true,
    checks: [
      "same-team employee",
      "cross-team employee with partial duration",
      "other team full transfer",
      "preview and confirmation",
      "cancel without write",
      "own-team no-op",
      "cell scheduling",
      "team-chip conflict assigns planned time only",
      "drag cleanup",
      "mobile layout",
    ],
  };
}
