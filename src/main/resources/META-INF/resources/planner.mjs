import {
  dayInZone,
  clock,
  toUTC,
  durationMinutes,
  hours,
  inputDuration,
  unionSlots,
} from "./time.mjs";
const $ = (id) => document.getElementById(id);
let state,
  zone,
  dragged = null,
  busy = false;
const drafts = new Map();
const shownHubRejections = new Set();
const base = "/v3/work-order-time-plans";
function el(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") n.className = v;
    else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    // draggable is an enumerated HTML attribute: an empty value does not enable it.
    else if (k === "draggable") n.draggable = Boolean(v);
    else if (v !== null && v !== false) n.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat())
    if (c !== null && c !== undefined)
      n.append(typeof c === "object" ? c : document.createTextNode(c));
  return n;
}
function message(text, error = false) {
  $("notice").textContent = text;
  $("notice").hidden = !text;
  $("notice").className = error ? "error" : "";
}
async function api(path, method = "GET", body) {
  const response = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const e = new Error(
      `${data.code || response.status}: ${data.message || "Request failed"}`,
    );
    e.data = data;
    e.status = response.status;
    throw e;
  }
  return data;
}
const plans = () => state?.timePlans || [];
const planById = (id) => plans().find((p) => p.timePlanId === Number(id));
const allMembers = () =>
  state.teams.flatMap((t) =>
    t.members.map((m) => ({ ...m, teamId: t.id, teamName: t.name })),
  );
const slots = (p) => drafts.get(p.timePlanId) || p.slots;
const title = (p) =>
  `#${p.workOrderId} · ${state.orders.find((o) => o.id === p.workOrderId)?.title || "Work order"}`;
