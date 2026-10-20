#!/usr/bin/env python3
"""BOOTSTRAP ONLY. Since the web panel (docs/admin) exists, data/*.json is the source of truth
and is edited there; CI copies data/*.json into the app assets. Running this again
overwrites data/ – it requires --bootstrap.

Originally generated app/src/main/assets/types.json, app/src/main/assets/bulletins.json
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

MML = "Rotax MML-912 Ed.4 Rev.1 (2021)"
MML2 = "Rotax MML-912 wg źródeł wtórnych (RAANZ, rotax-owner) – sprawdź 05-20-00"

def rotax_tasks():
    return [
        task("rotax_25h", L("Rotax: pierwsza kontrola po 25 h (nowy / po remoncie)", "Rotax: first check at 25 h (new / overhauled)", "Rotax: prvá kontrola po 25 h (nový / po GO)", "Rotax : premier contrôle à 25 h (neuf / révisé)"),
             "engine", True, MML, True, once=25),
        task("rotax_100h", L("Rotax: przegląd 100 h / 12 mies.", "Rotax: 100 h / 12 month inspection", "Rotax: prehliadka 100 h / 12 mes.", "Rotax : visite 100 h / 12 mois"),
             "engine", True, MML, True, hours=100, months=12, tol=10,
             note=L("Tolerancja wg MML: ±10 h / ±2 mies.", "MML tolerance: ±10 h / ±2 months.", "Tolerancia MML: ±10 h / ±2 mes.", "Tolérance MML : ±10 h / ±2 mois")),
        task("rotax_oil", L("Rotax: wymiana oleju i filtra", "Rotax: oil & filter change", "Rotax: výmena oleja a filtra", "Rotax : vidange huile et filtre"),
             "engine", True, MML2, False, hours=100, months=12,
             note=L("Przy AVGAS >30% czasu pracy: co 50 h – ustaw interwał.", "With leaded AVGAS >30% of operation: every 50 h – adjust interval.", "Pri AVGAS >30 % prevádzky: každých 50 h.", "Avec AVGAS plombé >30 % : toutes les 50 h.")),
        task("rotax_plugs", L("Rotax: świece zapłonowe", "Rotax: spark plugs", "Rotax: zapaľovacie sviečky", "Rotax : bougies"), "engine", True, MML2, False, hours=200,
             note=L("Przy AVGAS >30%: co 100 h.", "With leaded AVGAS >30%: every 100 h.", "Pri AVGAS >30 %: každých 100 h.", "Avec AVGAS >30 % : toutes les 100 h.")),
        task("rotax_gearbox", L("Rotax: reduktor / sprzęgło przeciążeniowe", "Rotax: gearbox / overload clutch", "Rotax: reduktor / preťažovacia spojka", "Rotax : réducteur / embrayage"), "engine", True, MML2, False, hours=1000,
             note=L("600 h dla sprzęgła P/N 996886 bez otworów i AVGAS >30%.", "600 h for clutch P/N 996886 without drain holes and AVGAS >30%.", "600 h pre spojku P/N 996886 bez otvorov a AVGAS >30 %.", "600 h pour embrayage P/N 996886 sans trous et AVGAS >30 %.")),
        task("rotax_rubber", L("Rotax: wymiana gumy i pompy paliwa (5 lat)", "Rotax: rubber parts & fuel pump (5 years)", "Rotax: gumové diely a palivové čerpadlo (5 rokov)", "Rotax : caoutchoucs et pompe à carburant (5 ans)"),
             "engine", True, MML + " 05-10-00", True, months=60,
             note=L("Membrany i króćce gaźników, węże paliwa i chłodzenia, część węży oleju, pasek, pompa paliwa.", "Carb diaphragms & sockets, fuel and coolant hoses, some oil hoses, V-belt, fuel pump.", "Membrány a hrdlá karburátorov, hadice paliva a chladenia, remeň, čerpadlo.", "Membranes et pipes carbu, durites carburant/refroidissement, courroie, pompe.")),
        task("rotax_tbo", L("Rotax: remont główny (TBO)", "Rotax: overhaul (TBO)", "Rotax: generálna oprava (TBO)", "Rotax : révision générale (TBO)"),
             "engine", True, MML + " 05-10-00", True, hours=2000, months=180,
             note=L("2000 h / 15 lat tylko od określonych numerów seryjnych (np. 912 ULS od 6775790, 912 S od 4923890). Starsze: 1200–1500 h – ustaw interwał.",
                    "2000 h / 15 y only from given S/N (e.g. 912 ULS from 6775790, 912 S from 4923890). Older: 1200–1500 h – set the interval.",
                    "2000 h / 15 r. len od určených v. č. (912 ULS od 6775790, 912 S od 4923890). Staršie: 1200–1500 h.",
                    "2000 h / 15 ans seulement à partir de certains N° (912 ULS dès 6775790, 912 S dès 4923890). Anciens : 1200–1500 h.")),
        task("prop", L("Przegląd śmigła", "Propeller inspection", "Prehliadka vrtule", "Inspection hélice"), "propeller", True,
             "Instrukcja producenta śmigła / propeller manual – ustaw interwał", False),
        task("brs_repack", L("Spadochron ratowniczy – przepakowanie", "Rescue parachute – repack", "Záchranný padák – prebalenie", "Parachute – repliage"),
             "rescue", True, "Instrukcja producenta BRS / Galaxy / Stratos / Junkers – ustaw interwał", False),
        task("brs_rocket", L("Spadochron ratowniczy – rakieta / żywotność", "Rescue parachute – rocket / service life", "Záchranný padák – raketa / životnosť", "Parachute – fusée / durée de vie"),
             "rescue", True, "Instrukcja producenta BRS – ustaw interwał", False),
    ]

def generic_easa_tasks():
    return [
        task("airframe_100h", L("Przegląd okresowy płatowca (wg AMM)", "Airframe periodic inspection (per AMM)", "Periodická prehliadka draku (podľa AMM)", "Visite périodique cellule (selon AMM)"),
             "airframe", True, "AMM producenta – sprawdź / verify", False, hours=100, months=12),
        task("arc", L("Przegląd zdatności (ARC)", "Airworthiness review (ARC)", "Kontrola letovej spôsobilosti (ARC)", "Examen de navigabilité (ARC)"),
             "regulatory", True, "Part-ML, ML.A.901 (Rozp. UE 1321/2014)", True, months=12),
    ] + rotax_tasks()

def generic_ul_tasks():
    return [
        task("ul_100h", L("Przegląd okresowy płatowca 100 h / 12 mies.", "Airframe periodic inspection 100 h / 12 months", "Periodická prehliadka draku 100 h / 12 mes.", "Visite périodique cellule 100 h / 12 mois"),
             "airframe", True, "Instrukcja obsługi producenta – sprawdź / verify", False, hours=100, months=12),
        task("ul_national", L("Przegląd / dokument zdatności wg przepisów krajowych UL", "National UL airworthiness renewal", "Obnova spôsobilosti podľa národných predpisov UL", "Renouvellement navigabilité ULM national"),
             "regulatory", True, "Przepisy krajowe (np. ULC, LAA) – ustaw / set", False, months=12),
    ] + rotax_tasks()

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


# ---------------------------------------------------------------- other Rotax types
ZONES = {"VS0": "white", "VS1": "green", "VFE": "white", "VNE": "red", "VAPP": "approach", "VR": "approach"}
ORDER = ["VS0", "VS1", "VAPP", "VFE", "VA", "VC", "VNE", "VR", "VX", "VY", "VBG"]
LBL["VAPP"] = L("Podejście (wg AFM)", "Approach (AFM)", "Priblíženie (AFM)", "Approche (AFM)")

def gtype(id_, name, maker, cat, tcds, unit, vals, src, verified, groups, mtow, fuel, engine, note=None, unverified_keys=()):
    speeds = []
    for k in ORDER:
        v = vals.get(k)
        if v is None:
            if k == "VAPP" and vals.get("VS0"):
                speeds.append(approach(vals["VS0"], unit))
            else:
                u = unknown(k); u["zone"] = ZONES.get(k, "normal"); speeds.append(u)
            continue
        ok = verified and k not in unverified_keys
        speeds.append(spd(k, LBL[k], v, unit, src if ok else (src + " (brochure)"), ok, ZONES.get(k, "normal")))
    items = [
        item(L("MTOW", "MTOW"), mtow, src),
        item(L("Silnik", "Engine", "Motor", "Moteur"), engine, src),
        item(L("Paliwo", "Fuel", "Palivo", "Carburant"), fuel, src),
    ]
    if note:
        items.append(item(L("Uwaga", "Note", "Poznámka", "Remarque"), note, src))
    return {
        "id": id_, "name": name, "family": maker.lower().split()[0], "manufacturer": maker, "category": cat,
        "tcds": tcds, "bulletinGroups": groups + ["ROTAX_912"],
        "speeds": speeds,
        "manual": [sec("data", L("Dane podstawowe", "Basic data", "Základné údaje", "Données de base"), items)],
        "tasks": generic_easa_tasks() if cat == "EASA" else generic_ul_tasks(),
    }

CAS = L("Prędkości w TCDS podane jako CAS – IAS sprawdź w AFM.", "TCDS speeds are CAS – check IAS in the AFM.", "Rýchlosti v TCDS sú CAS – IAS overte v AFM.", "Vitesses TCDS en CAS – vérifier l'IAS dans l'AFM.")
TC = lambda n: f"EASA TCDS {n}"

types += [
    gtype("p2002jf", "Tecnam P2002 JF", "Tecnam", "EASA", "EASA.A.006", "kt",
          {"VS0": 38, "VS1": 41, "VFE": 67, "VA": 96, "VC": 110, "VNE": 138}, TC("A.006 Iss.13"), True, ["TECNAM"],
          "580 kg (600/620 kg z MOD)", "100 L / 99 L", "Rotax 912 S2 / S3", unverified_keys=("VS0", "VS1")),
    gtype("p2008jc", "Tecnam P2008 JC", "Tecnam", "EASA", "EASA.A.583", "kt",
          {"VFE": 72, "VA": 98, "VC": 111, "VNE": 141}, TC("A.583 Iss.14 (KCAS)"), True, ["TECNAM"],
          "630 kg (650 kg opcja)", "124 L / 120 L", "Rotax 912 S2", note=CAS),
    gtype("p92js", "Tecnam P92-JS", "Tecnam", "EASA", "EASA.A.412", "kt",
          {"VFE": 68, "VA": 93, "VC": 106, "VNE": 134}, TC("A.412 Iss.04"), True, ["TECNAM"],
          "550 kg (600 kg z MOD92/41)", "70 L (90 L opcja)", "Rotax 912 S2"),
    gtype("aquila_a211", "Aquila A211 (AT01)", "Aquila", "EASA", "EASA.A.527", "kt",
          {"VS0": 39, "VS1": 49, "VAPP": 60, "VFE": 90, "VA": 112, "VC": 130, "VNE": 165, "VX": 52, "VY": 65, "VBG": 78},
          TC("A.527 + AFM FM-AT01-1010-103"), True, ["AQUILA"], "750 kg", "120 L / 109,6 L", "Rotax 912 S3 / iSc3"),
    gtype("hk36", "Diamond HK 36 Super Dimona", "Diamond", "EASA", "EASA.A.065", "kmh",
          {"VA": 176, "VNE": 261}, TC("A.065 Iss.05"), True, ["DIAMOND"], "770 kg", "55 L (79 L opcja)", "Rotax 912 A2/A3/S3"),
    gtype("dv20", "Diamond DV 20 Katana", "Diamond", "EASA", "EASA.A.439", "kmh",
          {"VFE": 150, "VA": 193, "VC": 215, "VNE": 291}, TC("A.439 Iss.03"), True, ["DIAMOND"], "730 kg", "79 L / 77 L", "Rotax 912 A3 / S3"),
    gtype("ctls", "Flight Design CTLS", "Flight Design", "EASA", "EASA.A.537", "kt",
          {"VFE": 62, "VA": 105, "VC": 120, "VNE": 145}, TC("A.537 Iss.7"), True, ["FLIGHTDESIGN"], "600 kg", "130 L / 126 L", "Rotax 912 S2 / ULS2 / iS",
          note=L("VFE: klapy 1 – 105 kt, klapy 2 – 80 kt, klapy 3 – 62 kt.", "VFE: flaps 1 – 105 kt, flaps 2 – 80 kt, flaps 3 – 62 kt.")),
    gtype("wt9", "Aerospool WT9 Dynamic LSA", "Aerospool", "EASA", "EASA.A.644", "kt",
          {"VFE": 83, "VA": 96, "VC": 113, "VNE": 142}, TC("A.644 Iss.04 (KCAS)"), True, ["AEROSPOOL"], "600 kg", "126 L / 119 L", "Rotax 912 ULS2", note=CAS),
    gtype("breezer_b600", "Breezer B600", "Breezer", "EASA", "EASA.A.598", "kmh",
          {"VFE": 126, "VA": 179, "VC": 197, "VNE": 252}, TC("A.598 Iss.02"), True, ["BREEZER"], "600 kg", "76 L / 74 L", "Rotax 912 ULS2"),
    gtype("bristell_b23", "BRM Aero Bristell B23", "BRM Aero", "EASA", "EASA.A.642", "kt",
          {"VS0": 43, "VS1": 50, "VFE": 81, "VA": 98, "VC": 135, "VNE": 156}, TC("A.642 Iss.09"), True, ["BRISTELL"], "750 kg", "2 × 60 L", "Rotax 912 S3 / iSc3"),
    gtype("bristell_ul", "BRM Aero Bristell UL / NG5", "BRM Aero", "UL", None, "kmh",
          {"VS0": 73, "VFE": 139, "VA": 162, "VNE": 285}, "DAeC Kennblatt 66253 / ULC UL.A.00-015/2023 (CAS)", True, ["BRISTELL"], "600 kg", "2 × 60 L", "Rotax 912 S / ULS / iS"),
    gtype("c42", "Comco Ikarus C42", "Comco Ikarus", "UL", None, "kt",
          {"VS0": 32, "VS1": 41, "VAPP": 58, "VFE": 63, "VA": 82, "VNE": 121, "VX": 48, "VY": 60, "VBG": 58},
          "C42 POH OHB/C42/001 Iss.19 (UK)", True, ["IKARUS"], "450 / 472,5 kg (UK), do 560 kg wg kraju", "50 / 65 L", "Rotax 912 / 912 S / iS"),
    gtype("a22ls", "Aeroprakt A22LS Foxbat / Vision", "Aeroprakt", "UL", None, "kmh",
          {"VS0": 60, "VS1": 77, "VAPP": 100, "VFE": 132, "VA": 175, "VNE": 229, "VX": 90, "VY": 100, "VBG": 100},
          "DULV Kennblatt 991-22 1 / ULC UL.A.00-010/2023 / POH", True, ["AEROPRAKT"], "600 kg", "90 L (114 L opcja)", "Rotax 912 S / ULS"),
    gtype("a32", "Aeroprakt A32 Vixxen", "Aeroprakt", "UL", None, "kmh",
          {"VS0": 50, "VS1": 60, "VAPP": 100, "VFE": 147, "VA": 151, "VNE": 240, "VX": 100, "VY": 120, "VBG": 110},
          "A32 POH A32-048 (2018)", True, ["AEROPRAKT"], "600 kg", "90 L (114 L opcja)", "Rotax 912 ULS",
          note=L("Sprawdź SB-A32-16 (zmiana oznaczeń prędkościomierza).", "Check SB-A32-16 (ASI marking change).")),
    gtype("shark600", "Shark.Aero Shark 600", "Shark.Aero", "UL", None, "kmh",
          {"VS0": 60, "VS1": 85, "VAPP": 95, "VFE": 141, "VA": 185, "VNE": 328, "VX": 135, "VY": 150, "VBG": 135},
          "Shark600 MA-075 Rev.G (2026)", True, ["SHARK"], "600 kg", "100 L (150 L opcja)", "Rotax 912 ULS",
          note=L("VNE 328 km/h do 3000 ft – maleje z wysokością (tabela w AFM).", "VNE 328 km/h up to 3000 ft – decreases with altitude (AFM table).")),
    gtype("fk9", "FK Lightplanes FK9 Mk IV / ELA", "FK Lightplanes", "UL", None, "kmh",
          {"VS0": 65, "VFE": 105, "VA": 151, "VNE": 230}, "Traficom FIN/U021", True, ["FK"], "450 / 472,5 kg (FI)", "42–78 L", "Rotax 912 UL / ULS"),
    gtype("ev97", "Evektor EV-97 Eurostar SL", "Evektor", "UL", None, "kmh",
          {"VS0": 58, "VFE": 125, "VA": 160, "VNE": 270}, "Traficom FIN/U018 / ULC UL.A.00-013/2023", True, ["EVEKTOR"], "450 / 472,5 kg", "65 L", "Rotax 912 ULS"),
    gtype("remos_gx", "Remos GX", "Remos", "UL", None, "kt",
          {"VS0": 42, "VS1": 44, "VAPP": 60, "VFE": 78, "VA": 88, "VC": 107, "VNE": 135, "VX": 50, "VY": 60, "VBG": 60},
          "Remos GX POH R07 (2017)", True, ["REMOS"], "600 kg", "84 L / 80 L", "Rotax 912 ULS2",
          note=L("VNE maleje z wysokością (128 kt @5000 ft).", "VNE decreases with altitude (128 kt @5000 ft).")),
    gtype("virus_sw", "Pipistrel Virus SW", "Pipistrel", "UL", None, "kt",
          {"VS0": 47, "VFE": 65, "VA": 100, "VNE": 163}, "BMAA TADS BM-97", True, ["PIPISTREL"], "600 kg", "100 L / 98 L", "Rotax 912 ULS"),
    gtype("alpha_trainer", "Pipistrel Alpha Trainer", "Pipistrel", "UL", None, "kmh",
          {"VNE": 250}, "Wikipedia / broszura", False, ["PIPISTREL"], "550 kg", "50 L", "Rotax 912 UL",
          note=L("Ograniczenia zmienione SB-162-00-80-005 – sprawdź POH.", "Limits changed by SB-162-00-80-005 – check the POH.")),
    gtype("faeta", "Atec 321 Faeta NG", "Atec", "UL", None, "kmh",
          {"VS0": 67, "VS1": 90, "VFE": 117, "VA": 180, "VNE": 245}, "Traficom FIN/U048 (CAS)", True, ["ATEC"], "600 kg", "2 × 50 L", "Rotax 912 iS"),
    gtype("vl3", "JMB VL3", "JMB", "UL", None, "kmh",
          {"VS0": 65, "VNE": 315}, "broszura JMB", False, ["JMB"], "600 kg", "120 L", "Rotax 912 ULS / iS"),
    gtype("savage_cub", "Zlin Savage Cub", "Zlin Aviation", "UL", None, "kmh",
          {"VS0": 52, "VNE": 205}, "Wikipedia / aeropedia", False, ["ZLIN"], "450–560 kg", "64–68 L", "Rotax 912 UL / ULS",
          note=L("Źródła publiczne są sprzeczne – wszystkie wartości z POH.", "Public sources disagree – take all values from the POH.")),
]
for t in types[:4]:
    t["manufacturer"] = "TOMARK"
    t["bulletinGroups"] = t["bulletinGroups"] + ["ROTAX_912"]

# ---------------------------------------------------------------- bulletins
TS = "https://www.tomarkaero.com/en/technical-support/"
RTC, NV, UL, GT, RX = "SD4_RTC", "SD4_NVFR", "SD4_UL", "GT9", "ROTAX_912"

def b(id_, date, title, groups, kind, mandatory=None, url=TS):
    return {"id": id_, "date": date, "title": title, "groups": groups, "kind": kind,
            "mandatory": mandatory, "url": url}

bulletins = [
    b("EASA-AD-2026-0170", "2026-09-03", "ATA 72 – Engine – Propeller Gearbox – Inspection (Rotax 912 ULS2/S2)", [RTC, NV], "AD", True,
      "https://ad.easa.europa.eu/blob/EASA_AD_2026_0170.pdf/AD_2026-0170_1"),
    b("ASB_SD4UL-01-2026", "2026-07-01", "Inspection of the propeller gearbox for ROTAX 912, 912 i (Series) Aircraft Engines", [UL], "ASB", True),
    b("ASB_SD4-04-2026", "2026-06-30", "Inspection of the propeller gearbox for ROTAX 912 ULS2 Aircraft Engines", [RTC], "ASB", True),
    b("ROTAX-ASB-2026-001", "2026-06-22", "Rotax ASB-2026-001 / ASB_A-2026-001: Inspection of the propeller gearbox – 912 / 912 i / 914 incl. UL/ULS (affected S/N listed)", [RX], "ASB", True,
      "https://www.flyrotax.com/services/technical-documentation.html"),
    b("ROTAX-SB-912-078UL", "2023-09-14", "Rotax SB-912-078UL: Exchange of propeller shaft – 912 UL/ULS (affected S/N listed; date = TOMARK SB-SD4-04-2023 reference)", [RX], "SB", True,
      "https://www.flyrotax.com/services/technical-documentation.html"),
    b("ROTAX-SB-912-077UL", "2023-09-14", "Rotax SB-912-077UL: Exchange of stator assembly – 912 UL/ULS (affected S/N listed; date = TOMARK SB-SD4-04-2023 reference)", [RX], "SB", True,
      "https://www.flyrotax.com/services/technical-documentation.html"),
    b("ROTAX-DOCS", "2026-10-20", "Rotax technical documentation – current SB/SI/SL and manuals (check your engine S/N)", [RX], "DOC", None,
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
    import sys
    if "--bootstrap" not in sys.argv:
        sys.exit("data/*.json is now edited in the web panel. Re-run with --bootstrap to overwrite it from this script.")
    dump("app/src/main/assets/types.json", types)
    dump("data/types.json", types)
    import datetime
    auto_path = os.path.join(ROOT, "tools/bulletins_auto.json")
    if os.path.exists(auto_path):
        have = {x["id"] for x in bulletins}
        bulletins += [a for a in json.load(open(auto_path, encoding="utf-8")) if a["id"] not in have]
    bulletins.sort(key=lambda x: x["date"], reverse=True)
    feed = {"version": 2, "updated": max(x["date"] for x in bulletins) if bulletins else "", "bulletins": bulletins}
    dump("app/src/main/assets/bulletins.json", feed)
    dump("data/bulletins.json", feed)
    src = json.load(open(os.path.join(ROOT, "tools/contacts_source.json"), encoding="utf-8"))
    keep = ("id", "name", "kind", "brands", "country", "city", "address", "lat", "lon", "phone", "email", "web", "source")
    contacts = [{k: c[k] for k in keep if c.get(k) not in (None, "", [])} for c in src]
    cfeed = {"version": 1, "updated": "2026-10-20", "contacts": contacts}
    dump("app/src/main/assets/contacts.json", cfeed)
    dump("data/contacts.json", cfeed)
