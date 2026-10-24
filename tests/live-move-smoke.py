"""SQL-backed move/merge/concurrency regression; uses only its own order/day plans.

Run on the freshly seeded local app: python3 tests/live-move-smoke.py [base URL].
Refuses a work-order/day that is already planned. Audit and outbox records survive.
"""
import concurrent.futures
import json
import sys
import urllib.error
import urllib.request

base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081") + "/v3/work-order-time-plans"
created = set()
day = "2026-10-26"
order = 1505


def call(method, path="", body=None, expected=200):
    req = urllib.request.Request(base + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    data = json.load(response)
    if expected is not None:
        assert response.status == expected, (method, path, response.status, data)
    return response.status, data


def write(method, path, body):
    _, response = call(method, path, body)
    for p in response["timePlans"]:
        created.add(p["timePlanId"])
    return response["timePlans"]


def load(identifier):
    return call("GET", f"/{identifier}")[1]


def create(member, minutes):
    return write("POST", "", {"workOrderId": order, "teamId": 53, "memberId": member,
                               "day": day, "duration": f"PT{minutes}M"})[0]


def schedule(p, start, end):
    return write("PUT", f"/{p['timePlanId']}/slots", {"expectedVersion": p["version"],
                 "slots": [{"start": f"{day}T{start}:00Z", "end": f"{day}T{end}:00Z"}]})[0]


try:
    existing = call("GET", f"?day={day}&workOrderId={order}")[1]
    assert not existing, "Test order/day is already planned; refusing to modify existing data"

    source = schedule(create(460, 97), "06:00", "07:45")
    original_id, slot_id = source["timePlanId"], source["slots"][0]["id"]
    moved = write("POST", f"/{original_id}/slots/{slot_id}/move", {
        "expectedVersion": source["version"], "targetMemberId": 470,
        "start": f"{day}T08:00:00Z", "end": f"{day}T09:45:00Z"})[0]
    assert moved["timePlanId"] == original_id and moved["memberId"] == 470
    assert moved["plannedDuration"] == "PT1H37M" and moved["slots"][0]["id"] == slot_id

    team = write("POST", f"/{original_id}/slots/{slot_id}/move", {
        "expectedVersion": moved["version"], "targetTeamId": 53})[0]
    assert team["timePlanId"] != original_id and team["memberId"] is None and not team["slots"]
    assert team["plannedDuration"] == "PT1H37M"
    call("GET", f"/{original_id}", expected=404)

    split = write("POST", f"/{team['timePlanId']}/move", {
        "expectedVersion": team["version"], "targetTeamId": 53, "targetMemberId": 452, "duration": "PT30M"})
    remainder = next(p for p in split if p["memberId"] is None)
    target = next(p for p in split if p["memberId"] == 452)
    assert remainder["plannedDuration"] == "PT1H7M" and target["plannedDuration"] == "PT30M"
    target = write("POST", f"/{remainder['timePlanId']}/move", {
        "expectedVersion": remainder["version"], "targetTeamId": 53, "targetMemberId": 452})[0]
    assert target["plannedDuration"] == "PT1H37M"
    call("GET", f"/{remainder['timePlanId']}", expected=404)

    target = schedule(target, "06:00", "07:45")
    target = write("POST", f"/{target['timePlanId']}/move", {
        "expectedVersion": target["version"], "targetTeamId": 61, "targetMemberId": 470,
        "slotHandling": "DROP"})[0]
    assert target["plannedDuration"] == "PT1H37M" and not target["slots"]

    source = schedule(create(460, 60), "06:00", "07:00")
    transferred_slot = source["slots"][0]["id"]
    target = write("POST", f"/{source['timePlanId']}/move", {
        "expectedVersion": source["version"], "targetTeamId": 61, "targetMemberId": 470})[0]
    assert target["plannedDuration"] == "PT2H37M" and target["slots"][0]["id"] == transferred_slot
    call("GET", f"/{source['timePlanId']}", expected=404)

    # Both clients loaded the same version. Exactly one may add time.
    body = {"workOrderId": order, "teamId": 61, "memberId": 470, "day": day,
            "duration": "PT30M", "expectedVersion": target["version"]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(lambda _: call("POST", body=body, expected=None), range(2)))
    assert sorted(status for status, _ in replies) == [200, 409], replies
    assert load(target["timePlanId"])["plannedDuration"] == "PT3H7M"
    logs = call("GET", f"/{original_id}/log")[1]
    assert any(row["operation"] == "MOVE_SLOT" and row["minutes"] == 105 for row in logs)
    print("Live move regression passed: rounded 105/97 slot, member transfer, team conversion, split/merge, DROP, stable IDs, audit duration, concurrent stale writers")
finally:
    for identifier in sorted(created):
        status, p = call("GET", f"/{identifier}", expected=None)
        if status == 404:
            continue
        assert status == 200, (status, p)
        call("DELETE", f"/{identifier}?expectedVersion={p['version']}")
    print(f"Cleaned up surviving plans from {len(created)} test IDs")