function capacity(c, key) {
  return c?.[`${key}Minutes`] ?? c?.[`${key}_minutes`] ?? 0;
}
async function load(background = false) {
  if (busy) return;
  busy = true;
  $("refresh").disabled = true;
  try {
    const requestedDay = $("day").value, requestedDealer = $("dealer").value;
    const data = await api(
      `/v3/planner?day=${requestedDay}&dealerId=${requestedDealer}`,
    );
    // A background response must not replace a draft or interrupt an open editor.
    if (background && (drafts.size || $("modal").open || dragged ||
        requestedDay !== $("day").value || requestedDealer !== $("dealer").value)) return;
    state = data;
    zone = data.businessZone || zone;
    render();
    $("new-plan").disabled = false;
  } catch (e) {
    message(e.message, true);
    if (background) return;
    $("teams").replaceChildren(
      el(
        "p",
        { class: "empty" },
        "Planner could not load. Check that SQL Server and the application are running.",
      ),
    );
  } finally {
    busy = false;
    $("refresh").disabled = false;
  }
}
function metric(label, value, note, cls = "") {
  return el(
    "div",
    { class: `metric ${cls}` },
    el("p", {}, label),
    el("strong", { class: value < 0 ? "negative" : "" }, hours(value)),
    el("small", {}, note),
  );
}
function render() {
  const totals = state.teams.reduce(
    (a, t) => {
      for (const k of ["capacity", "booked", "available", "pending"])
        a[k] += capacity(t.capacity, k);
      return a;
    },
    { capacity: 0, booked: 0, available: 0, pending: 0 },
  );
  $("summary").replaceChildren(
    metric("Productive capacity", totals.capacity, "hours"),
    metric("Planned workload", totals.booked, "hours"),
    metric("Available to plan", totals.available, "hours", "available"),
    metric("Pending absences", totals.pending, "hours · informational"),
  );
  $("zone").textContent = `${zone} · business time`;
  $("dealer").replaceChildren(
    ...state.dealers.map((d) =>
      el(
        "option",
        { value: d.id, selected: d.id === Number($("dealer").value) },
        d.name,
      ),
    ),
  );
  $("teams").replaceChildren(...state.teams.map(renderTeam));
  if (!state.teams.length)
    $("teams").append(
      el("p", { class: "empty" }, "No teams configured for this workshop."),
    );
  $("draft-count").textContent = drafts.size
    ? `${drafts.size} unsaved schedule${drafts.size === 1 ? "" : "s"}`
    : "";
  $("save").hidden = $("discard").hidden = !drafts.size;
}
function startDrag(event, data) {
  if (drafts.size && data.kind === "plan") {
    event.preventDefault();
    message("Save or discard schedule drafts before moving a time plan.", true);
    return;
  }
  dragged = data;
  event.dataTransfer.effectAllowed = "move";
  event.dataTransfer.setData("text/plain", String(data.planId));
  event.currentTarget.classList.add("is-dragging");
  document.body.dataset.dragKind = data.kind;
  const source = planById(data.planId);
  for (const target of document.querySelectorAll("[data-drop-team]")) {
    const sameTeam = source?.teamId === Number(target.dataset.dropTeam);
    target.classList.toggle(
      "drop-eligible",
      data.kind !== "plan" || source?.memberId != null || !sameTeam,
    );
  }
  message(
    data.kind === "plan"
      ? source?.memberId != null
        ? "Move the whole time plan: drop on another employee or a team. Scheduled times are checked in the preview."
        : "Drop on an employee to assign work, on a time cell to schedule, or on another team to transfer."
      : "Drop on an employee’s time cell or on a team to move this slot.",
  );
}
function endDrag() {
  dragged = null;
  delete document.body.dataset.dragKind;
  document
    .querySelectorAll(".drop-active, .drop-eligible, .is-dragging")
    .forEach((node) =>
      node.classList.remove("drop-active", "drop-eligible", "is-dragging"),
    );
}
function dropEvents(accept, drop) {
  return {
    ondragover: (event) => {
      if (!dragged || !accept(dragged)) return;
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "move";
      event.currentTarget.classList.add("drop-active");
    },
    ondragleave: (event) => {
      if (!event.currentTarget.contains(event.relatedTarget))
        event.currentTarget.classList.remove("drop-active");
    },
    ondrop: (event) => {
      if (!dragged || !accept(dragged)) return;
      event.currentTarget.classList.remove("drop-active");
      drop(event);
    },
  };
}
function renderTeam(t) {
  const teamPlans = plans().filter(
    (p) => p.teamId === t.id && p.memberId == null,
  );
  const acceptsTeam = (data) =>
    t.enabled &&
    (data.kind !== "plan" ||
      planById(data.planId)?.memberId != null ||
      planById(data.planId)?.teamId !== t.id);
  const heading = el(
    "div",
    {
      class: "team-heading team-drop-zone",
      "data-drop-team": t.id,
      "aria-label": `${t.name}, team drop zone`,
      ...dropEvents(acceptsTeam, (event) => dropTeam(event, t)),
    },
    el(
      "div",
      { class: "team-identity" },
      el(
        "span",
        { class: "team-avatar", "aria-hidden": true },
        t.name.slice(0, 1),
      ),
      el(
        "div",
        {},
        el("h3", {}, t.name),
        el(
          "small",
          {},
          `${t.members.length} employees · ${hours(capacity(t.capacity, "available"))} h available`,
        ),
      ),
    ),
    el("span", { class: "team-drop-hint" }, "↓ Drop here to transfer to team"),
    el("button", { onclick: () => newPlan(t.id) }, "＋ Plan time"),
  );
  const backlog = el(
    "div",
    {
      class: "team-backlog team-drop-zone",
      "data-drop-team": t.id,
      "aria-label": `${t.name}, unassigned team work`,
      ...dropEvents(acceptsTeam, (event) => dropTeam(event, t)),
    },
    el(
      "div",
      { class: "backlog-label" },
      el("strong", {}, "Team workload"),
      el("span", { class: "backlog-count" }, String(teamPlans.length)),
      el("small", {}, "Not assigned to an employee"),
    ),
    el(
      "div",
      { class: "team-plans" },
      ...teamPlans.map((p) =>
        el(
          "button",
          {
            class: "chip team-work-card",
            draggable: true,
            "data-plan-id": p.timePlanId,
            "aria-label": `${title(p)}, ${hours(durationMinutes(p.plannedDuration))} hours, move team work`,
            ondragstart: (event) =>
              startDrag(event, { kind: "plan", planId: p.timePlanId }),
            ondragend: endDrag,
            onclick: () => planDetails(p),
          },
          el("span", { class: "card-grip", "aria-hidden": true }, "⠿"),
          el(
            "span",
            { class: "card-description" },
            el("strong", {}, `#${p.workOrderId}`),
            el(
              "span",
              {},
              state.orders.find((o) => o.id === p.workOrderId)?.title ||
                "Work order",
            ),
          ),
          el(
            "span",
            { class: "card-duration" },
            hours(durationMinutes(p.plannedDuration)),
            el("small", {}, "planned"),
          ),
        ),
      ),
    ),
  );
  if (!teamPlans.length)
    backlog.lastChild.append(
      el(
        "span",
        { class: "backlog-empty" },
        "No team work waiting. Drop a work card here to transfer it.",
      ),
    );
  const grid = el(
    "div",
    { class: "grid-scroll" },
    el(
      "div",
      { class: "grid-head" },
      el("span", {}, "EMPLOYEE / PLANNED TIME"),
      el(
        "div",
        { class: "hour-labels" },
        ...Array.from({ length: 12 }, (_, i) =>
          el("span", {}, `${String(i + 6).padStart(2, "0")}:00`),
        ),
      ),
    ),
    renderTeamSchedule(t),
    ...t.members.map((m) => renderMember(t, m)),
  );
  return el(
    "section",
    { class: "team", "data-team-id": t.id },
    heading,
    backlog,
    grid,
  );
}

function renderTeamSchedule(t) {
  const memberIds = new Set(t.members.map((m) => m.id));
  const memberPlans = plans().filter(
    (p) => p.teamId === t.id && memberIds.has(p.memberId),
  );
  const combined = unionSlots(memberPlans.flatMap((p) => slots(p)));
  const timeline = el("div", {
    class: "timeline team-timeline",
    "aria-label": `${t.name}, union of employee scheduled time`,
  });
  for (const slot of combined) {
    const block = interval(slot.start, slot.end, "team-slot");
    if (!block) continue;
    const label = `${clock(slot.start, zone)}–${clock(slot.end, zone)}`;
    block.setAttribute("title", `Employees scheduled: ${label}`);
    block.setAttribute("aria-label", `Employees scheduled: ${label}`);
    block.textContent = label;
    timeline.append(block);
  }
  if (!combined.length)
    timeline.append(el("span", { class: "team-schedule-empty" }, "No scheduled time"));
  return el("div", { class: "team-schedule-row" },
    el("div", { class: "team-schedule-label" },
      el("strong", {}, "Team schedule"),
      el("small", {}, `Employee union${memberPlans.some((p) => drafts.has(p.timePlanId)) ? " · draft" : ""}`)),
    timeline);
}

