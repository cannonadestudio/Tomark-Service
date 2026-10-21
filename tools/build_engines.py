#!/usr/bin/env python3
"""
One-off data migration (Oct 2026), kept for reference / re-runs:
  * data/engines.json  – engine catalogue with time-based inspections and the items due at each
                         (built from research notes in tools/research/*.json)
  * data/types.json    – engine/propeller/rescue tasks removed from the types (they now come from the
                         chosen engine and the aircraft options), new fields kind + engine, Evektor
                         bulletin groups split per model, weight-shift trikes and gyroplanes added
  * data/bulletins.json – BRP-Rotax, Jabiru and UL Power documents added; Evektor groups split
Run: python3 tools/build_engines.py
"""
import json, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "tools/research")


def load(p):
    return json.load(open(os.path.join(ROOT, p), encoding="utf-8"))


def dump(p, d):
    with open(os.path.join(ROOT, p), "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=1)
        f.write("\n")


def L(pl, en):
    return {"pl": pl, "en": en, "sk": en, "fr": en}


ENG_GROUPS = {
    "rotax_912": ["ROTAX_912"], "rotax_912is": ["ROTAX_912"], "rotax_914": ["ROTAX_914"],
    "rotax_915is": ["ROTAX_915"], "rotax_916is": ["ROTAX_916"], "rotax_582": ["ROTAX_582"],
    "jabiru_2200": ["JABIRU"], "jabiru_3300": ["JABIRU"], "jabiru_gen4": ["JABIRU"], "jabiru_5100": ["JABIRU"],
    "ulpower_ul260i": ["ULPOWER"], "ulpower_ul350i": ["ULPOWER"], "ulpower_ul390i": ["ULPOWER"], "ulpower_ul520i": ["ULPOWER"],
    "zonsen": ["ZONSEN"], "hks_700e": ["HKS"], "hirth_f23": ["HIRTH"],
}
SHORT = {
    "rotax_912": "Rotax 912 UL / ULS / S", "rotax_912is": "Rotax 912 iS", "rotax_914": "Rotax 914",
    "rotax_915is": "Rotax 915 iS", "rotax_916is": "Rotax 916 iS", "rotax_582": "Rotax 582",
    "jabiru_2200": "Jabiru 2200", "jabiru_3300": "Jabiru 3300", "jabiru_gen4": "Jabiru 2200 / 3300 Gen 4", "jabiru_5100": "Jabiru 5100",
    "ulpower_ul260i": "UL Power UL260i/iS", "ulpower_ul350i": "UL Power UL350i/iS", "ulpower_ul390i": "UL Power UL390i/iS",
    "ulpower_ul520i": "UL Power UL520i/iS", "zonsen": "Zonsen (Zongshen) CA / C", "hks_700e": "HKS 700E", "hirth_f23": "Hirth F-23",
}
MAKER = {"rotax": "BRP-Rotax", "jabiru": "Jabiru", "ulpower": "UL Power", "zonsen": "Zonsen (Zongshen)", "hks": "HKS", "hirth": "Hirth"}


def fmt(h):
    return str(int(h)) if float(h).is_integer() else str(h).replace(".", ",")


def fid(h):
    return fmt(h).replace(",", "_")


def build_engine(e):
    eid = e["id"]
    rotax4 = eid.startswith("rotax_") and eid != "rotax_582"
    insp = e.get("inspections", [])
    tasks = []
    base = next((i for i in insp if i.get("everyHours") and i.get("everyMonths") and not i.get("oneTimeAtHours")
                 and not (e.get("tboHours") and i["everyHours"] >= e["tboHours"])), None)
    src_default = e.get("bulletinsPage") or ""

    def items_of(i, every=None):
        out = []
        for it in i["items"]:
            ev = it.get("everyHours") or every
            out.append({"title": L(it["pl"], it["en"]), "everyHours": ev,
                        "verified": bool(i.get("verified", True)) and it.get("verified", True) is not False})
        return out

    merged_into_base = []
    if base:
        b_items = items_of(base)
        for i in insp:
            if i is base or i.get("oneTimeAtHours") or not i.get("everyHours") or i.get("everyMonths"):
                continue
            ev = i["everyHours"]
            if ev > base["everyHours"] and ev % base["everyHours"] == 0 and not (e.get("tboHours") and ev >= e["tboHours"]):
                # 600 h / 1000 h checks: listed inside the periodic inspection, due only at those hours
                b_items += items_of(i, ev)
                merged_into_base.append(i)
        bh, bm = base["everyHours"], base["everyMonths"]
        tasks.append({
            "id": "rotax_100h" if rotax4 else f"{eid}_{fid(bh)}h",
            "title": L(f"Przegląd silnika {fmt(bh)} h / {bm} mies.", f"Engine inspection {fmt(bh)} h / {bm} months"),
            "category": "engine", "intervalHours": bh, "intervalMonths": bm, "oneTimeAtHours": None,
            "toleranceHours": 10 if eid.startswith("rotax_") else None, "mandatory": True,
            "source": base.get("source", src_default), "verified": bool(base.get("verified", True)),
            "note": L(base["note"], base["note"]) if base.get("note") else None,
            "items": b_items,
            "aliases": ["rotax_oil", "rotax_plugs", "rotax_gearbox"] if rotax4 else [],
        })
    for i in insp:
        if i is base or i in merged_into_base:
            continue
        one = i.get("oneTimeAtHours")
        ev, em = i.get("everyHours"), i.get("everyMonths")
        if e.get("tboHours") and ev and ev >= e["tboHours"]:
            continue  # TBO handled below
        if one:
            tid = "rotax_25h" if (rotax4 and one == 25) else f"{eid}_first{fid(one)}h"
        elif ev and not em:
            tid = f"{eid}_{fid(ev)}h"
        elif em and not ev:
            tid = "rotax_rubber" if (eid.startswith("rotax_") and em == 60) else f"{eid}_{em}m"
        else:
            tid = f"{eid}_{fid(ev)}h_{em}m"
        title_pl, title_en = i["title_pl"], i["title_en"]
        conditional = bool(re.search(r"tylko|only|AVGAS", title_pl + title_en, re.I))
        tasks.append({
            "id": tid, "title": L(title_pl, title_en), "category": "engine",
            "intervalHours": ev if not one else None, "intervalMonths": em, "oneTimeAtHours": one,
            "toleranceHours": None, "mandatory": not conditional,
            "source": i.get("source", src_default), "verified": bool(i.get("verified", True)),
            "note": L(i["note"], i["note"]) if i.get("note") else None,
            "items": items_of(i) if len(i["items"]) > 1 else [],
            "defaultHidden": conditional,
        })
    if e.get("tboHours") or e.get("tboYears"):
        th, ty = e.get("tboHours"), e.get("tboYears")
        txt = " / ".join(x for x in [f"{th} h" if th else "", f"{ty} lat" if ty else ""] if x)
        txt_en = " / ".join(x for x in [f"{th} h" if th else "", f"{ty} years" if ty else ""] if x)
        tasks.append({
            "id": "rotax_tbo" if eid.startswith("rotax_") else f"{eid}_tbo",
            "title": L(f"Remont główny silnika (TBO {txt})", f"Engine overhaul (TBO {txt_en})"),
            "category": "engine", "intervalHours": th, "intervalMonths": ty * 12 if ty else None, "oneTimeAtHours": None,
            "toleranceHours": None, "mandatory": True, "source": src_default,
            "verified": e.get("tboVerified", True) is not False and eid not in ("zonsen", "hirth_f23"),
            "note": L("TBO zależy od numeru seryjnego silnika – sprawdź w instrukcji.", "TBO depends on the engine serial number – check the manual.") if eid.startswith("rotax_") else None,
            "items": [],
        })
    if not base:
        # no published schedule found: a generic periodic inspection the owner can adjust
        tasks.insert(0, {
            "id": f"{eid}_100h", "title": L("Przegląd silnika 100 h / 12 mies. (ustaw wg instrukcji producenta)", "Engine inspection 100 h / 12 months (set per manufacturer)"),
            "category": "engine", "intervalHours": 100, "intervalMonths": 12, "oneTimeAtHours": None, "toleranceHours": None,
            "mandatory": True, "source": src_default or "instrukcja producenta / manufacturer manual", "verified": False, "note": None,
            "items": [{"title": L("Przegląd wg instrukcji obsługi producenta silnika", "Inspection per engine manufacturer's manual"), "everyHours": None, "verified": False}],
        })
    maker = MAKER[eid.split("_")[0]]
    return {"id": eid, "name": SHORT[eid], "fullName": e["name"], "manufacturer": maker,
            "bulletinGroups": ENG_GROUPS[eid], "tboHours": e.get("tboHours"), "tboYears": e.get("tboYears"),
            "fuel": e.get("fuel", ""), "notes": e.get("notes", ""), "bulletinsPage": e.get("bulletinsPage"), "tasks": tasks}


def engines():
    R = json.load(open(os.path.join(RES, "research_engines.json"), encoding="utf-8"))
    out = [build_engine(e) for e in R["engines"]]
    out.append({"id": "other", "name": "Inny silnik", "fullName": "Other engine", "manufacturer": "Inny / Other",
                "bulletinGroups": [], "tboHours": None, "tboYears": None, "fuel": "", "notes": "", "bulletinsPage": None,
                "tasks": [{"id": "other_100h", "title": L("Przegląd silnika (ustaw interwał wg instrukcji)", "Engine inspection (set interval per manual)"),
                           "category": "engine", "intervalHours": 100, "intervalMonths": 12, "oneTimeAtHours": None, "toleranceHours": None,
                           "mandatory": True, "source": "instrukcja producenta / manufacturer manual", "verified": False, "note": None, "items": []}]})
    return out, R


# ---------------------------------------------------------------- types
LEGACY = re.compile(r"^(rotax_|prop$|brs_)")
EVK = {"ev97": ["EVEKTOR_EV97"], "harmony": ["EVEKTOR_HARMONY"], "sportstar": ["EVEKTOR_RTC"]}


def evektor_groups(t):
    n = t["name"].lower()
    if "harmony" in n:
        return ["EVEKTOR_HARMONY"]
    if "sportstar" in n:
        return ["EVEKTOR_RTC"] if "rtc" in n else ["EVEKTOR_SPORTSTAR"]
    return ["EVEKTOR_EV97"]


def engine_for(name):
    s = (name or "").lower()
    if "915" in s: return "rotax_915is"
    if "916" in s: return "rotax_916is"
    if "914" in s: return "rotax_914"
    if "582" in s: return "rotax_582"
    if "912" in s and ("is" in s.split("912")[-1][:4]): return "rotax_912is"
    if "912" in s: return "rotax_912"
    if "jabiru" in s: return "jabiru_3300"
    if "ul power" in s or "ulpower" in s or "ul260" in s or "ul350" in s: return "ulpower_ul350i"
    return "other"


def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:40]


