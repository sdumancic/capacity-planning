// Browser-boundary test doubles; does not contact SQL Server or Hub.
export default async function smoke(page) {
  page.setDefaultTimeout(6000);
  await page.unrouteAll({ behavior: "ignoreErrors" });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  const capacity = { capacityMinutes: 480, bookedMinutes: 180, availableMinutes: 300 };
  const fixture = {
    day: "2026-10-26", businessZone: "Europe/Zagreb",
    dealers: [{ id: 1, name: "Workshop" }],
    teams: [{ id: 53, dealerId: 1, name: "Service team", enabled: true, capacity,
      members: [{ id: 452, userId: 452, name: "Ana Horvat", enabled: true, capacity,
        freeIntervals: [{ start: "2026-10-26T06:00:00Z", end: "2026-10-26T16:00:00Z" }],
        breaks: [], events: [] }] }],
    orders: [{ id: 1505, dealerId: 1, title: "Service", dropoffAt: "2026-10-26T05:00:00Z", pickupAt: "2026-10-26T17:00:00Z" }],
    timePlans: [{ timePlanId: 1, workOrderId: 1505, teamId: 53, memberId: 452,
      day: "2026-10-26", version: 0, plannedDuration: "PT3H", unscheduledDuration: "PT0M",
      slots: [0, 1, 2].map(i => ({ id: 21+i, start: `2026-10-26T0${7+i}:00:00Z`,
        end: `2026-10-26T${String(8+i).padStart(2,"0")}:00:00Z`, duration: "PT1H", hubStatus: "SYNCED" })) }],
    absences: []
  };
  const writes = [];
  await page.route("**/v3/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (request.method() !== "GET") {
      const body = request.postDataJSON();
      writes.push({ path, body });
      const item = path.endsWith("/schedules") ? body.timePlans[0] : body;
      // The action syncs its own slots after commit and returns the outcome per slot.
      const status = s => s.id === 22 ? "REJECTED" : "SYNCED";
      fixture.timePlans[0].slots = item.slots.map(s => ({ ...s, hubStatus: status(s) }));
      fixture.timePlans[0].version++;
      return route.fulfill({ json: { timePlans: [], warnings: [], hubSync: item.slots.map(s =>
        status(s) === "REJECTED" ? { slotId: s.id, status: "REJECTED", errorCode: "HUB_OVERLAP" } : { slotId: s.id, status: "SYNCED" }) } });
    }
    if (path.endsWith("/config")) return route.fulfill({ json: { businessZone: "Europe/Zagreb" } });
    return route.fulfill({ json: fixture });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("http://127.0.0.1:8766/src/main/resources/META-INF/resources/index.html");
  await page.locator(".slot").first().click();
  await page.locator("#start").fill("08:15");
  await page.locator("#end").fill("09:15");
  await page.locator("#modal-submit").click();
  await page.getByRole("button", {name:"Save schedules",exact:true}).click();
  await page.getByRole("heading", {name:"Resolve calendar conflict"}).waitFor({timeout:10000});
  if (await page.locator(".slot.rejected").count() !== 1) throw Error("Rejected slot must be distinct");
  if (await page.locator(".hub-rejection").count() !== 1) throw Error("Only rejected slot belongs in resolution dialog");
  await page.getByRole("button", {name:"Keep locally",exact:true}).click();
  if (await page.locator("#modal").evaluate(d => d.open)) throw Error("Keep locally should dismiss dialog");
  // A later load shows a rejection as a slot mark only; the dialog belongs to the action that got it.
  await page.locator("#refresh").click();
  await page.waitForTimeout(500);
  if (await page.locator("#modal").evaluate(d => d.open)) throw Error("A reload must not open the conflict dialog");
  if (await page.locator(".slot.rejected").count() !== 1) throw Error("Rejected slot stays marked after reload");
  await page.locator(".slot.rejected").click();
  // Each conflict card edits its own slot in place and saves it directly.
  await page.locator(".hub-rejection input[aria-label='New start']").fill("11:00");
  await page.locator(".hub-rejection input[aria-label='New end']").fill("12:00");
  await page.getByRole("button", {name:"Change time",exact:true}).click();
  await page.getByRole("heading", {name:"Resolve calendar conflict"}).waitFor({timeout:10000});
  await page.setViewportSize({width:390,height:844});
  if (await page.locator("#modal").evaluate(d => d.getBoundingClientRect().right > innerWidth)) throw Error("Conflict dialog overflows mobile viewport");
  await page.getByRole("button", {name:"Remove slot",exact:true}).click();
  await page.waitForFunction(() => !document.querySelector("#modal").open);
  const removal = writes.at(-1);
  if (!removal.path.endsWith("/1/slots") || removal.body.slots.some(s => s.id === 22) || removal.body.slots.length !== 2) {
    throw Error("Removal must preserve the other two slots");
  }
  if (removal.body.expectedVersion !== 2) throw Error("Resolution must use current plan version");
  if (errors.length) throw Error(errors.join("\n"));
  return {passed:true, checks:["rejection from the action response", "one rejection out of three", "no dialog on reload", "keep locally", "manual reopen", "change time", "mobile dialog", "remove only rejected slot", "current version", "no browser exceptions"]};
}