function pos(instant) {
  const [h, m] = clock(instant, zone).split(":").map(Number);
  return ((h * 60 + m - 360) / 720) * 100;
}
function interval(start, end, cls) {
  const from = toUTC($("day").value, "06:00", zone);
  const to = toUTC($("day").value, "18:00", zone);
  const clippedStart = new Date(Math.max(Date.parse(start), Date.parse(from))).toISOString();
  const clippedEnd = new Date(Math.min(Date.parse(end), Date.parse(to))).toISOString();
  if (Date.parse(clippedEnd) <= Date.parse(clippedStart)) return null;
  const left = Math.max(0, pos(clippedStart)),
    right = Math.min(100, pos(clippedEnd));
  if (right <= left) return null;
  return el("div", {
    class: `interval ${cls}`,
    style: `left:${left}%;width:${right - left}%`,
  });
}
function renderMemberCalendar(m) {
  const absences = (state.absences || []).filter(
    (a) => a.userId === m.userId && ["APPROVED", "PENDING"].includes(a.status),
  );
  const tracks = [
    ["Absence", "absence", absences],
    ["Break", "break", m.breaks || []],
    ["Event", "event", m.events || []],
  ];
  const entries = tracks.flatMap(([label, kind, items]) =>
    items.map((entry) => ({ ...entry, label, kind })),
  ).sort((a, b) => Date.parse(a.start) - Date.parse(b.start));
  const lanes = [];
  const blocks = [];
  for (const entry of entries) {
      const { label, kind } = entry;
      const block = interval(entry.start, entry.end, `calendar-block calendar-${kind}${entry.status === "PENDING" ? " calendar-pending" : ""}`);
      if (!block) continue;
      // Overlapping source records retain their own lane; none hides another.
      let lane = lanes.findIndex((end) => end <= Date.parse(entry.start));
      if (lane === -1) lane = lanes.length;
      lanes[lane] = Date.parse(entry.end);
      block.style.top = `${5 + lane * 26}px`;
      const text = `${label}${entry.status === "PENDING" ? " (pending)" : ""} · ${clock(entry.start, zone)}–${clock(entry.end, zone)}`;
      block.textContent = text;
      block.setAttribute("title", text);
      block.setAttribute("aria-label", `${m.name}, ${text}`);
      blocks.push(block);
  }
  return el("div", { class: "member-calendar member-row calendar-row" },
    el("div", { class: "calendar-label" }, "Absences · breaks · events"),
    el("div", { class: "timeline calendar-timeline", style: `min-height:${Math.max(1, lanes.length) * 26 + 10}px`, "aria-label": `${m.name}, absences, breaks and events timeline` },
      ...blocks, !blocks.length ? el("span", { class: "calendar-empty" }, "—") : null));
}
function renderMember(t, m) {
  const memberPlans = plans().filter(
    (p) => p.memberId === m.id && p.teamId === t.id,
  );
  const info = el(
    "div",
    {
      class: "member-info",
      "data-member-id": m.id,
      "aria-label": `${m.name}, assign team work`,
      ...dropEvents(
        () => t.enabled && m.enabled,
        (e) => dropMember(e, t, m, null),
      ),
    },
    el("span", { class: "member-name" }, m.name),
    el("span", { class: "member-drop-hint" }, "↓ Drop work to assign"),
  );
  const available = capacity(m.capacity, "available");
  const productivity = m.capacity?.productivityPercent;
  const productivityBadge =
    Number.isInteger(productivity) && productivity >= 0 && productivity < 100
      ? el(
          "span",
          {
            class: "productivity-badge",
            title: `Productive capacity uses ${productivity}% of attended time. Shift length and scheduling intervals are unchanged.`,
          },
          `Productivity ${productivity}%`,
        )
      : null;
  const summary = el(
    "div",
    { class: "member-summary" },
    el(
      "span",
      { class: "member-order-count" },
      `${memberPlans.length} work order${memberPlans.length === 1 ? "" : "s"}`,
    ),
    productivityBadge,
    el(
      "span",
      {},
      `${hours(capacity(m.capacity, "booked"))} booked / ${hours(capacity(m.capacity, "capacity"))} h capacity`,
    ),
    el(
      "span",
      { class: available < 0 ? "negative" : "" },
      `${hours(available)} h available`,
    ),
  );
  const header = el(
    "div",
    { class: "member-row member-heading" },
    info,
    summary,
  );
  const rows = memberPlans.map((p) => renderWorkOrder(t, m, p));
  rows.unshift(renderMemberCalendar(m));
  if (!memberPlans.length)
    rows.push(
      el(
        "div",
        { class: "member-row member-empty" },
        el(
          "div",
          { class: "work-order-info empty-order-info" },
          "No work orders assigned",
        ),
        renderTimeline(t, m, null),
      ),
    );
  return el(
    "section",
    {
      class: "member-group",
      "data-member-group": m.id,
      "aria-label": `${m.name}, work orders`,
    },
    header,
    ...rows,
  );
}
function renderWorkOrder(t, m, p) {
  const scheduled = slots(p).reduce(
    (total, slot) =>
      total + (Date.parse(slot.end) - Date.parse(slot.start)) / 60000,
    0,
  );
  const remaining = Math.max(0, durationMinutes(p.plannedDuration) - scheduled);
  const info = el(
    "div",
    { class: "work-order-info" },
    el(
      "button",
      {
        type: "button",
        class: "plan-drag-handle",
        draggable: true,
        "data-plan-id": p.timePlanId,
        "aria-label": `Move time plan #${p.workOrderId} for ${m.name}`,
        title:
          "Drag the whole time plan to an employee or team; click to choose a destination",
        ondragstart: (event) =>
          startDrag(event, { kind: "plan", planId: p.timePlanId }),
        ondragend: endDrag,
        onclick: () => movePlan(p),
      },
      el("span", { "aria-hidden": true }, "⠿"),
    ),
    el(
      "button",
      { class: "work-order-label", onclick: () => planDetails(p) },
      el("strong", {}, `#${p.workOrderId}`),
      el(
        "span",
        {},
        state.orders.find((o) => o.id === p.workOrderId)?.title || "Work order",
      ),
    ),
    el(
      "div",
      { class: "work-order-meta" },
      el("span", {}, `${hours(durationMinutes(p.plannedDuration))} h planned`),
      el(
        "span",
        { class: remaining ? "remaining-time" : "" },
        `${hours(remaining)} h unscheduled`,
      ),
    ),
  );
  return el(
    "div",
    {
      class: `member-row work-order-row ${drafts.has(p.timePlanId) ? "has-draft" : ""}`,
      "data-work-order-id": p.workOrderId,
      "data-time-plan-id": p.timePlanId,
      "aria-label": `${m.name}, work order ${p.workOrderId}`,
    },
    info,
    renderTimeline(t, m, p),
  );
}
function renderTimeline(t, m, p) {
  const timeline = el("div", { class: "timeline" });
  // The complement of server free time also includes occupied slots; slots draw over it.
  let cursor = toUTC($("day").value, "06:00", zone);
  const finish = toUTC($("day").value, "18:00", zone);
  for (const f of [...(m.freeIntervals || [])].sort((a, b) =>
    a.start.localeCompare(b.start),
  )) {
    if (f.end <= cursor || f.start >= finish) continue;
    if (f.start > cursor) {
      const n = interval(cursor, f.start, "blocked");
      if (n) timeline.append(n);
    }
    if (f.end > cursor) cursor = f.end;
  }
  if (cursor < finish) {
    const n = interval(cursor, finish, "blocked");
    if (n) timeline.append(n);
  }
  for (let i = 0; i < 48; i++) {
    const time = `${String(Math.floor((360 + i * 15) / 60)).padStart(2, "0")}:${String((i * 15) % 60).padStart(2, "0")}`;
    timeline.append(
      el("button", {
        class: "cell",
        "aria-label": `${m.name}, ${p ? `#${p.workOrderId}, ` : ""}${time}, add scheduled time`,
        onclick: () =>
          p
            ? slotModal(p, null, time)
            : message(
                "Assign a work order to this employee before scheduling.",
                true,
              ),
        ...dropEvents(
          () => t.enabled && m.enabled,
          (e) => dropMember(e, t, m, time),
        ),
      }),
    );
  }
  // Other work orders and memberships remain blocked in this person's free-time layer.
  // Only this work order's slots are drawn as editable work on this row.
  for (const s of p ? slots(p) : []) {
    const left = pos(s.start),
      right = pos(s.end);
    if (right <= 0 || left >= 100) continue;
    const status = s.hubStatus || s.hubSyncStatus || "PENDING";
    timeline.append(
      el(
        "button",
        {
          class: `slot ${drafts.has(p.timePlanId) ? "draft" : ""} ${s.conflicts?.length ? "conflict" : ""} ${status === "REJECTED" ? "rejected" : ""}`,
          title: status === "REJECTED" ? "Calendar rejected this time. Click to resolve." : `Calendar: ${status}`,
          "aria-label": `#${p.workOrderId}, ${clock(s.start, zone)}–${clock(s.end, zone)}, calendar ${status.toLowerCase()}`,
          style: `left:${Math.max(0, left)}%;width:${Math.min(100, right) - Math.max(0, left)}%`,
          draggable: true,
          ondragstart: (event) =>
            startDrag(event, {
              kind: "slot",
              planId: p.timePlanId,
              slotId: s.id,
              slot: s,
            }),
          ondragend: endDrag,
          onclick: () => editSlot(p, s),
        },
        el("strong", {}, `#${p.workOrderId} ${status === "REJECTED" ? "⚠ Rejected" : status === "SYNCED" ? "" : "◷"}`),
        el("small", {}, `${clock(s.start, zone)}–${clock(s.end, zone)}`),
      ),
    );
  }
  return timeline;
}
function field(label, id, type, value = "", extra = {}) {
  return el("label", {}, label, el("input", { id, type, value, ...extra }));
}
function selectField(label, id, options, value) {
  return el(
    "label",
    {},
    label,
    el(
      "select",
      { id },
      ...options.map(([id, text]) =>
        el(
          "option",
          { value: id, selected: String(value) === String(id) },
          text,
        ),
      ),
    ),
  );
}
function assigneeOptions() {
  return state.teams.flatMap((t) => [
    [`t:${t.id}`, `${t.name} · Unassigned team`],
    ...t.members
      .filter((m) => m.enabled)
      .map((m) => [`m:${m.id}`, `${t.name} · ${m.name}`]),
  ]);
}
function target(value) {
  const [type, id] = value.split(":");
  if (type === "t") return { targetTeamId: Number(id) };
  const m = allMembers().find((m) => m.id === Number(id));
  return { targetTeamId: m.teamId, targetMemberId: m.id };
}
function showModal(title, children, submit, label = "Save") {
  $("modal-title").textContent = title;
  $("modal-body").replaceChildren(...children);
  $("modal-error").textContent = "";
  $("modal-submit").textContent = label;
  $("modal-submit").hidden = !submit;
  $("modal-form").onsubmit = async (e) => {
    e.preventDefault();
    $("modal-submit").disabled = true;
    try {
      await submit();
    } catch (error) {
      $("modal-error").textContent = error.message;
    } finally {
      $("modal-submit").disabled = false;
    }
  };
  $("modal").showModal();
}
function close() {
  $("modal").close();
}
function noDrafts() {
  if (drafts.size)
    throw Error(
      "Save or discard schedule drafts before changing planned time or assignee.",
    );
}
async function write(path, method, body) {
  const result = await api(path, method, body);
  close();
  await load();
  const warnings = result.warnings || [];
  const sync = result.hubSync || [];
  message(
    warnings.length
      ? `Saved with warnings:\n${warnings.map((w) => w.code).join("\n")}`
      : sync.some((s) => s.status === "PENDING")
        ? "Saved. The calendar is updated in the background."
        : "Changes saved.",
  );
  if (sync.some((s) => s.status === "REJECTED"))
    message(
      "Saved. A calendar rejected a scheduled slot; edit, remove or keep it from the planner.",
      true,
    );
  showActionRejections(sync);
  return result;
}
// Only the action that got REJECTED opens the dialog; a rejection found later by the worker is a slot mark.
function showActionRejections(sync) {
  const ids = new Set(sync.filter((s) => s.status === "REJECTED").map((s) => s.slotId));
  if (!ids.size) return;
  showHubRejections(plans().flatMap((plan) => (plan.slots || [])
    .filter((slot) => ids.has(slot.id))
    .map((slot) => ({ plan, slot }))));
}
function newPlan(teamId) {
  try {
    noDrafts();
  } catch (e) {
    message(e.message, true);
    return;
  }
  showModal(
    "Plan work order time",
    [
      selectField(
        "Work order",
        "order",
        state.orders.map((o) => [o.id, `#${o.id} · ${o.title}`]),
      ),
      selectField(
        "Assign to",
        "assignee",
        assigneeOptions(),
        `t:${teamId || state.teams[0]?.id}`,
      ),
      field("Duration (h:mm)", "duration", "text", "1:00", {
        required: true,
        pattern: "[0-9]+:[0-5][0-9]",
      }),
      el(
        "p",
        { class: "muted" },
        "Planned minutes count toward capacity whether scheduled or not.",
      ),
    ],
    async () => {
      const a = target($("assignee").value);
      const body = {
        workOrderId: Number($("order").value),
        teamId: a.targetTeamId,
        memberId: a.targetMemberId,
        day: $("day").value,
        duration: inputDuration($("duration").value),
      };
      const existing = plans().find(
        (p) =>
          p.workOrderId === body.workOrderId &&
          p.teamId === body.teamId &&
          (p.memberId ?? null) === (body.memberId ?? null),
      );
      if (existing) body.expectedVersion = existing.version;
      await overbookWrite(base, body);
    },
    "Plan time",
  );
}
async function overbookWrite(path, body) {
  try {
    return await write(path, "POST", body);
  } catch (e) {
    if (
      e.data?.code === "CAPACITY_EXCEEDED" &&
      confirm(
        `${e.message}\n${JSON.stringify(e.data.details || {})}\nPlan over capacity?`,
      )
    )
      return write(path, "POST", { ...body, allowOverbooking: true });
    throw e;
  }
}
function editSlot(p, s) {
  if (s.hubStatus === "REJECTED" && !drafts.has(p.timePlanId)) {
    showHubRejections([{ plan: p, slot: s }]);
  } else {
    slotModal(p, s, clock(s.start, zone));
  }
}

