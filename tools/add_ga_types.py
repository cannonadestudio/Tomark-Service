#!/usr/bin/env python3
"""Adds certified GA aircraft (Piper, Cessna, Cirrus, PZL, Socata) and their engines (Lycoming, Continental,
PZL-Franklin, AI-14RA) to data/types.json and data/engines.json. Sources: tools/research/ga_types.json (airframes,
EASA/FAA TCDS + POH) and the engine data below (Lycoming SI 1009 / SB 480, Continental SIL98-9 / maintenance manual).
Idempotent: existing ids are replaced. Usage: python3 tools/add_ga_types.py"""
import json, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import build_data as bd  # gtype(), L(), item(); writes nothing on import

def L(pl, en):
    return {"pl": pl, "en": en, "sk": en, "fr": en}

def it(pl, en, every=None, verified=True):
    return {"title": L(pl, en), "everyHours": every, "verified": verified}

def task(id_, title, hours=None, months=None, items=(), source="", verified=True, mandatory=True, note=None, cat="engine"):
    return {"id": id_, "title": title, "category": cat, "intervalHours": hours, "intervalMonths": months, "oneTimeAtHours": None,
            "toleranceHours": 10 if hours and hours >= 100 else None, "mandatory": mandatory, "source": source, "verified": verified,
            "note": note, "items": list(items)}

LYC_SRC = "Lycoming SI 1009BF (TBO), SB 480F (olej), SI 1191A (sprężanie)"
LYC_INSP = [
    it("Pomiar sprężania różnicowy (SI 1191A)", "Cylinder compression test (differential, SI 1191A)"),
    it("Świece: czyszczenie, przerwa, rotacja", "Spark plugs: clean, gap, rotate"),
    it("Iskrowniki: zapłon i wiązka zapłonowa", "Magneto timing and ignition harness"),
    it("Olej i filtr: wymiana, filtr rozciąć i sprawdzić, sito ssące", "Oil and filter change, cut filter, clean suction screen"),
    it("Układ dolotowy i filtr powietrza", "Induction system and air filter"),
    it("Układ wydechowy (pęknięcia, nieszczelności)", "Exhaust system (cracks, leaks)"),
    it("Układ paliwowy: gaźnik / wtrysk, przewody", "Fuel system: carburettor / injection, lines"),
    it("Owiewki, przegrody chłodzenia, żebra cylindrów", "Baffles, cowling, cylinder fins"),
    it("Łoże silnika, węże, wycieki", "Engine mounts, hoses, leaks"),
    it("Sterowanie silnikiem (gaz, mieszanka, obroty)", "Engine controls (throttle, mixture, prop)"),
    it("Przegląd biuletynów (SB/SI) i AD", "SB / SI and AD compliance check"),
]
TCM_SRC = "Continental SIL98-9C (TBO), Maintenance Manual X30634A (olej 50 h / 6 mies., przegląd 100 h)"
TCM_INSP = [it("Pomiar sprężania różnicowy", "Cylinder compression test (differential)")] + LYC_INSP[1:3] + [
    it("Olej: wymiana, filtr rozciąć i sprawdzić", "Oil drain, filter cut and inspect"),
    it("Cylindry, żebra i przegrody chłodzenia", "Cylinders, cooling fins and baffling"),
    it("Układ dolotowy i wydechowy", "Induction and exhaust systems"),
    it("Paliwo / olej: przewody, wycieki, dysze wtrysku", "Fuel / oil lines, leaks, injector nozzles"),
    it("Sterowanie, cięgna, akcesoria, łoże", "Controls, cables, accessories, engine mount"),
    it("Próba silnika po przeglądzie", "Post-inspection engine run"),
    LYC_INSP[-1],
]
TURBO = it("Turbosprężarka, wastegate, sterownik", "Turbocharger, wastegate, controller")