def trike_gyro_types(base_tasks):
    G = json.load(open(os.path.join(RES, "research_trikes_gyros.json"), encoding="utf-8"))
    out = []
    zone = {"VNE": "red", "VS": "white", "VY": "normal", "VAPP": "approach", "VA": "green", "VB": "green"}
    for t in G["types"]:
        sp = []
        for s in t.get("speeds", []):
            if s.get("value") is None:
                continue
            v, unit = s["value"], s.get("unit", "kmh")
            if unit == "mph":
                v, unit = round(v * 1.609344), "kmh"
            elif unit not in ("kt", "kmh"):
                unit = "kmh"
            lab = s.get("label_pl") or s["key"]
            sp.append({"key": s["key"], "label": L(f"{s['key']} {lab.lower()}" if not lab.upper().startswith(s["key"]) else lab,
                                                    f"{s['key']} {(s.get('label_en') or '').lower()}".strip()),
                       "value": v, "unit": unit, "source": t.get("source", ""), "verified": bool(s.get("verified", True)), "zone": zone.get(s["key"], "normal")})
        maker = t["manufacturer"]
        grp = re.sub(r"[^A-Z0-9]", "", maker.upper())[:12]
        name = t["name"]
        opts = ", ".join(t.get("engineOptions") or []) or (t.get("engine") or "")
        out.append({
            "id": ("gyro_" if t["kind"] == "gyro" else "trike_") + slug(name),
            "name": name, "family": slug(maker), "manufacturer": maker, "category": "UL", "tcds": None,
            "kind": t["kind"], "engine": engine_for(t.get("engine")),
            "bulletinGroups": [grp], "speeds": sp,
            "manual": [{"id": "data", "title": L("Dane podstawowe", "Basic data"), "items": [
                {"label": L("Silnik", "Engine"), "value": L(opts or "—", opts or "—"), "source": t.get("source", "")},
                {"label": L("Źródło danych", "Data source"), "value": L(t.get("source", ""), t.get("source", "")), "source": t.get("source", "")},
            ] + ([{"label": L("Dokumenty producenta", "Manufacturer documents"), "value": L(t["bulletinsPage"], t["bulletinsPage"]), "source": t["bulletinsPage"]}] if t.get("bulletinsPage") else [])}],
            "tasks": [dict(x) for x in base_tasks],
        })
    return out


