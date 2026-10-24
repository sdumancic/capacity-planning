// Explicit browser-boundary test doubles. This does not verify SQL Server or the API.
// Run this exported function with a Playwright page and a running app on port 8081.
export default async function smoke(page) {
  page.setDefaultTimeout(5000);
  await page.unrouteAll({ behavior: "ignoreErrors" });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const requests = [];
  const capacity = {
    shiftMinutes: 480,
    blockedMinutes: 0,
    attendedMinutes: 480,
    productivityPercent: 100,
    capacityMinutes: 480,
    bookedMinutes: 180,
    availableMinutes: 300,
    pendingMinutes: 60,
  };
  const fixture = {
    day: "2026-10-23",
    businessZone: "Europe/Zagreb",
    dealers: [{ id: 1, name: "Zagreb workshop" }],
    teams: [
      {
        id: 53,
        dealerId: 1,
        name: "Toyota service",
        enabled: true,
        capacity: { ...capacity, capacityMinutes: 960, availableMinutes: 780 },
        members: [
          {
            id: 452,
            userId: 452,
            name: "Ana Horvat",
            enabled: true,
            capacity,
            breaks: [{start: "2026-10-23T10:00:00Z", end: "2026-10-23T11:00:00Z"}],
            events: [{id: 1, start: "2026-10-23T11:30:00Z", end: "2026-10-23T12:30:00Z"}],
            freeIntervals: [
              { start: "2026-10-23T05:00:00Z", end: "2026-10-23T07:00:00Z" },
              { start: "2026-10-23T08:00:00Z", end: "2026-10-23T10:00:00Z" },
              { start: "2026-10-23T11:00:00Z", end: "2026-10-23T14:00:00Z" },
            ],
          },
          {
            id: 460,
            userId: 460,
            name: "Marko Babić",
            enabled: true,
            capacity: { ...capacity, bookedMinutes: 0, availableMinutes: 480 },
            freeIntervals: [
              { start: "2026-10-23T05:00:00Z", end: "2026-10-23T10:00:00Z" },
              { start: "2026-10-23T11:00:00Z", end: "2026-10-23T14:00:00Z" },
            ],
          },
        ],
      },
    ],
    orders: [
      {
        id: 34084,
        dealerId: 1,
        title: "Annual service · Toyota Corolla",
        dropoffAt: "2026-10-23T04:00:00Z",
        pickupAt: "2026-10-23T16:00:00Z",
      },
    ],
    timePlans: [
      {
        timePlanId: 1,
        workOrderId: 34084,
        teamId: 53,
        memberId: 452,
        day: "2026-10-23",
        version: 3,
        plannedDuration: "PT3H",
        unscheduledDuration: "PT2H",
        slots: [
          {
            id: 10,
            start: "2026-10-23T07:00:00Z",
            end: "2026-10-23T08:00:00Z",
            duration: "PT1H",
            hubStatus: "PENDING",
          },
        ],
      },
      {
        timePlanId: 2,
        workOrderId: 34084,
        teamId: 53,
        memberId: null,
        day: "2026-10-23",
        version: 0,
        plannedDuration: "PT2H",
        unscheduledDuration: "PT2H",
        slots: [],
      },
    ],
    absences: [
      {id: 2, userId: 452, start: "2026-10-23T10:30:00Z", end: "2026-10-23T11:45:00Z", status: "APPROVED"},
      {id: 3, userId: 460, start: "2026-10-22T22:00:00Z", end: "2026-10-23T22:00:00Z", status: "APPROVED"},
      {id: 4, userId: 452, start: "2026-10-23T11:00:00Z", end: "2026-10-23T12:00:00Z", status: "CANCELLED"},
      {
        id: 1,
        userId: 452,
        start: "2026-10-23T11:00:00Z",
        end: "2026-10-23T12:00:00Z",
        status: "PENDING",
      },
    ],
  };
  fixture.orders.push({
    id: 1511,
    dealerId: 1,
    title: "Engine inspection",
    dropoffAt: "2026-10-23T04:00:00Z",
    pickupAt: "2026-10-23T16:00:00Z",
  });
  fixture.timePlans.push({
    timePlanId: 3,
    workOrderId: 1511,
    teamId: 53,
    memberId: 452,
    day: "2026-10-23",
    version: 0,
    plannedDuration: "PT2H",
    unscheduledDuration: "PT2H",
    slots: [],
  });
  await page.route("**/v3/**", async (route) => {
    const r = route.request(),
      url = new URL(r.url());
    if (r.method() !== "GET") {
      requests.push({ path: url.pathname, body: r.postDataJSON() });
      return route.fulfill({
        json: { timePlans: [], warnings: [], hubSync: [] },
      });
    }
    if (url.pathname.endsWith("/config"))
      return route.fulfill({ json: { businessZone: "Europe/Zagreb" } });
    return route.fulfill({ json: fixture });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  // The planner opens "today" in the business zone; pin it to the fixture day so the check is date-independent.
  await page.clock.setFixedTime(new Date(`${fixture.day}T10:00:00Z`));
  await page.goto("http://127.0.0.1:8081");
  await page.getByRole("heading", { name: "Toyota service" }).waitFor();
  if ((await page.locator(".slot").count()) !== 1)
    throw Error("Expected one scheduled slot");
  if ((await page.locator(".slot small").textContent()) !== "09:00–10:00")
    throw Error("Business-zone rendering failed");
  const ana = page.locator('[data-member-group="452"]');
  if ((await ana.locator('.calendar-row').count()) !== 1 ||
      (await ana.locator('.calendar-timeline').count()) !== 1)
    throw Error('Absences, breaks and events must share one calendar row');
  const calendar = await ana.locator('.member-calendar').evaluate(root => {
    const blocks = [...root.querySelectorAll('.calendar-block')];
    const overlap = (a,b) => {const x=a.getBoundingClientRect(),y=b.getBoundingClientRect(); return x.left<y.right && x.right>y.left && x.top<y.bottom && x.bottom>y.top;};
    return {count:blocks.length, overlaps:blocks.some((a,i)=>blocks.slice(i+1).some(b=>overlap(a,b))), colors:new Set(blocks.map(b=>getComputedStyle(b).backgroundColor)).size, pending:root.querySelectorAll('.calendar-pending').length};
  });
  if (calendar.count !== 4 || calendar.overlaps || calendar.colors !== 3 || calendar.pending !== 1)
    throw Error(`Calendar tracks failed: ${JSON.stringify(calendar)}`);
  const fullDay = await page.locator('[data-member-group="460"] .calendar-block').evaluate(b=>({left:b.style.left,width:b.style.width}));
  if (fullDay.left !== '0%' || fullDay.width !== '100%') throw Error('Full-day absence was not clipped to displayed day');
  if ((await ana.locator(".work-order-row").count()) !== 2)
    throw Error("One work-order row per assigned plan is required");
  if (
    (await ana
      .locator('.work-order-row[data-work-order-id="34084"] .slot')
      .count()) !== 1 ||
    (await ana
      .locator('.work-order-row[data-work-order-id="1511"] .slot')
      .count()) !== 0
  )
    throw Error("Slots leaked into another work order row");
  if (
    (await page
      .locator('[data-member-group="460"] .work-order-row')
      .count()) !== 0
  )
    throw Error("Empty employee must not show fabricated work orders");
  await page
    .getByRole("button", { name: "＋ Plan time", exact: true })
    .first()
    .click();
  await page.locator("#assignee").selectOption("m:452");
  await page.locator("#duration").fill("1:37");
  await page.locator("#modal-submit").click();
  if (
    requests[0]?.body.duration !== "PT97M" ||
    requests[0]?.body.expectedVersion !== 3
  )
    throw Error("Additive plan payload incorrect");
  await page.locator(".slot").click();
  await page.locator("#start").fill("10:00");
  await page.locator("#end").fill("11:00");
  await page.locator("#modal-submit").click();
  await page
    .getByRole("button", { name: "Save schedules", exact: true })
    .click();
  const batch = requests.find((r) => r.path.endsWith("/schedules"))?.body;
  if (batch?.timePlans?.[0]?.slots?.[0]?.start !== "2026-10-23T08:00:00.000Z")
    throw Error("Schedule batch or UTC conversion incorrect");
  // Clicking a child row schedules that order without a separate selection control.
  await page
    .getByRole("button", {
      name: "Ana Horvat, #1511, 11:00, add scheduled time",
      exact: true,
    })
    .click();
  await page.locator("#end").fill("11:30");
  await page.locator("#modal-submit").click();
  if (
    (await ana
      .locator('.work-order-row[data-work-order-id="1511"] .slot')
      .count()) !== 1
  )
    throw Error("Draft slot did not remain in its own order row");
  await page
    .getByRole("button", { name: "Save schedules", exact: true })
    .click();
  const childBatch = requests
    .filter((r) => r.path.endsWith("/schedules"))
    .at(-1)?.body;
  if (childBatch?.timePlans?.[0]?.timePlanId !== 3)
    throw Error("Row click scheduled the wrong work order");
  await page.setViewportSize({ width: 390, height: 844 });
  if (
    await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth,
    )
  )
    throw Error("Page overflows mobile viewport");
  if (errors.length) throw Error(errors.join("\n"));
  return {
    passed: true,
    checks: [
      "employee to work-order hierarchy",
      "one combined calendar row with distinct colors and no hidden overlaps",
      "full-day absence clipping and cancelled absence exclusion",
      "one row per work order including unscheduled orders",
      "isolated slots and direct row scheduling",
      "empty employees",
      "business-zone display",
      "additive planning version",
      "ISO non-grid duration",
      "draft schedule batch",
      "business-zone UTC input",
      "mobile overflow",
      "no browser exceptions",
    ],
  };
}
