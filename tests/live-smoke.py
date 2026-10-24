"""Explicit local SQL-backed API check; creates and deletes only its own plans.

Run against a dev database seeded by Liquibase, with no concurrent planner edits:
python3 tests/live-smoke.py http://127.0.0.1:8081
Audit and outbox records intentionally survive cleanup.
"""
import json
import sys
import urllib.error
import urllib.request

base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081") + "/v3/work-order-time-plans"
created = []


def request(method, path="", body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    payload = json.load(response)
    assert response.status == expected, (method, path, response.status, payload)
    return payload


def plan(identifier):
    return request("GET", f"/{identifier}")


def schedule(p, start, end):
    return {"timePlanId": p["timePlanId"], "expectedVersion": p["version"],
            "slots": [{"id": p["slots"][0]["id"] if p["slots"] else None,
                       "start": f"2026-10-23T{start}:00Z", "end": f"2026-10-23T{end}:00Z"}]}


try:
    existing = request("GET", "?day=2026-10-23")
    for order in (1505, 1506):
        assert not any(p["workOrderId"] == order and p["memberId"] == 460 for p in existing), "Test target already planned; refusing to modify it"
        p = request("POST", body={"workOrderId": order, "teamId": 53, "memberId": 460,
                                  "day": "2026-10-23", "duration": "PT30M"})["timePlans"][0]
        created.append(p["timePlanId"])
    a, b = map(plan, created)
    request("PUT", "/schedules", {"timePlans": [schedule(a, "05:00", "05:30"), schedule(b, "05:30", "06:00")]})
    a, b = map(plan, created)
    original_slot_ids = [a["slots"][0]["id"], b["slots"][0]["id"]]
    request("PUT", "/schedules", {"timePlans": [schedule(a, "05:30", "06:00"), schedule(b, "05:00", "05:30")]})
    a, b = map(plan, created)
    assert [a["slots"][0]["id"], b["slots"][0]["id"]] == original_slot_ids
    before = [a, b]
    request("PUT", "/schedules", {"timePlans": [schedule(a, "06:00", "06:30"), schedule(b, "06:00", "06:30")]}, expected=400)
    assert list(map(plan, created)) == before, "Failed batch changed persisted plans"
    request("PATCH", f"/{a['timePlanId']}", {"expectedVersion": 0, "plannedDuration": "PT60M"}, expected=409)
    request("PATCH", f"/{a['timePlanId']}", {"expectedVersion": a["version"], "plannedDuration": "PT0S"}, expected=400)
    assert plan(a["timePlanId"]) == a
    print("Live SQL-backed smoke passed: creation, scheduling, atomic swap, stable slot IDs, failed-batch rollback, stale version, duration validation")
finally:
    for identifier in created:
        p = plan(identifier)
        request("DELETE", f"/{identifier}?expectedVersion={p['version']}")
    print(f"Cleaned up {len(created)} smoke-test plans")
