#!/usr/bin/env python3
"""Generates app/src/main/assets/types.json, app/src/main/assets/bulletins.json
and data/bulletins.json (the online feed the app downloads).

Edit the definitions below, run `python3 tools/build_data.py`, commit.
Every value carries a source. verified=False means: taken from marketing
material / general Rotax practice and MUST be checked against the AFM/AMM.
"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def L(pl, en, sk=None, fr=None):
    return {"pl": pl, "en": en, "sk": sk or en, "fr": fr or en}

TCDS = "EASA TCDS A.606 Iss.4 (18-MAR-2025)"
WEB_SD4 = "tomarkaero.com – Viper SD4 LSA-UL (specyfikacja)"
WEB_GT9 = "tomarkaero.com – Skyper GT9 (specyfikacja)"

def spd(key, label, value, unit, source, verified, zone="normal"):
    return {"key": key, "label": label, "value": value, "unit": unit,
            "source": source, "verified": verified, "zone": zone}

def approach(vs0, unit):
    return spd("VAPP", L("Podejście (1,3×VS0)", "Approach (1.3×VS0)", "Priblíženie (1,3×VS0)", "Approche (1,3×VS0)"),
               round(vs0 * 1.3), unit, "obliczone / computed 1.3×VS0", False, "approach")

LBL = {
    "VS0": L("VS0 przeciągnięcie, klapy do lądowania", "VS0 stall, landing flaps", "VS0 pád, pristávacie klapky", "VS0 décrochage, volets atterrissage"),
    "VS1": L("VS1 przeciągnięcie, bez klap", "VS1 stall, clean", "VS1 pád, bez kláp", "VS1 décrochage, lisse"),
    "VFE": L("VFE maks. z klapami", "VFE max flaps extended", "VFE max. s klapkami", "VFE max volets sortis"),
    "VA":  L("VA prędkość manewrowa", "VA manoeuvring speed", "VA obratová rýchlosť", "VA vitesse de manœuvre"),
    "VC":  L("VC prędkość przelotowa", "VC cruise speed", "VC cestovná rýchlosť", "VC vitesse de croisière"),
    "VNE": L("VNE nieprzekraczalna", "VNE never exceed", "VNE nikdy neprekročiteľná", "VNE à ne jamais dépasser"),
    "VR":  L("VR rotacja / start", "VR rotation / take-off", "VR rotácia / vzlet", "VR rotation / décollage"),
    "VX":  L("VX najlepszy kąt wznoszenia", "VX best angle of climb", "VX najlepší uhol stúpania", "VX meilleur angle de montée"),
    "VY":  L("VY najlepsza prędkość wznoszenia", "VY best rate of climb", "VY najlepšia stúpavosť", "VY meilleur taux de montée"),
    "VBG": L("Najlepszy szybowanie", "Best glide", "Najlepšie kĺzanie", "Finesse max"),
}

def unknown(key):
    return spd(key, LBL[key], None, "kt", "AFM – uzupełnij / fill in", False,
               "approach" if key in ("VR",) else "normal")

# ---------------------------------------------------------------- manual
def item(label, value, source):
    if isinstance(value, str):
        value = L(value, value)
    return {"label": label, "value": value, "source": source}

def sec(id_, title, items):
    return {"id": id_, "title": title, "items": items}

T_MASS = L("Masy", "Masses", "Hmotnosti", "Masses")
T_CG = L("Środek ciężkości", "Centre of gravity", "Ťažisko", "Centrage")
T_LOAD = L("Obciążenia i klapy", "Load factors & flaps", "Násobky a klapky", "Facteurs de charge et volets")
T_PWR = L("Zespół napędowy", "Power plant", "Pohonná jednotka", "Groupe motopropulseur")
T_FLU = L("Płyny i paliwo", "Fluids & fuel", "Kvapaliny a palivo", "Fluides et carburant")
T_OPS = L("Ograniczenia eksploatacyjne", "Operating limitations", "Prevádzkové obmedzenia", "Limitations d'utilisation")
T_PERF = L("Osiągi (orientacyjne)", "Performance (indicative)", "Výkony (orientačné)", "Performances (indicatives)")
T_DOCS = L("Dokumentacja obowiązująca", "Applicable documents", "Platná dokumentácia", "Documents applicables")

def rtc_manual(nvfr):
    flaps = "0° / 15° / 30° / 35° (III: AFM)" if nvfr else "0° / 15° / 30° / (40° III: AFM)"
    prop = ("DUC Hélices FLASH-R H-FSH_3-D-R_I, 3-blade, Ø 1.73 m, 23.5°" if nvfr
            else "Neuform CR3-65-(IP)-47-101.6, 3-blade, Ø 1.65 m")
    eng = "Rotax 912 S2" if nvfr else "Rotax 912 ULS2 / 912 S2"
    afm = "TOM-TC-15-AFM" if nvfr else "TOM-TC-01-AFM"
    amm = "TOM-TC-15-AMM" if nvfr else "TOM-TC-01-AMM"
    return [
        sec("mass", T_MASS, [
            item(L("MTOW", "MTOW"), "600 kg", TCDS),
            item(L("Maks. masa pustego", "Max empty mass", "Max. prázdna hmotnosť", "Masse à vide max"), "405 kg", TCDS),
            item(L("Bagaż (za fotelami)", "Baggage (behind seats)", "Batožina (za sedadlami)", "Bagages (derrière sièges)"), "15 kg (2 × 7,5 kg)", TCDS),
            item(L("Załoga / pasażerowie", "Crew / passengers", "Posádka / cestujúci", "Équipage / passagers"), L("1 pilot (lewy fotel) + 1", "1 pilot (left seat) + 1", "1 pilot (ľavé sedadlo) + 1", "1 pilote (siège gauche) + 1"), TCDS),
        ]),
        sec("cg", T_CG, [
            item(L("Przedni limit", "Forward limit", "Predná hranica", "Limite avant"), "310 mm (24 % MAC)", TCDS),
            item(L("Tylny limit", "Aft limit", "Zadná hranica", "Limite arrière"), "413 mm (32 % MAC)", TCDS),
            item(L("Baza", "Datum", "Základňa", "Référence"), L("krawędź natarcia skrzydła", "wing leading edge", "nábežná hrana krídla", "bord d'attaque de l'aile"), TCDS),
        ]),
        sec("load", T_LOAD, [
            item(L("Bez klap", "Clean", "Bez kláp", "Lisse"), "+4 g / −2 g", TCDS),
            item(L("Z klapami", "Flaps extended", "S klapkami", "Volets sortis"), "+2 g / 0 g", TCDS),
            item(L("Położenia klap", "Flap positions", "Polohy kláp", "Positions volets"), flaps, TCDS),
        ]),
        sec("pwr", T_PWR, [
            item(L("Silnik", "Engine", "Motor", "Moteur"), eng, TCDS),
            item(L("Śmigło", "Propeller", "Vrtuľa", "Hélice"), prop, TCDS),
        ]),
        sec("fluids", T_FLU, [
            item(L("Paliwo użyteczne", "Usable fuel", "Využiteľné palivo", "Carburant utilisable"), "90 L", TCDS),
            item(L("Olej", "Oil", "Olej", "Huile"), "3 L", TCDS),
            item(L("Płyn chłodzący", "Coolant", "Chladiaca kvapalina", "Liquide de refroidissement"), "≈ 1,5 L", TCDS),
            item(L("Gatunki paliwa/oleju", "Fuel/oil grades", "Druhy paliva/oleja", "Types carburant/huile"), L("wg AFM", "see AFM", "podľa AFM", "voir AFM"), TCDS),
        ]),
        sec("ops", T_OPS, [
            item(L("Pułap maks.", "Max altitude", "Max. výška", "Altitude max"), "15 500 ft", TCDS),
            item(L("Rodzaj lotów", "Approved operations", "Schválená prevádzka", "Opérations approuvées"), "Day VFR + Night VFR" if nvfr else "Day VFR", TCDS),
            item(L("Opony", "Tyres", "Pneumatiky", "Pneus"), "4.00-6 Kaspar", TCDS),
            item(L("Uwaga – korkociąg", "Note – spin recovery", "Pozn. – vývrtka", "Note – vrille"),
                 L("Przy wyprowadzaniu z korkociągu mogą zostać przekroczone VFE i współczynniki – samolot to wytrzymuje, wg AFM",
                   "During spin recovery VFE and load factors may be exceeded – proven, see AFM",
                   "Pri vybratí vývrtky môžu byť prekročené VFE a násobky – lietadlo to vydrží, podľa AFM",
                   "Lors de la sortie de vrille, VFE et facteurs de charge peuvent être dépassés – démontré, voir AFM"), TCDS),
        ]),
        sec("docs", T_DOCS, [
            item(L("AFM", "AFM"), afm, TCDS),
            item(L("AMM", "AMM"), amm + (" Issue D rev.1 (02/2026)" if nvfr else " Issue H (04/2026)"), "tomarkaero.com – Technical support"),
            item(L("Lista ważnych publikacji", "Valid publications list", "Zoznam platných publikácií", "Liste des publications"), "TOM-TC-606-VTP Rev H (04/2026)", "tomarkaero.com – Technical support"),
        ]),
    ]

def ul_viper_manual():
    return [
        sec("mass", T_MASS, [
            item(L("MTOW (UL)", "MTOW (UL)"), "472,5 kg", "Wikipedia – Tomark Viper SD4"),
            item(L("MTOW (LSA / SD4-600)", "MTOW (LSA / SD4-600)"), "600 kg", WEB_SD4),
        ]),
        sec("pwr", T_PWR, [
            item(L("Silnik", "Engine", "Motor", "Moteur"), "Rotax 912 UL 80 KM / 912 ULS 100 KM", "Wikipedia – Tomark Viper SD4"),
        ]),
        sec("perf", T_PERF, [
            item(L("Prędkość przelotowa", "Cruise speed", "Cestovná rýchlosť", "Vitesse de croisière"), "200 km/h", WEB_SD4),
            item(L("Wznoszenie", "Climb rate", "Stúpavosť", "Taux de montée"), "5–6 m/s", "airport-technology.com / Wikipedia"),
            item(L("Doskonałość", "Glide", "Kĺzavosť", "Finesse"), L("≈ 10 km z 1 km", "≈ 10 km from 1 km", "≈ 10 km z 1 km", "≈ 10 km depuis 1 km"), WEB_SD4),
        ]),
        sec("docs", T_DOCS, [
            item(L("Instrukcja", "Manual", "Príručka", "Manuel"), L("AFM/POH dostarczony z samolotem – załącz PDF w aplikacji", "AFM/POH supplied with the aircraft – attach the PDF in the app", "AFM/POH dodaný s lietadlom – priložte PDF v aplikácii", "AFM/POH fourni avec l'avion – joignez le PDF dans l'app"), "—"),
        ]),
    ]

def gt9_manual():
    return [
        sec("mass", T_MASS, [
            item(L("MTOW (UL)", "MTOW (UL)"), "472,5 kg", "Altair.com.pl / Wikipedia"),
            item(L("MTOW (LSA / GT9-600)", "MTOW (LSA / GT9-600)"), "600 kg", WEB_GT9),
        ]),
        sec("pwr", T_PWR, [
            item(L("Silnik", "Engine", "Motor", "Moteur"), "Rotax 912 ULS 100 KM", WEB_GT9),
            item(L("Śmigło", "Propeller", "Vrtuľa", "Hélice"), L("3-łopatowe nastawialne DUC lub Neuform", "3-blade ground-adjustable DUC or Neuform", "3-listá nastaviteľná DUC alebo Neuform", "tripale réglable au sol DUC ou Neuform"), WEB_GT9),
            item(L("Klapy", "Flaps", "Klapky", "Volets"), L("szczelinowe, elektryczne, 3 położenia", "slotted, electric, 3 positions", "štrbinové, elektrické, 3 polohy", "à fente, électriques, 3 positions"), WEB_GT9),
        ]),
        sec("fluids", T_FLU, [
            item(L("Zbiorniki paliwa", "Fuel tanks", "Palivové nádrže", "Réservoirs"), "88 L", WEB_GT9),
            item(L("Zużycie", "Consumption", "Spotreba", "Consommation"), "≈ 17 L/h", WEB_GT9),
        ]),
        sec("perf", T_PERF, [
            item(L("Prędkość przelotowa", "Cruise speed", "Cestovná rýchlosť", "Vitesse de croisière"), "205 km/h (111 kt)", WEB_GT9),
            item(L("Wznoszenie", "Climb rate", "Stúpavosť", "Taux de montée"), "5,5 m/s", WEB_GT9),
            item(L("Rozbieg startowy", "Take-off distance", "Vzletová dráha", "Distance décollage"), "175 m", WEB_GT9),
            item(L("Dobieg", "Landing distance", "Pristávacia dráha", "Distance atterrissage"), "150 m", WEB_GT9),
            item(L("Długotrwałość / zasięg", "Endurance / range", "Vytrvalosť / dolet", "Autonomie / distance"), "4,5 h / 1000 km", WEB_GT9),
        ]),
        sec("docs", T_DOCS, [
            item(L("Instrukcja", "Manual", "Príručka", "Manuel"), L("AFM/POH dostarczony z samolotem – załącz PDF w aplikacji", "AFM/POH supplied with the aircraft – attach the PDF in the app", "AFM/POH dodaný s lietadlom – priložte PDF v aplikácii", "AFM/POH fourni avec l'avion – joignez le PDF dans l'app"), "—"),
        ]),
    ]

# ---------------------------------------------------------------- maintenance
def task(id_, title, cat, mandatory, source, verified, hours=None, months=None,
         once=None, tol=None, note=None):
    return {"id": id_, "title": title, "category": cat, "intervalHours": hours,
            "intervalMonths": months, "oneTimeAtHours": once, "toleranceHours": tol,
            "mandatory": mandatory, "source": source, "verified": verified, "note": note}

SB0323 = "SB_SD4-03-2023"
ROTAX = "Rotax MML-912 – sprawdź aktualną rewizję / verify"

def rotax_tasks():
    return [
        task("rotax_oil", L("Wymiana oleju i filtra (Rotax)", "Oil & filter change (Rotax)", "Výmena oleja a filtra (Rotax)", "Vidange huile et filtre (Rotax)"),
             "engine", True, ROTAX, False, hours=100, months=12,
             note=L("Przy częstym użyciu AVGAS Rotax zaleca krótszy interwał.", "With frequent AVGAS use Rotax recommends a shorter interval.")),
        task("rotax_plugs", L("Świece zapłonowe", "Spark plugs", "Zapaľovacie sviečky", "Bougies"), "engine", True, ROTAX, False, hours=200),
        task("rotax_gearbox", L("Przegląd reduktora", "Gearbox inspection", "Prehliadka reduktora", "Inspection réducteur"), "engine", True, ROTAX, False, hours=1000),
        task("rotax_rubber", L("Wymiana elementów gumowych silnika", "Engine rubber parts replacement", "Výmena gumových dielov motora", "Remplacement pièces caoutchouc moteur"),
             "engine", True, ROTAX, False, months=60),
        task("rotax_tbo", L("Remont główny silnika (TBO)", "Engine overhaul (TBO)", "Generálna oprava motora (TBO)", "Révision générale moteur (TBO)"),
             "engine", True, ROTAX, False, hours=2000, months=180,
             note=L("TBO zależy od numeru seryjnego silnika i biuletynów Rotax.", "TBO depends on engine S/N and Rotax bulletins.")),
        task("prop", L("Przegląd śmigła", "Propeller inspection", "Prehliadka vrtule", "Inspection hélice"), "propeller", True,
             "Instrukcja producenta śmigła / propeller manual – ustaw interwał", False),
        task("brs_repack", L("Spadochron ratowniczy – przepakowanie", "Rescue parachute – repack", "Záchranný padák – prebalenie", "Parachute – repliage"),
             "rescue", True, "Instrukcja producenta BRS / Galaxy / Stratos – ustaw interwał", False),
        task("brs_rocket", L("Spadochron ratowniczy – rakieta / żywotność", "Rescue parachute – rocket / service life", "Záchranný padák – raketa / životnosť", "Parachute – fusée / durée de vie"),
             "rescue", True, "Instrukcja producenta BRS – ustaw interwał", False),
    ]

def easa_tasks(nvfr):
    amm = "TOM-TC-15-AMM" if nvfr else "TOM-TC-01-AMM"
    return [
        task("insp_25h", L("Przegląd po 25 h (nowy samolot / nowy silnik)", "25 h inspection (new aircraft / engine)", "Prehliadka po 25 h (nové lietadlo / motor)", "Inspection 25 h (avion / moteur neuf)"),
             "airframe", True, SB0323 + " / " + amm, True, once=25, tol=1.25),
        task("insp_50h", L("Przegląd 50 h (zalecany)", "50 h inspection (recommended)", "Prehliadka 50 h (odporúčaná)", "Inspection 50 h (recommandée)"),
             "airframe", False, SB0323 + " / " + amm, True, hours=50, tol=2.5),
        task("insp_100h", L("Przegląd 100 h (obowiązkowy)", "100 h inspection (mandatory)", "Prehliadka 100 h (povinná)", "Inspection 100 h (obligatoire)"),
             "airframe", True, SB0323 + " / " + amm, True, hours=100, tol=5),
        task("insp_annual", L("Przegląd roczny", "Annual inspection", "Ročná prehliadka", "Visite annuelle"),
             "airframe", True, amm + " – sprawdź / verify", False, months=12),
        task("insp_2000h", L("Duży przegląd płatowca 2000 h", "2000 h major airframe inspection", "Veľká prehliadka draku 2000 h", "Grande visite cellule 2000 h"),
             "airframe", True, "SB-SD4-01-2024 / " + SB0323 + " / AMM 04-30-10", True, hours=2000, tol=100),
        task("arc", L("Przegląd zdatności (ARC)", "Airworthiness review (ARC)", "Kontrola letovej spôsobilosti (ARC)", "Examen de navigabilité (ARC)"),
             "regulatory", True, "Part-ML, ML.A.901 (Rozp. UE 1321/2014)", True, months=12),
    ] + rotax_tasks()

def ul_tasks(family):
    src100 = "SB_LŠZ_SD4-03-2017 (100 h / 1 rok)" if family == "viper" else "Instrukcja obsługi technicznej GT9 – sprawdź / verify"
    return [
        task("ul_100h", L("Przegląd okresowy 100 h / 12 mies.", "Periodic inspection 100 h / 12 months", "Periodická údržba 100 h / 12 mes.", "Visite périodique 100 h / 12 mois"),
             "airframe", True, src100, family == "viper", hours=100, months=12),
        task("ul_national", L("Przegląd / dokument zdatności wg przepisów krajowych UL", "National UL airworthiness renewal", "Obnova spôsobilosti podľa národných predpisov UL", "Renouvellement navigabilité ULM national"),
             "regulatory", True, "Przepisy krajowe (np. ULC, LAA) – ustaw / set", False, months=12),
    ] + rotax_tasks()

# ---------------------------------------------------------------- types
types = [
    {
        "id": "sd4_rtc", "name": "Viper SD-4 RTC", "family": "viper", "category": "EASA",
        "tcds": "EASA.A.606", "bulletinGroups": ["SD4_RTC"],
        "speeds": [
            spd("VS0", LBL["VS0"], 43, "kt", TCDS, True, "white"),
            spd("VS1", LBL["VS1"], 49, "kt", TCDS, True, "green"),
            approach(43, "kt"),
            spd("VFE", LBL["VFE"], 79, "kt", TCDS, True, "white"),
            spd("VA", LBL["VA"], 88, "kt", TCDS, True, "normal"),
            spd("VC", LBL["VC"], 102, "kt", TCDS, True, "normal"),
            spd("VNE", LBL["VNE"], 126, "kt", TCDS, True, "red"),
            unknown("VR"), unknown("VX"), unknown("VY"), unknown("VBG"),
        ],
        "manual": rtc_manual(False), "tasks": easa_tasks(False),
    },
    {
        "id": "sd4_nvfr", "name": "Viper SD-4 Night-VFR", "family": "viper", "category": "EASA",
        "tcds": "EASA.A.606", "bulletinGroups": ["SD4_NVFR"],
        "speeds": [
            spd("VS0", LBL["VS0"], 43, "kt", TCDS, True, "white"),
            spd("VS1", LBL["VS1"], 49, "kt", TCDS, True, "green"),
            approach(43, "kt"),
            spd("VFE", LBL["VFE"], 79, "kt", TCDS, True, "white"),
            spd("VA", LBL["VA"], 88, "kt", TCDS, True, "normal"),
            spd("VC", LBL["VC"], 102, "kt", TCDS, True, "normal"),
            spd("VNE", LBL["VNE"], 126, "kt", TCDS, True, "red"),
            unknown("VR"), unknown("VX"), unknown("VY"), unknown("VBG"),
        ],
        "manual": rtc_manual(True), "tasks": easa_tasks(True),
    },
    {
        "id": "sd4_ul", "name": "Viper SD-4 UL / LSA", "family": "viper", "category": "UL",
        "tcds": None, "bulletinGroups": ["SD4_UL"],
        "speeds": [
            spd("VS0", LBL["VS0"], 82, "kmh", WEB_SD4, False, "white"),
            spd("VS1", LBL["VS1"], 93, "kmh", WEB_SD4, False, "green"),
            approach(82, "kmh"),
            spd("VC", LBL["VC"], 200, "kmh", WEB_SD4, False, "normal"),
            spd("VNE", LBL["VNE"], 240, "kmh", "airport-technology.com", False, "red"),
            unknown("VFE"), unknown("VA"), unknown("VR"), unknown("VX"), unknown("VY"), unknown("VBG"),
        ],
        "manual": ul_viper_manual(), "tasks": ul_tasks("viper"),
    },
    {
        "id": "gt9", "name": "Skyper GT9", "family": "skyper", "category": "UL",
        "tcds": None, "bulletinGroups": ["GT9"],
        "speeds": [
            spd("VS0", LBL["VS0"], 78, "kmh", WEB_GT9, False, "white"),
            spd("VS1", LBL["VS1"], 93, "kmh", WEB_GT9, False, "green"),
            approach(78, "kmh"),
            spd("VC", LBL["VC"], 205, "kmh", WEB_GT9, False, "normal"),
            spd("VNE", LBL["VNE"], 250, "kmh", "Wikipedia – Tomark Skyper GT9", False, "red"),
            unknown("VFE"), unknown("VA"), unknown("VR"), unknown("VX"), unknown("VY"), unknown("VBG"),
        ],
        "manual": gt9_manual(), "tasks": ul_tasks("skyper"),
    },
]

# ---------------------------------------------------------------- bulletins
TS = "https://www.tomarkaero.com/en/technical-support/"
RTC, NV, UL, GT = "SD4_RTC", "SD4_NVFR", "SD4_UL", "GT9"

def b(id_, date, title, groups, kind, mandatory=None, url=TS):
    return {"id": id_, "date": date, "title": title, "groups": groups, "kind": kind,
            "mandatory": mandatory, "url": url}

bulletins = [
    b("EASA-AD-2026-0170", "2026-09-03", "ATA 72 – Engine – Propeller Gearbox – Inspection (Rotax 912 ULS2/S2)", [RTC, NV], "AD", True,
      "https://ad.easa.europa.eu/blob/EASA_AD_2026_0170.pdf/AD_2026-0170_1"),
    b("ASB_SD4UL-01-2026", "2026-07-01", "Inspection of the propeller gearbox for ROTAX 912, 912 i (Series) Aircraft Engines", [UL], "ASB", True),
    b("ASB_SD4-04-2026", "2026-06-30", "Inspection of the propeller gearbox for ROTAX 912 ULS2 Aircraft Engines", [RTC], "ASB", True),
    b("ROTAX-ASB-2026-001", "2026-06-22", "Rotax ASB: Inspection of the propeller gearbox for 912 / 912 i / 914 (S/N listed) – also UL/ULS engines", [RTC, NV, UL, GT], "ASB", True,
      "https://www.flyrotax.com/services/technical-documentation.html"),
    b("TOM-TC-606-VTP-H", "2026-04-13", "List of Valid Technical Publications, Revision H", [RTC, NV], "DOC"),
    b("TOM-TC-01-AMM-H", "2026-04-08", "Aircraft Maintenance Manual Viper SD-4 RTC Issue H", [RTC], "DOC"),
    b("SB_SD4-02-2026", "2026-02-19", "Update of an error in the AMM Issue D, for Viper SD-4 Night-VFR", [NV], "SB"),
    b("TOM-TC-15-AMM-D1", "2026-02-19", "Viper SD-4 Night-VFR Aircraft Maintenance Manual, Issue D, revision 1", [NV], "DOC"),
    b("SB_SD4-04-2025", "2025-12-18", "Optional engine intake air filter GREEN FILTER EUROPE", [RTC, NV], "SB", False),
    b("SB_SD4-03-2025", "2025-12-18", "Optional Battery EXIDE", [RTC], "SB", False),
    b("SB_SD4UL-02-2025", "2025-11-03", "Replacement of fuel hoses", [UL], "SB"),
    b("SB_SD4-02-2025", "2025-10-02", "Replacement of fuel hoses", [RTC, NV], "SB"),
    b("SB_SD4-01-2025", "2025-01-10", "Inspection and/or replacement of expansion tank assembly for ROTAX 912 ULS engines", [RTC], "SB"),
    b("SB_SD4UL-01-2025", "2025-01-10", "Inspection and/or replacement of expansion tank assembly for ROTAX 912 ULS / 912 iS SPORT", [UL], "SB"),
    b("SB_GT9-01-2025", "2025-01-10", "Inspection and/or replacement of expansion tank assembly for ROTAX 912 ULS engines", [GT], "SB"),
    b("SB-SD4-01-2024", "2024-12-12", "2000-hour Major Airframe Inspection", [RTC, NV], "SB", True),
    b("SB-SD4-03-2023", "2023-09-15", "Specification of aircraft inspection tolerances", [RTC, NV], "SB", False,
      "https://www.tomarkaero.com/wp-content/uploads/technical_support/bulletins/2023/SB_SD4-03-2023_Specification%20of%20Aircraft%20Inspection%20Tolerances.pdf"),
    b("SB-SD4-04-2023", "2023-09-14", "Exchange of stator assembly / propeller shaft on Rotax 912ULS (Rotax SB-912-077UL, -078UL)", [RTC], "SB", True,
      "https://www.tomarkaero.com/wp-content/uploads/technical_support/bulletins/2023/SB_SD4-04-2023_IR-Exchange-of-stator-assy--and-propeller-shaft-on-ROTAX-912ULS.pdf"),
    b("SB-SD4-02-2023", "2023-03-30", "Installation of two DYNON SkyView HDX displays (SV-HDX1100)", [RTC, NV], "SB", False,
      "https://www.tomarkaero.com/wp-content/uploads/SB_SD4-02-2023_IR.pdf"),
    b("SB-SD4-01-2023", "2023-01-20", "Replacement of fuel pump nipple fittings on ROTAX 912 (Series)", [RTC], "SB", True,
      "https://www.tomarkaero.com/wp-content/uploads/technical_support/bulletins/2023/SB_SD4-01-2023_Replacement%20of%20fuel%20pump%20nipple%20fittings%20on%20ROTAX%20Engine%20Type%20912%20(Series)%20Aircraft%20Engines.pdf"),
    b("SB-SD4-03-2022", "2022-07-15", "Installation of SilentHektik F1209v Coolant Thermostat", [RTC, NV], "SB"),
    b("SB-SD4-02-2022", "2022-06-30", "SD4-A-1-509-L-7 / R-7 Engine mount bottom bracket outer stiffeners inspection", [RTC, NV], "SB"),
    b("SB-SD4-01-2022", "2022-04-28", "Installation of DUC Hélices FLASH-R propeller H-FSH_3-D-R_I", [RTC], "SB"),
    b("SB-SD4-01-2021", "2021-09-21", "Exchange of STROBE and NAV LIGHTS automatic circuit breakers [OPTIONAL]", [RTC], "SB", False),
    b("SB-SD4-02-2020", "2020-12-15", "Exchange of carburettor floats on ROTAX 912 ULS (P/N 861185 / 861188)", [RTC], "SB"),
    b("SB-SD4-01-2020", "2020-01-09", "Introduction of Oil Hoses CONTI OLN M4M-2 for Rotax 912 S2/ULS2", [RTC, NV], "SB"),
    b("SB-SD4-01-2019", "2019-08-09", "Replacement of circlip Rotax P/N 945785 in both carburettors (912 ULS)", [RTC], "SB"),
    b("TOM-TC-01-ICA", "2019-01-11", "Instructions for Continued Airworthiness – Viper SD-4 RTC / Night-VFR", [RTC, NV], "DOC"),
    b("SB-SD4-06-2017-R2", "2018-02-12", "Valve push-rod assy / rocker arms inspection or replacement – ROTAX 912/914", [RTC], "SB"),
    b("SB-SD4-01-2017-R1", "2017-12-12", "Additional exhaust silencer installation", [RTC], "SB", False),
    b("SB-B-SD4-04-2017", "2017-07-03", "Cabin canopy glass maintenance update", [UL], "SB"),
    b("SB_LSZ_SD4-03-2017", "2017-06-19", "Connection of central fuselage rear cross-beam with vertical stiffener (all Viper SD-4)", [UL], "SB", True,
      "https://www.tomarkaero.com/wp-content/uploads/technical_support/bulletins/2017/SB_LSZ_SD4-03-2017_SVK.pdf"),
    b("SB_SD4-01-2016", "2016-08-16", "Inspection / replacement of Rotax 912/914 carburettor floats", [UL], "SB"),
    b("SIB-SD4-02-2015", "2015-12-11", "Use of ACF-50 anti-corrosion compound", [UL], "SIB", False),
    b("SIB-SD4-01-2015", "2015-09-16", "Visual inspection of rudder pedals (SK)", [UL], "SIB", False),
    b("ZB-SD4-03-2014", "2014-07-11", "Autopilot servo slipping (SK)", [UL], "SB"),
    b("ZB-SD4-02-2014", "2014-06-19", "Replacement of tow-hook lock (SK)", [UL], "SB"),
    b("ZB-SD4-01-2014", "2014-03-06", "Tightening of rescue system carabiner (SK)", [UL], "SB"),
    b("ZB-SD4-04-2013", "2013-08-05", "Static pressure port relocated to rear fuselage (SK)", [UL], "SB"),
    b("ZB-SD4-03-2013", "2013-05-10", "Nose gear leg flange without reinforcing ribs – inspection/replacement (SK)", [UL], "SB"),
    b("ZB-SD4-02-2013", "2013-05-07", "Nose gear leg connecting flange – inspection/replacement (SK)", [UL], "SB"),
    b("ZB-SD4-01-2012", "2012-09-28", "Nose gear steering rod installation change (SK)", [UL], "SB"),
]

def dump(path, obj):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    print("wrote", path)

if __name__ == "__main__":
    dump("app/src/main/assets/types.json", types)
    feed = {"version": 1, "updated": "2026-10-20", "bulletins": bulletins}
    dump("app/src/main/assets/bulletins.json", feed)
    dump("data/bulletins.json", feed)