def lyc(id_, name, full, tbo, tbo_note, fuel, turbo=False):
    insp = LYC_INSP[:4] + ([TURBO] if turbo else []) + LYC_INSP[4:] + [
        it("Iskrowniki – przegląd 500 h (wg Slick / Bendix)", "Magnetos – 500 h inspection (Slick / Bendix)", 500, False)]
    return {"id": id_, "name": name, "fullName": full, "manufacturer": "Lycoming", "bulletinGroups": ["LYCOMING"],
            "tboHours": tbo, "tboYears": 12, "fuel": fuel,
            "notes": f"TBO {tbo} h / 12 lat wg Lycoming SI 1009BF ({tbo_note}). Olej co 50 h lub 4 mies. z filtrem pełnoprzepływowym (25 h tylko z sitem) – SB 480F.",
            "bulletinsPage": "https://www.lycoming.com/support/publications",
            "tasks": [
                task(f"{id_}_100h", L("Przegląd silnika 100 h / 12 mies.", "Engine inspection 100 h / 12 months"), 100, 12, insp, LYC_SRC),
                task(f"{id_}_50h", L("Przegląd 50 h / 4 mies. – olej i filtr", "50 h / 4 months inspection – oil and filter"), 50, 4,
                     [it("Wymiana oleju i filtra, filtr rozciąć i sprawdzić", "Oil and filter change, cut filter open"), it("Czyszczenie sita ssącego oleju", "Clean oil suction screen")],
                     "Lycoming SB 480F"),
                task(f"{id_}_tbo", L("Remont główny (TBO)", "Overhaul (TBO)"), tbo, 144, [], "Lycoming SI 1009BF"),
            ]}

def tcm(id_, name, full, tbo, tbo_note, fuel, injected=False, turbo=False):
    insp = TCM_INSP[:4] + ([TURBO] if turbo else []) + TCM_INSP[4:] + [it("Iskrowniki – przegląd 500 h", "Magnetos – 500 h inspection", 500)]
    if injected:
        insp.append(it("Czyszczenie dysz wtryskowych (co 300 h i przy przeglądzie rocznym)", "Fuel nozzle cleaning (every 300 h and at annual)", 300, False))
    tasks = [
        task(f"{id_}_100h", L("Przegląd silnika 100 h / 12 mies.", "Engine inspection 100 h / 12 months"), 100, 12, insp, TCM_SRC),
        task(f"{id_}_50h", L("Przegląd 50 h / 6 mies. – olej i filtr", "50 h / 6 months inspection – oil and filter"), 50, 6,
             [it("Wymiana oleju i filtra, filtr rozciąć i sprawdzić", "Oil and filter change, cut filter open")], "Continental X30634A"),
        task(f"{id_}_mag48", L("Iskrowniki TCM – remont / wymiana co 4 lata", "TCM magnetos – overhaul / replace every 4 years"), None, 48, [], "Continental X30634A"),
    ]
    tasks.append(task(f"{id_}_tbo", L("Remont główny (TBO)", "Overhaul (TBO)"), tbo, 144, [], "Continental SIL98-9C"))
    return {"id": id_, "name": name, "fullName": full, "manufacturer": "Continental", "bulletinGroups": ["CONTINENTAL"],
            "tboHours": tbo, "tboYears": 12, "fuel": fuel,
            "notes": f"TBO {tbo} h / 12 lat wg Continental SIL98-9C ({tbo_note}). +200 h przy średnio ≥ 40 h/mies. Olej co 50 h lub 6 mies.",
            "bulletinsPage": "https://continental.aero/technical-publications/", "tasks": tasks}

GENERIC = [it("Pomiar sprężania", "Cylinder compression check"), LYC_INSP[1], LYC_INSP[2], it("Olej, filtr / sita", "Oil, filter / screens"),
           LYC_INSP[4], LYC_INSP[6], LYC_INSP[7], LYC_INSP[8], LYC_INSP[-1]]