def types(engs):
    T = load("data/types.json")
    ids = {e["id"] for e in engs}
    ul_tasks = None
    for t in T:
        t["tasks"] = [k for k in t["tasks"] if not LEGACY.match(k["id"])]
        t["bulletinGroups"] = [g for g in t["bulletinGroups"] if not g.startswith("ROTAX_")]
        if "EVEKTOR" in t["bulletinGroups"]:
            t["bulletinGroups"] = [g for g in t["bulletinGroups"] if g != "EVEKTOR"] + evektor_groups(t)
        t.setdefault("kind", "airplane")
        if t.get("engine") not in ids:
            # engine from the manual's "Engine" line, default Rotax 912
            eng_line = ""
            for sec in t.get("manual", []):
                for it in sec.get("items", []):
                    if it["label"].get("en", "").lower().startswith("engine"):
                        eng_line = it["value"].get("en", "")
            t["engine"] = engine_for(eng_line) if engine_for(eng_line) != "other" else "rotax_912"
        if t["id"] == "ev97":
            ul_tasks = [k for k in t["tasks"] if k["id"] in ("ul_100h", "ul_national")]
    T = [t for t in T if t.get("kind") == "airplane"] + trike_gyro_types(ul_tasks)
    seen = {}
    for t in T:  # unique ids (several trike + wing combinations share a long name)
        n = seen.get(t["id"], 0)
        seen[t["id"]] = n + 1
        if n:
            t["id"] = f"{t['id']}_{n + 1}"
    return T


