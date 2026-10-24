// Browser drag-event regression with explicit API doubles; no real plans are moved.
export default async function memberPlanDragSmoke(page) {
  page.setDefaultTimeout(5000);
  await page.unrouteAll({ behavior: "ignoreErrors" });
  const calls = [],
    errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  let outcome = "KEEP",
    fits = true;
  const cap = {
    capacityMinutes: 480,
    bookedMinutes: 180,
    availableMinutes: 300,
  };
  const member = (id, name) => ({
    id,
    userId: id,
    name,
    enabled: true,
    capacity: cap,
    freeIntervals: [
      { start: "2026-10-23T05:00:00Z", end: "2026-10-23T14:00:00Z" },
    ],
  });
  const fixture = {
    day: "2026-10-23",
    businessZone: "Europe/Zagreb",
    dealers: [{ id: 1, name: "Workshop" }],
    teams: [
      {
        id: 53,
        dealerId: 1,
        name: "Toyota 1",
        enabled: true,
        capacity: cap,
        members: [member(452, "Ana"), member(460, "Marko")],
      },
      {
        id: 61,
        dealerId: 1,
        name: "Toyota 2",
        enabled: true,
        capacity: cap,
        members: [member(470, "Ivan")],
      },
    ],
    orders: [
      {
        id: 34084,
        dealerId: 1,
        title: "Brake inspection",
        dropoffAt: "2026-10-23T05:00:00Z",
        pickupAt: "2026-10-23T15:00:00Z",
      },
    ],
    timePlans: [
      {
        timePlanId: 42,
        workOrderId: 34084,
        teamId: 53,
        memberId: 452,
        day: "2026-10-23",
        version: 3,
        plannedDuration: "PT3H",
        unscheduledDuration: "PT2H",
        slots: [
          {
            id: 9,
            start: "2026-10-23T07:00:00Z",
            end: "2026-10-23T08:00:00Z",
            duration: "PT1H",
            hubStatus: "PENDING",
          },
        ],
      },
    ],
    absences: [],
  };
  await page.route("**/v3/**", async (route) => {
    const r = route.request(),
      path = new URL(r.url()).pathname;
    if (r.method() === "GET")
      return route.fulfill({
        json: path.endsWith("/config")
          ? { businessZone: fixture.businessZone }
          : fixture,
      });
    const body = r.postDataJSON();
    calls.push({ path, body });
    if (path.endsWith("/preview"))
      return route.fulfill({
        json: {
          timePlanVersion: 3,
          plannedDuration: "PT3H",
          scheduledDuration: "PT1H",
          sourceUnscheduledDuration: "PT2H",
          capacity: {
            bookedDuration: "PT3H",
            availableDuration: "PT5H",
            gains: body.targetMemberId != null || body.targetTeamId !== 53,
            fits,
          },
          merge: null,
          slots: {
            outcome: body.targetMemberId == null ? "REMOVED" : outcome,
            count: 1,
            conflicts: [],
          },
        },
      });
    return route.fulfill({
      json: { timePlans: [], warnings: [], hubSync: [] },
    });
  });
  await page.setViewportSize({ width: 1440, height: 1400 });
  await page.goto("http://127.0.0.1:8081");
  const handle = page.getByRole("button", {
    name: "Move time plan #34084 for Ana",
    exact: true,
  });
  await handle.waitFor();
  if (!(await handle.evaluate((n) => n.draggable)))
    throw Error("Handle must enable native HTML dragging");
  const previews = () => calls.filter((c) => c.path.endsWith("/preview"));
  const moves = () => calls.filter((c) => c.path.endsWith("/move"));
  async function drag(target) {
    const transfer = await page.evaluateHandle(() => new DataTransfer());
    const event = { dataTransfer: transfer };
    const accepted = await handle.evaluate(
      (n, transfer) =>
        n.dispatchEvent(
          new DragEvent("dragstart", {
            dataTransfer: transfer,
            bubbles: true,
            cancelable: true,
          }),
        ),
      transfer,
    );
    if (accepted) {
      await target.dispatchEvent("dragover", event);
      await target.dispatchEvent("drop", event);
      await handle.dispatchEvent("dragend", event);
    }
    await transfer.dispose();
    return accepted;
  }
  async function confirm() {
    await page
      .getByRole("heading", { name: "Confirm move", exact: true })
      .waitFor();
    await page.locator("#modal-submit").click();
    await page.locator("#modal").waitFor({ state: "hidden" });
  }

  // Keyboard/click alternative moves a whole member plan; there is no partial amount input.
  await handle.click();
  await page
    .getByRole("heading", { name: "Move planned time", exact: true })
    .waitFor();
  if (await page.locator("#move-duration").count())
    throw Error("Member plan moves must be full moves");
  await page.locator("#cancel-modal").click();

  await drag(page.locator('[data-member-id="460"]'));
  await page
    .getByRole("heading", { name: "Confirm move", exact: true })
    .waitFor();
  if (moves().length) throw Error("Drop must preview without saving");
  await page.locator("#cancel-modal").click();
  if (moves().length) throw Error("Cancelled move was saved");
  await drag(page.locator('[data-member-id="460"]'));
  await confirm();
  const same = moves().at(-1);
  if (
    same.path !== "/v3/work-order-time-plans/42/move" ||
    same.body.targetTeamId !== 53 ||
    same.body.targetMemberId !== 460 ||
    same.body.duration ||
    same.body.slots ||
    same.body.slotHandling !== "KEEP"
  )
    throw Error("Same-team drop must move the whole plan and keep its slots");

  // A member plan dropped on a time cell is still an assignee transfer, not a slot creation.
  await drag(
    page.getByRole("button", {
      name: "Ivan, 10:00, add scheduled time",
      exact: true,
    }),
  );
  await confirm();
  const across = moves().at(-1).body;
  if (
    across.targetTeamId !== 61 ||
    across.targetMemberId !== 470 ||
    across.start ||
    across.slots ||
    across.duration
  )
    throw Error("Cross-team member drop must preserve the whole plan");

  outcome = "CONFLICT";
  fits = false;
  await drag(page.locator('[data-member-id="470"]'));
  await page
    .getByRole("heading", { name: "Confirm move", exact: true })
    .waitFor();
  const count = moves().length;
  await page.locator("#modal-submit").click();
  if (moves().length !== count)
    throw Error("Overbooking must require confirmation");
  await page.locator("#allow-overbooking").check();
  await confirm();
  if (
    moves().at(-1).body.slotHandling !== "DROP" ||
    moves().at(-1).body.allowOverbooking !== true
  )
    throw Error(
      "Conflict must support full planned-time-only transfer with accepted overbooking",
    );
  outcome = "KEEP";
  fits = true;

  await drag(page.locator('.team-heading[data-drop-team="61"]'));
  await confirm();
  if (
    moves().at(-1).body.targetTeamId !== 61 ||
    moves().at(-1).body.targetMemberId ||
    moves().at(-1).body.duration
  )
    throw Error("Team drop must unassign the full plan");

  // A member's own team is a valid target and does not gain team capacity.
  await drag(page.locator('.team-heading[data-drop-team="53"]'));
  await page
    .getByRole("heading", { name: "Confirm move", exact: true })
    .waitFor();
  if (
    !(await page.locator("#modal-body").innerText()).includes(
      "Team capacity does not change",
    )
  )
    throw Error("Own-team move must explain unchanged capacity");
  await confirm();
  if (
    moves().at(-1).body.targetTeamId !== 53 ||
    moves().at(-1).body.targetMemberId
  )
    throw Error("Own-team unassignment failed");

  const before = previews().length;
  await drag(page.locator('[data-member-id="452"]'));
  if (previews().length !== before)
    throw Error("Dropping on the same assignee must do nothing");

  await page.locator(".slot").click();
  await page.locator("#start").fill("10:00");
  await page.locator("#end").fill("11:00");
  await page.locator("#modal-submit").click();
  const blocked = await drag(page.locator('[data-member-id="470"]'));
  if (blocked || previews().length !== before)
    throw Error("Unsaved drafts must block time-plan moves");
  await page
    .getByRole("button", { name: "Discard drafts", exact: true })
    .click();
  if (errors.length) throw Error(errors.join("\n"));
  return {
    passed: true,
    checks: [
      "native draggable handle",
      "keyboard alternative",
      "same-team member KEEP",
      "cross-team member and time cell",
      "preview/cancel",
      "conflict DROP and overbooking",
      "other team",
      "own team",
      "same assignee no-op",
      "unsaved draft guard",
    ],
  };
}