ENGINES = [
    lyc("lycoming_o235", "Lycoming O-235", "Lycoming O-235 (-C, -E, -H, -K, -L, -M, -N, -P; -F, -G, -J)", 2400, "O-235-F/-G/-J: 2000 h", "AVGAS 100LL (min. gatunek wg wersji – SI 1070)"),
    lyc("lycoming_o320", "Lycoming O-320", "Lycoming O-320 (-A, -B, -C, -D, -E, -H)", 2000, "nowe/fabrycznie odnowione 2600 h; O-320-H 2400 h", "AVGAS 100LL (O-320-A/-E min. 80/87)"),
    lyc("lycoming_o360", "Lycoming O-360", "Lycoming O-360 (-A, -B, -C, -D, -F, -G, -J; -E)", 2000, "nowe/fabrycznie odnowione 2600 h", "AVGAS 100LL"),
    lyc("lycoming_io360", "Lycoming IO-360", "Lycoming IO-360 (180 / 200 KM)", 2000, "nowe/fabrycznie odnowione 2600 h", "AVGAS 100LL"),
    lyc("lycoming_io540", "Lycoming IO-540 / AEIO-540", "Lycoming IO-540 (-C, -D, -K, -L, -M, -N, -T, -V, -W…), AEIO-540", 2000, "nowe/fabrycznie odnowione 2600 h", "AVGAS 100LL"),
    lyc("lycoming_tio540", "Lycoming TIO-540", "Lycoming TIO-540 (turbo)", 2000, "TIO-540-A/-F/-J/-N/-R/-S/-U: 1800 h", "AVGAS 100LL", turbo=True),
    tcm("continental_o200", "Continental O-200", "Continental O-200-A, -B, -D", 2000, "O-200-A/B S/N < 1006000: 1800 h", "AVGAS 100LL"),
    tcm("continental_o300", "Continental O-300", "Continental O-300-A…D", 1800, "", "AVGAS 100LL"),
    tcm("continental_o470", "Continental O-470", "Continental O-470 (-K, -L, -M, -R, -S, -U…)", 1500, "S/N ≥ 1006000: 1700 h; O-470-U wg spec. 2000/2200 h", "AVGAS 100LL"),
    tcm("continental_io520", "Continental IO-520", "Continental IO-520 (-A…-P)", 1700, "część wersji S/N ≥ 1006000: 1900 h; L/IO-520-P 2000/2200 h", "AVGAS 100LL", injected=True),
    tcm("continental_io550", "Continental IO-550", "Continental IO-550 (-A…-R), IOF-550-N", 2000, "IO-550-G/N/P/R; S/N ≥ 1006000 2200 h; IO-550-A…L 1700/1900 h", "AVGAS 100LL", injected=True),
    tcm("continental_tsio520", "Continental TSIO-520", "Continental TSIO-520 (turbo)", 1400, "zależnie od wersji 1400–2200 h – sprawdź SIL98-9", "AVGAS 100LL", injected=True, turbo=True),
    tcm("continental_tsio550", "Continental TSIO-550", "Continental TSIO-550 (-B, -C, -E, -G, -K, -N)", 2000, "TSIO-550-C/G/K/N; S/N ≥ 1006000 2200 h; -B/-E 1600/1800 h", "AVGAS 100LL", injected=True, turbo=True),
    {"id": "ivchenko_ai14r", "name": "AI-14RA", "fullName": "AI-14R / AI-14RA (WSK PZL-Kalisz), 9-cyl. radial ~260 KM", "manufacturer": "WSK PZL-Kalisz",
     "bulletinGroups": ["AI14"], "tboHours": None, "tboYears": None, "fuel": "wg instrukcji silnika / AFM",
     "notes": "Resurs i okresy obsługi wg dokumentacji WSK PZL-Kalisz i programu obsługi (AMP) – wpisz je ręcznie.", "bulletinsPage": None,
     "tasks": [task("ivchenko_ai14r_100h", L("Przegląd silnika wg programu obsługi", "Engine inspection per maintenance programme"), 100, 12, GENERIC,
                    "Program obsługi (AMP) / dokumentacja silnika – sprawdź / verify", False)]},
    {"id": "pzl_franklin_4a235", "name": "PZL-Franklin 4A-235", "fullName": "Franklin 4A-235-B3, -B31, -B4", "manufacturer": "Franklin (PZL-Rzeszów)",
     "bulletinGroups": ["PZL_FRANKLIN"], "tboHours": None, "tboYears": None, "fuel": "AVGAS min. 100/130 (100LL) – EASA TCDS E.087",
     "notes": "TBO i okresy wymiany oleju wg instrukcji 26.0.061 (EN) / 26.0.055 (PL) – brak w TCDS, wpisz ręcznie.", "bulletinsPage": None,
     "tasks": [task("pzl_franklin_4a235_100h", L("Przegląd silnika 100 h / 12 mies.", "Engine inspection 100 h / 12 months"), 100, 12, GENERIC,
                    "Instrukcja 26.0.061 – sprawdź / verify", False)]},
]