function hubRejectionKey(plan, slot) {
  return `${slot.id}:${plan.memberId}:${slot.start}:${slot.end}`;
}

function showHubRejections(items) {
  if (!state || drafts.size || $("modal").open) return;
  const rejected = items || plans().flatMap(plan => (plan.slots || [])
    .filter(slot => slot.hubStatus === "REJECTED" &&
      !shownHubRejections.has(hubRejectionKey(plan, slot)))
    .map(slot => ({ plan, slot })));
  if (!rejected.length) return;
  for (const { plan, slot } of rejected) shownHubRejections.add(hubRejectionKey(plan, slot));
  showModal("Resolve calendar conflict", [
    el("p", { class: "detail" },
      "Your schedule was saved, but the calendar rejected these times because they are already occupied. Other slots are unaffected. Choose a new time, remove the slot, or keep it locally without a calendar event."),
    ...rejected.map(({ plan, slot }) => el("section", { class: "hub-rejection" },
      el("h3", {}, title(plan)),
      el("p", {}, `${allMembers().find(m => m.id === plan.memberId)?.name || "Employee"} · ${plan.day} · ${clock(slot.start, zone)}–${clock(slot.end, zone)} · ${zone}`),
      el("p", { class: "hub-rejection-status" }, "Calendar rejected · Not booked on Hub"),
      el("div", { class: "text-actions" },
        el("button", { type: "button", onclick: () => {
          close();
          slotModal(plan, slot, clock(slot.start, zone));
        } }, "Change time"),
        el("button", { type: "button", class: "danger-button", onclick: async event => {
          const button = event.currentTarget;
          button.disabled = true;
          try {
            noDrafts();
            for (const other of rejected) {
              if (other.slot.id !== slot.id) {
                shownHubRejections.delete(hubRejectionKey(other.plan, other.slot));
              }
            }
            await write(`${base}/${plan.timePlanId}/slots`, "PUT", {
              expectedVersion: plan.version,
              slots: plan.slots.filter(s => s.id !== slot.id).map(({ id, start, end }) => ({ id, start, end })),
            });
            message("Slot removed. Other scheduled times and planned duration are unchanged.");
          } catch (error) {
            $("modal-error").textContent = error.message;
          } finally {
            button.disabled = false;
          }
        } }, "Remove slot"),
        el("button", { type: "button", onclick: event => {
          event.currentTarget.closest(".hub-rejection").remove();
          if (!$("modal-body").querySelector(".hub-rejection")) close();
          message("Slot kept locally. It is still rejected and has no Hub calendar booking.", true);
        } }, "Keep locally"),
      ),
    )),
  ], null);
}
function slotModal(p, s, time) {
  showModal(
    s ? "Edit scheduled time" : "Schedule work order",
    [
      el(
        "p",
        { class: "detail" },
        `${title(p)}\n${hours(durationMinutes(p.plannedDuration))} h planned · ${zone}`,
      ),
      el(
        "div",
        { class: "fields" },
        field("Start", "start", "time", time, { step: 900, required: true }),
        field(
          "End",
          "end",
          "time",
          s
            ? clock(s.end, zone)
            : `${String(Number(time.slice(0, 2)) + 1).padStart(2, "0")}:${time.slice(3)}`,
          { step: 900, required: true },
        ),
      ),
      s
        ? el(
            "div",
            { class: "text-actions" },
            el(
              "button",
              {
                type: "button",
                class: "danger-button",
                onclick: () => {
                  const current = [...slots(p)];
                  drafts.set(
                    p.timePlanId,
                    current.filter((x) => x !== s),
                  );
                  close();
                  render();
                  message("Slot removed from draft. Save schedules to apply.");
                },
              },
              "Remove slot",
            ),
          )
        : null,
    ].filter(Boolean),
    async () => {
      const start = toUTC(p.day, $("start").value, zone),
        end = toUTC(p.day, $("end").value, zone);
      if (end <= start) throw Error("End must be after start.");
      let current = [...slots(p)];
      const replacement = { id: s?.id ?? null, start, end };
      if (s) current[current.indexOf(s)] = replacement;
      else current.push(replacement);
      drafts.set(p.timePlanId, current);
      close();
      render();
      message(
        "Schedule draft updated. Save schedules to apply all drafts together.",
      );
    },
    "Update draft",
  );
}
function planDetails(p) {
  showModal(
    title(p),
    [
      el(
        "div",
        { class: "detail" },
        `Planned ${hours(durationMinutes(p.plannedDuration))} h\nUnscheduled ${hours(durationMinutes(p.unscheduledDuration))} h\nVersion ${p.version} · ${p.day}`,
      ),
      field(
        "Planned duration (h:mm)",
        "planned-duration",
        "text",
        hours(durationMinutes(p.plannedDuration)),
        { required: true },
      ),
      el(
        "div",
        { class: "text-actions" },
        el(
          "button",
          {
            type: "button",
            onclick: () => {
              close();
              movePlan(p);
            },
          },
          "Move planned time",
        ),
        el(
          "button",
          { type: "button", onclick: () => audit(p) },
          "View change log",
        ),
        el(
          "button",
          {
            type: "button",
            class: "danger-button",
            onclick: async () => {
              try {
                noDrafts();
                if (confirm("Delete this time plan and all its slots?"))
                  await write(
                    `${base}/${p.timePlanId}?expectedVersion=${p.version}`,
                    "DELETE",
                  );
              } catch (e) {
                $("modal-error").textContent = e.message;
              }
            },
          },
          "Delete plan",
        ),
      ),
    ],
    async () => {
      noDrafts();
      const body = {
        expectedVersion: p.version,
        plannedDuration: inputDuration($("planned-duration").value),
      };
      try {
        await write(`${base}/${p.timePlanId}`, "PATCH", body);
      } catch (e) {
        if (
          e.data?.code === "CAPACITY_EXCEEDED" &&
          confirm(`${e.message}\nAllow overbooking?`)
        )
          await write(`${base}/${p.timePlanId}`, "PATCH", {
            ...body,
            allowOverbooking: true,
          });
        else throw e;
      }
    },
    "Change duration",
  );
}
async function audit(p) {
  try {
    const data = await api(`${base}/${p.timePlanId}/log`);
    $("modal-body").replaceChildren(
      el("p", { class: "muted" }, "Append-only history of this time plan."),
      el("pre", {}, JSON.stringify(data, null, 2)),
    );
    $("modal-submit").hidden = true;
  } catch (e) {
    $("modal-error").textContent = e.message;
  }
}
function movePlan(p, preset) {
  try {
    noDrafts();
  } catch (e) {
    message(e.message, true);
    return;
  }
  showModal(
    "Move planned time",
    [
      el(
        "p",
        { class: "detail" },
        `${title(p)}\n${hours(durationMinutes(p.plannedDuration))} h planned`,
      ),
      selectField(
        "Move to",
        "move-assignee",
        assigneeOptions(),
        preset || `t:${p.teamId}`,
      ),
      ...(p.memberId == null
        ? [
            field(
              "Amount (h:mm, leave empty for all)",
              "move-duration",
              "text",
              "",
            ),
          ]
        : []),
      el(
        "p",
        { class: "muted" },
        "The next step previews capacity and scheduling conflicts before you confirm.",
      ),
    ],
    async () => {
      const body = {
        expectedVersion: p.version,
        ...target($("move-assignee").value),
      };
      if ($("move-duration")?.value)
        body.duration = inputDuration($("move-duration").value);
      const preview = await api(
        `${base}/${p.timePlanId}/move/preview`,
        "POST",
        body,
      );
      close();
      confirmMove(p, body, preview);
    },
    "Preview move",
  );
}
function confirmMove(p, body, preview) {
  const conflict = preview.slots?.outcome === "CONFLICT";
  const detail = el(
    "div",
    { class: "detail" },
    `Scheduled time: ${preview.slots?.outcome || "NONE"}\n${preview.merge ? "Minutes will merge into an existing time plan.\n" : ""}${preview.capacity?.gains === false ? "Team capacity does not change." : `Target available: ${preview.capacity?.availableDuration || "See details below"}`}`,
  );
  const elements = [detail];
  if (conflict)
    elements.push(
      el(
        "p",
        { class: "error" },
        p.memberId == null
          ? "The proposed scheduled time does not fit. Move planned time only to assign without a slot."
          : "Some scheduled time does not fit. Moving planned time only will remove all source slots.",
      ),
    );
  elements.push(
    el(
      "details",
      {},
      el("summary", {}, "Preview details"),
      el("pre", {}, JSON.stringify(preview, null, 2)),
    ),
  );
  if (preview.capacity?.fits === false)
    elements.push(
      el(
        "label",
        { class: "check" },
        el("input", { id: "allow-overbooking", type: "checkbox" }),
        "I accept planning over capacity.",
      ),
    );
  showModal(
    "Confirm move",
    elements,
    async () => {
      if (preview.capacity?.fits === false && !$("allow-overbooking").checked)
        throw Error("Confirm overbooking to continue.");
      const request = {
        ...body,
        expectedVersion: preview.timePlanVersion ?? p.version,
        slotHandling:
          conflict && p.memberId != null && body.targetMemberId != null
            ? "DROP"
            : "KEEP",
        allowOverbooking: preview.capacity?.fits === false,
      };
      // A team chip has no source slots to drop: omit its proposed slots instead.
      if (conflict && p.memberId == null) delete request.slots;
      try {
        await write(`${base}/${p.timePlanId}/move`, "POST", request);
      } catch (e) {
        if (e.status === 409) {
          const latest = await api(
            `${base}/${p.timePlanId}/move/preview`,
            "POST",
            body,
          );
          close();
          confirmMove(p, body, latest);
          message("The plan changed. Review the updated preview.", true);
          return;
        }
        throw e;
      }
    },
    conflict ? "Move planned time only" : "Confirm move",
  );
}
function assignTeamWork(p, team, member) {
  showModal(
    "Assign team work",
    [
      el(
        "div",
        { class: "assignment-route" },
        el("span", {}, state.teams.find((t) => t.id === p.teamId)?.name),
        el("span", { "aria-hidden": true }, "→"),
        el("strong", {}, member.name),
        el("small", {}, team.name),
      ),
      el(
        "p",
        { class: "detail" },
        `${title(p)}\n${hours(durationMinutes(p.plannedDuration))} h remaining on team`,
      ),
      field(
        "Time to assign (h:mm)",
        "assign-duration",
        "text",
        hours(durationMinutes(p.plannedDuration)),
        { required: true },
      ),
      el(
        "button",
        {
          type: "button",
          class: "all-remaining",
          onclick: () => {
            $("assign-duration").value = hours(
              durationMinutes(p.plannedDuration),
            );
          },
        },
        "All remaining",
      ),
      el(
        "p",
        { class: "muted" },
        "Assigned work stays unscheduled. Drop on a time cell to assign and schedule together.",
      ),
    ],
    async () => {
      const duration = inputDuration($("assign-duration").value);
      const amount = durationMinutes(duration);
      if (amount <= 0 || amount > durationMinutes(p.plannedDuration))
        throw Error(
          "Choose an amount greater than zero and no more than the team’s remaining time.",
        );
      const body = {
        expectedVersion: p.version,
        targetTeamId: team.id,
        targetMemberId: member.id,
      };
      if (amount < durationMinutes(p.plannedDuration)) body.duration = duration;
      const preview = await api(
        `${base}/${p.timePlanId}/move/preview`,
        "POST",
        body,
      );
      close();
      confirmMove(p, body, preview);
    },
    "Preview assignment",
  );
}
async function previewPlanTransfer(p, teamId, memberId) {
  const body = { expectedVersion: p.version, targetTeamId: teamId };
  if (memberId != null) body.targetMemberId = memberId;
  const preview = await api(
    `${base}/${p.timePlanId}/move/preview`,
    "POST",
    body,
  );
  confirmMove(p, body, preview);
}
async function dropMember(event, t, m, time) {
  event.preventDefault();
  event.stopPropagation();
  const d = dragged;
  endDrag();
  if (!d) return;
  try {
    const p = planById(d.planId);
    if (d.kind === "plan") {
      noDrafts();
      if (p.memberId != null) {
        if (p.teamId === t.id && p.memberId === m.id) return;
        await previewPlanTransfer(p, t.id, m.id);
      } else if (time) {
        await dropChip(p, t, m, time);
      } else assignTeamWork(p, t, m);
      return;
    }
    if (!time) throw Error("Drop the slot on a time cell.");
    const start = toUTC(p.day, time, zone),
      end = new Date(
        Date.parse(start) + Date.parse(d.slot.end) - Date.parse(d.slot.start),
      ).toISOString();
    if (p.memberId === m.id) {
      const next = slots(p).map((s) =>
        s === d.slot || (s.id != null && s.id === d.slotId)
          ? { id: s.id, start, end }
          : s,
      );
      drafts.set(p.timePlanId, next);
      render();
      message("Slot moved in draft. Save schedules to apply.");
    } else {
      noDrafts();
      await overbookWrite(`${base}/${p.timePlanId}/slots/${d.slotId}/move`, {
        expectedVersion: p.version,
        targetMemberId: m.id,
        start,
        end,
      });
    }
  } catch (e) {
    message(e.message, true);
  }
}
async function dropChip(p, t, m, time) {
  const start = toUTC(p.day, time, zone),
    startMs = Date.parse(start);
  const order = state.orders.find((o) => o.id === p.workOrderId);
  const free = (m.freeIntervals || []).find(
    (f) => Date.parse(f.start) <= startMs && Date.parse(f.end) > startMs,
  );
  if (!free || startMs < Date.parse(order.dropoffAt))
    throw Error("The drop time is outside free time or the vehicle window.");
  const freeMinutes = Math.floor(
    (Math.min(Date.parse(free.end), Date.parse(order.pickupAt)) - startMs) /
      60000,
  );
  const planned = durationMinutes(p.plannedDuration),
    scheduled = Math.floor(Math.min(planned, freeMinutes) / 15) * 15;
  if (scheduled <= 0)
    throw Error(
      "There is less than 15 minutes of free time for this work order.",
    );
  const body = {
    expectedVersion: p.version,
    targetTeamId: t.id,
    targetMemberId: m.id,
    slots: [
      { start, end: new Date(startMs + scheduled * 60000).toISOString() },
    ],
  };
  if (freeMinutes < planned) body.duration = `PT${scheduled}M`;
  const preview = await api(
    `${base}/${p.timePlanId}/move/preview`,
    "POST",
    body,
  );
  confirmMove(p, body, preview);
}
async function dropTeam(event, t) {
  event.preventDefault();
  event.stopPropagation();
  const d = dragged;
  endDrag();
  if (!d) return;
  try {
    noDrafts();
    const p = planById(d.planId);
    if (d.kind === "plan") {
      if (p.memberId == null && p.teamId === t.id) return;
      await previewPlanTransfer(p, t.id, null);
      return;
    }
    if (confirm("Remove this scheduled slot and move its minutes to the team?"))
      await overbookWrite(`${base}/${p.timePlanId}/slots/${d.slotId}/move`, {
        expectedVersion: p.version,
        targetTeamId: t.id,
      });
  } catch (e) {
    message(e.message, true);
  }
}
$("close-modal").onclick = $("cancel-modal").onclick = close;
$("new-plan").onclick = () => newPlan();
$("save").onclick = async () => {
  try {
    const timePlans = [...drafts].map(([id, values]) => ({
      timePlanId: id,
      expectedVersion: planById(id).version,
      slots: values.map(({ id, start, end }) => ({ id, start, end })),
    }));
    const result = await api(`${base}/schedules`, "PUT", { timePlans });
    drafts.clear();
    await load();
    message("Schedules saved.");
    showActionRejections(result.hubSync || []);
  } catch (e) {
    message(e.message, true);
  }
};
$("discard").onclick = () => {
  drafts.clear();
  render();
  message("Draft schedules discarded.");
};
function navigate(day) {
  if (drafts.size && !confirm("Discard unsaved schedules and change day?"))
    return;
  drafts.clear();
  $("day").value = day;
  message("");
  load();
}
$("previous").onclick = () =>
  navigate(
    new Date(Date.parse(`${$("day").value}T12:00:00Z`) - 86400000)
      .toISOString()
      .slice(0, 10),
  );
