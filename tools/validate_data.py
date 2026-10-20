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

cf = load("data/contacts.json")
cids = [c["id"] for c in cf["contacts"]]
if len(cids) != len(set(cids)):
    errors.append("contacts: duplicate ids")
for c in cf["contacts"]:
    if c["kind"] not in ("manufacturer", "dealer", "rotax_distributor", "rotax_service", "mechanic", "instructor", "examiner"):
        errors.append(f"contact {c['id']}: bad kind")
    if not (-90 <= c.get("lat", 0) <= 90 and -180 <= c.get("lon", 0) <= 180):
        errors.append(f"contact {c['id']}: bad coords")
tids = [t["id"] for t in types]
if len(tids) != len(set(tids)):
    errors.append("duplicate type ids")

if errors:
    print("\n".join(errors)); sys.exit(1)
print(f"OK: {len(types)} types, {len(ids)} bulletins, {len(cids)} contacts")