def main():
    ep = os.path.join(ROOT, "data/engines.json")
    engines = json.load(open(ep, encoding="utf-8"))
    ids = {e["id"] for e in ENGINES}
    other = [e for e in engines if e["id"] == "other"]
    engines = [e for e in engines if e["id"] not in ids and e["id"] != "other"] + ENGINES + other
    json.dump(engines, open(ep, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    tp = os.path.join(ROOT, "data/types.json")
    types = json.load(open(tp, encoding="utf-8"))
    src = json.load(open(os.path.join(ROOT, "tools/research/ga_types.json"), encoding="utf-8"))
    new_ids = {s["id"] for s in src}
    types = [t for t in types if t["id"] not in new_ids]
    for s in src:
        t = bd.gtype(s["id"], s["name"], s["manufacturer"], "EASA", s["tcds"], "kt", s.get("speeds") or {}, s["source"], bool(s.get("verified")),
                     [s["group"]], s["mtow"], s["fuel"], s["engine"], note=s.get("note"), unverified_keys=tuple(s.get("unverifiedKeys") or ()))
        t["bulletinGroups"] = [s["group"]]                         # engine groups come from the engine catalogue
        t["tasks"] = [x for x in t["tasks"] if x["id"] in ("airframe_100h", "arc")]
        if s.get("rescue"):
            t["tasks"].append({"id": "caps_10y", "title": L("CAPS – wymiana spadochronu i rakiety (10 lat)", "CAPS – parachute repack and rocket (10 years)"),
                               "category": "airframe", "intervalHours": None, "intervalMonths": 120, "oneTimeAtHours": None, "toleranceHours": None,
                               "mandatory": True, "source": "Cirrus AMM ch. 95 – sprawdź / verify", "verified": False,
                               "note": L("Przecinaki linek co 6 lat.", "Line cutters every 6 years."),
                               "items": [{"title": L("Przecinaki linek – co 6 lat", "Line cutters – every 6 years"), "everyHours": None, "verified": False}]})
        prop = {"fixed": ("Śmigło stałe", "Fixed-pitch propeller"), "inflight": ("Śmigło przestawialne w locie (stałe obroty)", "Constant-speed propeller")}[s.get("propeller", "fixed")]
        pi = bd.item(bd.L("Śmigło", "Propeller", "Vrtuľa", "Hélice"), prop[0], s["source"])
        pi["value"] = {"pl": prop[0], "en": prop[1], "sk": prop[1], "fr": prop[1]}
        t["manual"][0]["items"].append(pi)
        if s.get("sourceUrl"):
            t["manual"][0]["items"].append(bd.item(bd.L("Źródło (link)", "Source (link)", "Zdroj (odkaz)", "Source (lien)"), s["sourceUrl"], s["source"]))
        t["kind"] = "airplane"
        t["engine"] = s["engineId"]
        t["family"] = s["manufacturer"].lower()
        types.append(t)
    types.sort(key=lambda t: (t.get("manufacturer") != "TOMARK", t.get("manufacturer", ""), t["name"]))
    json.dump(types, open(tp, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"engines {len(engines)}, types {len(types)} (+{len(src)} GA)")

if __name__ == "__main__":
    main()