$("next").onclick = () =>
  navigate(
    new Date(Date.parse(`${$("day").value}T12:00:00Z`) + 86400000)
      .toISOString()
      .slice(0, 10),
  );
$("today").onclick = () => navigate(dayInZone(Date.now(), zone));
$("day").onchange = () => {
  const day = $("day").value;
  $("day").value = state?.day || day;
  navigate(day);
};
$("dealer").onchange = () => {
  if (drafts.size) {
    message("Save or discard drafts before changing workshop.", true);
    $("dealer").value = state.teams[0]?.dealerId || 1;
    return;
  }
  load();
};
$("refresh").onclick = () => {
  if (drafts.size) {
    message("Save or discard drafts before refreshing.", true);
    return;
  }
  load();
};
window.addEventListener("beforeunload", (e) => {
  if (drafts.size) {
    e.preventDefault();
    e.returnValue = "";
  }
});
try {
  const config = await api("/v3/planner/config");
  zone = config.businessZone;
  if (config.minDay) $("day").min = config.minDay;
  if (config.maxDay) $("day").max = config.maxDay;
  $("day").value = dayInZone(Date.now(), zone);
  await load();
} catch (e) {
  message(e.message, true);
  $("teams").replaceChildren(
    el(
      "p",
      { class: "empty" },
      "Unable to connect. Start SQL Server and the Quarkus application.",
    ),
  );
}
