#!/usr/bin/env python3
"""Adds types from tools/more_types_source.json to data/types.json (skips ids already present).
Usage: python3 tools/add_types.py"""
import json, os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import build_data as bd  # reuses gtype() / Rotax task set; does not write anything on import

data_path = os.path.join(ROOT, "data/types.json")
types = json.load(open(data_path, encoding="utf-8"))
have = {t["id"] for t in types}
src = json.load(open(os.path.join(ROOT, "tools/more_types_source.json"), encoding="utf-8"))
added = 0
for s in src:
    tid = re.sub(r"[^a-z0-9_]", "_", s["id"].lower())
    if tid in have:
        continue
    cat = "EASA" if str(s.get("category", "")).upper().startswith("EASA") else "UL"
    vals = {k: v for k, v in (s.get("speeds") or {}).items() if isinstance(v, (int, float))}
    unit = "kmh" if s.get("unit") in ("kmh", "km/h") else "kt"
    group = re.sub(r"[^A-Z0-9]", "", s["manufacturer"].upper())[:12] or "OTHER"
    t = bd.gtype(tid, s["name"], s["manufacturer"], cat, s.get("tcds"), unit, vals,
                 s.get("source") or "—", bool(s.get("verified")), [group],
                 s.get("mtow") or "—", s.get("fuel") or "—", s.get("engine") or "Rotax",
                 note=s.get("note") or None, unverified_keys=tuple(s.get("unverifiedKeys") or ()))
    if s.get("sourceUrl"):
        t["manual"][0]["items"].append(bd.item(bd.L("Źródło (link)", "Source (link)", "Zdroj (odkaz)", "Source (lien)"), s["sourceUrl"], s.get("source") or ""))
    types.append(t)
    have.add(tid)
    added += 1
types.sort(key=lambda t: (t.get("manufacturer") != "TOMARK", t.get("manufacturer", ""), t["name"]))
json.dump(types, open(data_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"added {added}, total {len(types)}")
