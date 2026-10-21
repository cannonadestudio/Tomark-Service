#!/usr/bin/env python3
"""Sanity checks for the data files – run in CI before the Gradle build."""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
errors = []

def load(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return json.load(f)

types = load("data/types.json")
groups = set()
for t in types:
    for k in ("id", "name", "family", "category", "speeds", "manual", "tasks", "bulletinGroups"):
        if k not in t:
            errors.append(f"type {t.get('id')}: missing {k}")
    if t.get("category") not in ("EASA", "UL"):
        errors.append(f"type {t['id']}: bad category")
    groups.update(t["bulletinGroups"])
    ids = [x["id"] for x in t["tasks"]]
    if len(ids) != len(set(ids)):
        errors.append(f"type {t['id']}: duplicate task ids")
    for s in t["speeds"]:
        if s["unit"] not in ("kt", "kmh"):
            errors.append(f"{t['id']}/{s['key']}: bad unit")
    for task in t["tasks"]:
        for lang in ("pl", "en", "sk", "fr"):
            if not task["title"].get(lang):
                errors.append(f"{t['id']}/{task['id']}: missing title {lang}")

# Engine catalogue: inspections with items; engine bulletin groups count as known groups.
engines = load("data/engines.json")
eids = [e["id"] for e in engines]
if len(eids) != len(set(eids)):
    errors.append("engines: duplicate ids")
for e in engines:
    groups.update(e["bulletinGroups"])
    tids = [x["id"] for x in e["tasks"]]
    if len(tids) != len(set(tids)):
        errors.append(f"engine {e['id']}: duplicate task ids")
    for task in e["tasks"]:
        if not task["title"].get("pl") or not task["title"].get("en"):
            errors.append(f"engine {e['id']}/{task['id']}: missing title")
        for it in task.get("items", []):
            if not it["title"].get("pl") or not it["title"].get("en"):
                errors.append(f"engine {e['id']}/{task['id']}: item without title")
for t in types:
    if t.get("engine") not in eids:
        errors.append(f"type {t['id']}: unknown engine {t.get('engine')}")
    if t.get("kind", "airplane") not in ("airplane", "trike", "gyro"):
        errors.append(f"type {t['id']}: bad kind")
# Model families without an own type yet (bulletins kept for owners who add their type themselves).
groups.update({"EVEKTOR_SPORTSTAR"})

for path in ("data/bulletins.json",):
    feed = load(path)
    ids = [b["id"] for b in feed["bulletins"]]
    if len(ids) != len(set(ids)):
        errors.append(f"{path}: duplicate bulletin ids")
    for bl in feed["bulletins"]:
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", bl["date"]):
            errors.append(f"{path}/{bl['id']}: bad date")
        for g in bl["groups"]:
            if g not in groups:
                errors.append(f"{path}/{bl['id']}: unknown group {g}")

ef = load("data/examiners.json")
if any(c.get("kind") != "examiner" for c in ef["contacts"]):
    errors.append("examiners.json: only kind=examiner entries allowed")
cf0 = load("data/contacts.json")
if any(c["id"].startswith("ulc-exam-") for c in cf0["contacts"]):
    errors.append("contacts.json must not contain examiners (they are downloaded on request)")
cf = load("data/contacts.json")
cids = [c["id"] for c in cf["contacts"]]
if len(cids) != len(set(cids)):
    errors.append("contacts: duplicate ids")
for c in cf["contacts"]:
    if c["kind"] not in ("manufacturer", "dealer", "rotax_distributor", "rotax_service", "mechanic", "maintenance", "instructor", "school", "examiner"):
        errors.append(f"contact {c['id']}: bad kind")
    if not (-90 <= (c.get("lat") or 0) <= 90 and -180 <= (c.get("lon") or 0) <= 180):
        errors.append(f"contact {c['id']}: bad coords")
tids = [t["id"] for t in types]
if len(tids) != len(set(tids)):
    errors.append("duplicate type ids")

if errors:
    print("\n".join(errors)); sys.exit(1)
print(f"OK: {len(types)} types, {len(ids)} bulletins, {len(cids)} contacts")