# ---------------------------------------------------------------- bulletins
def bulletins(eng_research):
    feed = load("data/bulletins.json")
    B = feed["bulletins"]
    for b in B:
        if "EVEKTOR" in b["groups"]:
            i = b["id"].upper()
            spec = ["EVEKTOR_EV97"] if i.startswith("EVEKTOREV97") else ["EVEKTOR_SPORTSTAR"] if i.startswith("EVEKTORSPORTSTAR") \
                else ["EVEKTOR_RTC"] if i.startswith("EVEKTORRTC") else ["EVEKTOR_HARMONY"] if i.startswith("EVEKTORHARMONY") \
                else ["EVEKTOR_EV97", "EVEKTOR_SPORTSTAR", "EVEKTOR_RTC", "EVEKTOR_HARMONY"]
            b["groups"] = [g for g in b["groups"] if g != "EVEKTOR"] + spec
        if b["id"] == "ROTAX-DOCS":
            b["groups"] = ["ROTAX_912", "ROTAX_914", "ROTAX_915", "ROTAX_916", "ROTAX_582"]
    have = {b["id"] for b in B}
    norm = lambda s: re.sub(r"[^A-Z0-9]", "", s.upper())
    have_n = {norm(b["id"]) for b in B} | {norm(b["title"].split(":")[0]) for b in B}
    R = json.load(open(os.path.join(RES, "research_rotax.json"), encoding="utf-8"))["bulletins"]
    eg = {"912": "ROTAX_912", "914": "ROTAX_914", "915": "ROTAX_915", "916": "ROTAX_916", "582": "ROTAX_582"}
    added = 0
    for r in R:
        first = r["number"].split(" / ")[0]
        if r["id"] in have or norm("ROTAX" + first) in have_n:
            continue
        kind = r["kind"] if r["kind"] in ("AD", "ASB", "SB", "SIB", "DOC") else ("SIB" if r["kind"] in ("SI", "SL") else "SB")
        B.append({"id": r["id"], "date": r["date"], "title": f"Rotax {r['number']}: {r['title']}"[:260],
                  "groups": sorted({eg[x] for x in r["engines"] if x in eg}), "kind": kind,
                  "mandatory": r.get("mandatory") if kind != "SIB" else False, "url": r["url"]})
        have.add(r["id"]); added += 1
    for e in eng_research["engines"]:
        grp = ENG_GROUPS.get(e["id"])
        if not grp or grp[0].startswith("ROTAX"):
            continue
        for b in e.get("bulletins", []):
            if not b.get("number") or not b.get("date"):
                continue
            bid = grp[0] + "-" + norm(b["number"])
            if bid in have:
                continue
            B.append({"id": bid, "date": b["date"][:10], "title": f"{MAKER[e['id'].split('_')[0]]} {b['number']}: {b['title']}"[:260],
                      "groups": grp, "kind": "SB", "mandatory": b.get("mandatory"), "url": b.get("url") or e.get("bulletinsPage")})
            have.add(bid); added += 1
    B.sort(key=lambda x: x["date"], reverse=True)
    feed["version"] = feed.get("version", 0) + 1
    print("bulletins added:", added, "total:", len(B))
    return feed


if __name__ == "__main__":
    engs, research = engines()
    dump("data/engines.json", engs)
    dump("data/types.json", types(engs))
    dump("data/bulletins.json", bulletins(research))
    print("engines:", len(engs))
