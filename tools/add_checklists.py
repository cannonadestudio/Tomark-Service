#!/usr/bin/env python3
"""
Checklists for inspections (Oct 2026):
  * engine items (data/engines.json): measurement fields where the work gives a value to write down
    (compression per cylinder, spark-plug connector pull-off force, idle rpm, gearbox friction torque…)
  * airframe items (data/types.json): where the type has no item list yet, a general airframe inspection scope
    (based on the usual 100 h / annual scope – FAR 43 App. D, ASTM F2483) – marked NOT verified, to be checked
    against the manufacturer's maintenance manual and corrected in the panel.
Re-runnable: items already present are left as they are.
Run: python3 tools/add_checklists.py
"""
import json, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(p):
    return json.load(open(os.path.join(ROOT, p), encoding="utf-8"))


def dump(p, d):
    with open(os.path.join(ROOT, p), "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=1)
        f.write("\n")


def L(pl, en):
    return {"pl": pl, "en": en}


def cyl(n):
    return [L(f"Cyl {i}", f"Cyl {i}") for i in range(1, n + 1)]


def plugs(n):
    return [L(f"Fajka {i}", f"Cap {i}") for i in range(1, n + 1)]


# (regex on the Polish title, fields, unit)
def engine_fields(eid):
    n = 2 if eid in ("rotax_582", "hirth_f23") else (6 if any(k in eid for k in ("o470", "io520", "io550", "tsio520", "tsio550", "io540", "tio540", "jabiru_5100")) else 4)
    if eid in ("ivchenko_ai14r",):
        n = 9
    return [
        (r"sprężani|kompresj", cyl(n), "/80" if eid.startswith(("lycoming", "continental", "pzl")) or "rotax" in eid else ""),
        (r"ściągania fajek", plugs(n), "N"),
        (r"biegu jałowego|obrotów jałowych", [L("Obroty", "Idle")], "rpm"),
        (r"momentu tarcia", [L("Moment", "Torque")], "Nm"),
        (r"Próba silnika", [L("Obroty max (statycznie)", "Static max rpm")], "rpm"),
        (r"Wymiana oleju", [L("Olej / ilość", "Oil / quantity")], ""),
    ]


AIRFRAME = [
    ("Dokumentacja: książka płatowca i silnika, świadectwa, wykonane AD / biuletyny, masa i wyważenie",
     "Documents: airframe and engine logbooks, certificates, ADs / bulletins done, weight and balance", None, [], ""),
    ("Kabina: pasy bezpieczeństwa, fotele i szyny foteli, owiewka / szyby, zamki i zawiasy owiewki",
     "Cabin: seat belts, seats and rails, canopy / windows, canopy locks and hinges", None, [], ""),
    ("Układ sterowania: swoboda ruchów, wychylenia, luzy, linki i popychacze, zabezpieczenia połączeń",
     "Flight controls: free movement, deflections, play, cables and push-rods, safetying", None, [], ""),
    ("Skrzydła: pokrycie, mocowanie skrzydeł i sworznie, lotki, klapy i ich napęd, zawiasy",
     "Wings: skin, wing attachments and pins, ailerons, flaps and flap drive, hinges", None, [], ""),
    ("Usterzenie: statecznik, ster wysokości, ster kierunku, trymer, zawiasy i mocowania",
     "Empennage: stabilizer, elevator, rudder, trim, hinges and attachments", None, [], ""),
    ("Kadłub: pokrycie, pęknięcia, korozja, otwory odwadniające, mocowania",
     "Fuselage: skin, cracks, corrosion, drain holes, attachments", None, [], ""),
    ("Podwozie: opony (zużycie, ciśnienie), koła i łożyska, golenie, amortyzacja",
     "Landing gear: tyres (wear, pressure), wheels and bearings, legs, shock absorption", None,
     [L("Przód", "Nose"), L("Lewe", "Left"), L("Prawe", "Right")], "bar"),
    ("Hamulce: płyn hamulcowy, przewody, klocki i tarcze, hamulec postojowy",
     "Brakes: brake fluid, lines, pads and discs, parking brake", None, [], ""),
    ("Instalacja paliwowa: zbiorniki, przewody, zawór paliwa, odstojniki, filtr paliwa, wskaźniki",
     "Fuel system: tanks, lines, fuel valve, drains, fuel filter, gauges", None, [], ""),
    ("Instalacja elektryczna: akumulator, przewody i złącza, bezpieczniki, oświetlenie",
     "Electrical: battery, wiring and connectors, fuses / breakers, lights", None, [], ""),
    ("Przyrządy i awionika: instalacja pitot-statyczna (szczelność), kompas, radio, transponder",
     "Instruments and avionics: pitot-static system (leak check), compass, radio, transponder", None, [], ""),
    ("Śmigło: łopaty, piasta, kołpak, momenty dokręcenia śrub",
     "Propeller: blades, hub, spinner, bolt torque", None, [], ""),
    ("Smarowanie wg instrukcji obsługi (zawiasy, łożyska, przeguby)",
     "Lubrication per the maintenance manual (hinges, bearings, rod ends)", None, [], ""),
    ("Próba po przeglądzie i wpis do książki płatowca",
     "Check after the inspection and logbook entry", None, [], ""),
]
AIRFRAME_50 = [0, 2, 6, 7, 8, 11, 13]   # short scope for a 50 h check
SRC = "Zakres ogólny (FAR 43 App. D / ASTM F2483) – sprawdź z instrukcją obsługi producenta"


def airframe_items(subset=None):
    out = []
    for i, (pl, en, every, fields, unit) in enumerate(AIRFRAME):
        if subset is not None and i not in subset:
            continue
        it = {"title": L(pl, en), "verified": False}
        if every:
            it["everyHours"] = every
        if fields:
            it["fields"] = fields
        if unit:
            it["unit"] = unit
        out.append(it)
    return out


def main():
    engines = load("data/engines.json")
    nf = 0
    for e in engines:
        rules = engine_fields(e["id"])
        for t in e["tasks"]:
            for it in t.get("items", []):
                if it.get("fields"):
                    continue
                for rx, fields, unit in rules:
                    if re.search(rx, it["title"]["pl"], re.I):
                        it["fields"] = fields
                        if unit:
                            it["unit"] = unit
                        nf += 1
                        break
    dump("data/engines.json", engines)

    types = load("data/types.json")
    na = 0
    for ty in types:
        if ty.get("kind", "airplane") != "airplane":
            continue
        for t in ty["tasks"]:
            if t.get("items"):
                continue
            tid = t["id"]
            if tid in ("ul_100h", "airframe_100h", "insp_100h", "insp_annual", "insp_25h"):
                t["items"] = airframe_items()
            elif tid == "insp_50h":
                t["items"] = airframe_items(AIRFRAME_50)
            else:
                continue
            t["itemsSource"] = SRC
            na += 1
    dump("data/types.json", types)
    print(f"engine items with fields: {nf}; airframe tasks with a checklist: {na}")


if __name__ == "__main__":
    main()
