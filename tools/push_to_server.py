#!/usr/bin/env python3
"""Sends data/bulletins.json to the own AeroHangar server; the server adds only ids it does not have.
Secrets: AH_SERVER = https://domain/aerohangar   AH_CRON_KEY = cronKey from private/state/config.php"""
import json, os, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
server = os.environ["AH_SERVER"].rstrip("/")
# WordPress plugin: AH_SERVER = https://site/wp-json/aerohangar/v1/api ; standalone: https://site/aerohangar/public
api = (server + "?a=") if "wp-json" in server else (server + "/admin/api.php?a=")
feed = json.load(open(os.path.join(ROOT, "data/bulletins.json"), encoding="utf-8"))
req = urllib.request.Request(
    f"{api}import&key={os.environ['AH_CRON_KEY']}",
    data=json.dumps({"bulletins": feed["bulletins"]}).encode(), headers={"Content-Type": "application/json"}, method="POST")
print(urllib.request.urlopen(req, timeout=60).read().decode())
