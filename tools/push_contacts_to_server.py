#!/usr/bin/env python3
"""Sends the official ULC contact lists (ids ulc-…) to the own AeroHangar server.
Secrets: AH_SERVER = https://domain/aerohangar   AH_CRON_KEY = cronKey from private/state/config.php"""
import json, os, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
server = os.environ["AH_SERVER"].rstrip("/")
for fname, target, prefixes in (("contacts", "contacts", ["ulc-pl-"]), ("examiners", "examiners", ["ulc-exam-"])):
    items = json.load(open(os.path.join(ROOT, f"data/{fname}.json"), encoding="utf-8"))["contacts"]
    official = [c for c in items if any(c["id"].startswith(p) for p in prefixes)]
    req = urllib.request.Request(
        f"{server}/admin/api.php?a=import_contacts&key={os.environ['AH_CRON_KEY']}",
        data=json.dumps({"file": target, "prefixes": prefixes, "contacts": official}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    print(fname, urllib.request.urlopen(req, timeout=60).read().decode())
