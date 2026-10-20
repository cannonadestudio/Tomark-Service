#!/usr/bin/env python3
"""Weekly contact collector (GitHub Actions) – official ULC lists:
  * practical examiners – aeroplanes / gliders / balloons (FE, CRE, IRE, FIE …)
  * examiners – ultralights, gyroplanes, paragliders (UACP, UAGP …)
  * approved maintenance organisations Part-CAO and Part-145
The newest PDF is always taken from the ULC page that links it. Entries are replaced as a whole
(prefix ulc-…), so the app always shows the current lists. Coordinates: region (voivodeship) centre for
examiners, OpenStreetMap geocoding of the postcode/city for organisations (cached in tools/geocache.json).
DRY_RUN=1 prints a summary only.  Needs: pip install pdfplumber
"""
import datetime, html, json, os, re, sys, time, urllib.parse, urllib.request

import pdfplumber

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTACTS = os.path.join(ROOT, "data/contacts.json")
GEOCACHE = os.path.join(ROOT, "tools/geocache.json")
DRY = os.environ.get("DRY_RUN") == "1"
TODAY = datetime.date.today()
UA = {"User-Agent": "Mozilla/5.0 (AeroHangar contacts; +https://github.com/cannonadestudio/Tomark-Service)"}

EXAM_PAGE = "https://ulc.gov.pl/personel-lotniczy/komisja-egzaminacyjna/egzaminy-praktyczne"
ORG_PAGE = "https://ulc.gov.pl/technika-lotnicza/wykazy-organizacji"

VOIV = {  # approximate centres (capital cities) for "nearest" sorting
    "dolnośląskie": (51.11, 17.03, "Wrocław"), "kujawsko-pomorskie": (53.12, 18.01, "Bydgoszcz"),
    "lubelskie": (51.25, 22.57, "Lublin"), "lubuskie": (52.73, 15.24, "Gorzów Wlkp."),
    "łódzkie": (51.76, 19.46, "Łódź"), "małopolskie": (50.06, 19.94, "Kraków"),
    "mazowieckie": (52.23, 21.01, "Warszawa"), "opolskie": (50.67, 17.93, "Opole"),
    "podkarpackie": (50.04, 22.00, "Rzeszów"), "podlaskie": (53.13, 23.16, "Białystok"),
    "pomorskie": (54.35, 18.65, "Gdańsk"), "śląskie": (50.26, 19.02, "Katowice"),
    "świętokrzyskie": (50.87, 20.63, "Kielce"), "warmińsko-mazurskie": (53.78, 20.49, "Olsztyn"),
    "wielkopolskie": (52.41, 16.93, "Poznań"), "zachodniopomorskie": (53.43, 14.55, "Szczecin"),
}
EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
PHONE = re.compile(r"(?:\+?48[\s-]?)?(?:\d[\s-]?){9}\b")
CERT = re.compile(r"PL/[A-Z]+/\d+")


def notice(msg):
    print(("::notice::" if os.environ.get("GITHUB_ACTIONS") else "") + msg.replace("\n", " | "))


def get(url, binary=False):
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
    return data if binary else data.decode("utf-8", "replace")


def links(page_url):
    page = get(page_url)
    return [urllib.parse.urljoin(page_url, html.unescape(h)) for h in re.findall(r'href="([^"]+\.pdf)"', page, re.I)]


def pick(urls, *needles, exclude=()):
    """Newest link whose decoded file name contains all needles and none of exclude (case-insensitive)."""
    def name(u):
        return urllib.parse.unquote(u).rsplit("/", 1)[-1].lower()
    hits = [u for u in urls if all(n.lower() in name(u) for n in needles) and not any(x.lower() in name(u) for x in exclude)]
    return hits[0] if hits else None


def debug_rows(label, rows, n=8):
    if DRY:
        notice(f"RAW {label}: " + " ## ".join(" | ".join(c.replace(chr(10), " / ")[:60] for c in r) for r in rows[:n]))


def pdf_rows(url):
    tmp = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "ulc.pdf")
    open(tmp, "wb").write(get(url, binary=True))
    rows = []
    with pdfplumber.open(tmp) as pdf:
        for page in pdf.pages:
            for table in page.extract_tables():
                for r in table:
                    rows.append([(c or "").strip() for c in r])
    return rows


def norm_phone(s):
    d = re.sub(r"[^\d+]", "", s)
    return d if len(re.sub(r"\D", "", d)) >= 9 else ""


# ---------------------------------------------------------------- examiners

NAME_RX = re.compile(r"^[A-ZŁŚŻŹĆŃÓĄĘ][a-ząćęłńóśźż'-]+(?:-[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+)?\s+[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+(?:\s+[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+)?$")
TEL_RX = re.compile(r"tel\.?\s*:?\s*((?:\+?48)?[\d\s-]{9,15})", re.I)


def logical(r):
    """pdfplumber gives 13 columns with empty spacers; keep the 7 real ones."""
    if len(r) >= 13:
        return [r[i] for i in (0, 2, 4, 6, 8, 10, 12)]
    return (r + [""] * 7)[:7]


def parse_examiners(url, label, ul):
    """Lines of: certificate | name / e-mail / tel. | region | category | scope | description | valid until.
    One examiner spans many lines; a new examiner starts at a line with a name in column 2."""
    rows = pdf_rows(url)
    debug_rows("exam " + label[:20], rows[:2] + rows[2:40:6])
    people, cur, pending = [], None, []
    for raw in rows:
        r = [" ".join((c or "").split()) for c in logical(raw)]
        c0, c1, c2, c3, c4, c5, c6 = r
        if c1.lower().startswith("nazwisko") or c0.lower().startswith("nr"):
            continue
        certs = CERT.findall(c0) + re.findall(r"PL/[A-Z]+/\d+|\b\d{3,5}/\d{2}\b", c0) if c0 else []
        for part in [x.strip() for x in re.split(r" / |;", c1) if x.strip()]:
            if EMAIL.search(part):
                if cur is not None and not cur["email"]:
                    cur["email"] = EMAIL.search(part).group(0)
            elif TEL_RX.search(part) or re.fullmatch(r"[\d\s+-]{9,15}", part):
                if cur is not None and not cur["phone"]:
                    m = TEL_RX.search(part)
                    cur["phone"] = norm_phone(m.group(1) if m else part)
            elif NAME_RX.match(part):
                cur = {"name": part, "email": "", "phone": "", "regions": [], "quals": [], "valid": [], "certs": pending}
                pending = []
                people.append(cur)
        for c in certs:
            if cur is None:
                pending.append(c)
            elif c not in cur["certs"]:
                cur["certs"].append(c)
        if cur is None:
            continue
        for v in VOIV:
            if v in c2.lower() and v not in cur["regions"]:
                cur["regions"].append(v)
        q = " – ".join(x for x in (c3, c5) if x)
        if q and q not in cur["quals"] and not q.lower().startswith(("kategoria", "zakres")):
            cur["quals"].append(q[:80])
        for m in re.finditer(r"\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b", c6):
            try:
                cur["valid"].append(datetime.date(int(m.group(3)), int(m.group(2)), int(m.group(1))))
            except ValueError:
                pass

    out, seen = [], set()
    for p in people:
        if p["valid"] and max(p["valid"]) < TODAY:
            continue  # all authorisations expired
        key = re.sub(r"\W+", "-", p["name"].lower()).strip("-")
        cid = f"ulc-exam-{'ul-' if ul else ''}{key}"[:80]
        if cid in seen:
            continue
        seen.add(cid)
        reg = p["regions"][0] if p["regions"] else None
        lat, lon, _ = VOIV[reg] if reg else (None, None, "")
        quals = p["quals"][:6]
        valid = max(p["valid"]).strftime("%d.%m.%Y") if p["valid"] else ""
        cats = []
        for q in quals:
            cat = q.split(" – ")[0]
            if cat and cat not in cats:
                cats.append(cat)
        c = {"id": cid, "name": p["name"], "kind": "examiner",
             "brands": cats[:4] + (["UL"] if ul else []),
             "country": "PL", "city": ", ".join(p["regions"][:3]) + (" …" if len(p["regions"]) > 3 else ""),
             "lat": lat, "lon": lon, "phone": p["phone"], "email": p["email"],
             "note": (label + ". " + "; ".join(quals) + (f". Ważne do {valid}" if valid else "") + (". " + " ".join(p["certs"][:3]) if p["certs"] else "")).strip(),
             "source": url}
        out.append({k: v for k, v in c.items() if v not in (None, "", [])})
    return out


# ---------------------------------------------------------------- organisations

def geocode(query, cache):
    if query in cache:
        return cache[query]
    try:
        url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"q": query, "format": "json", "limit": 1, "countrycodes": "pl"})
        res = json.loads(get(url))
        time.sleep(1.1)  # Nominatim usage policy: max 1 request / second
        cache[query] = [float(res[0]["lat"]), float(res[0]["lon"])] if res else None
    except Exception:
        cache[query] = None
    return cache[query]


def parse_orgs(url, label, approval_rx, cache):
    """Name, number, issue date and 'Siedziba organizacji' (seat) share one cell; contacts may follow."""
    rows = pdf_rows(url)
    debug_rows("org " + label[-8:], rows[:2] + rows[2:40:6])
    text = "\n".join(" ".join(c for c in r if c) for r in rows)
    orgs = {}
    matches = list(re.finditer(approval_rx, text))
    for i, m in enumerate(matches):
        no = m.group(0)
        if no in orgs:
            continue
        line_start = text.rfind("\n", 0, m.start()) + 1
        name = " ".join(text[line_start:m.start()].split()).strip(' "„”/') or no
        chunk = text[m.end(): matches[i + 1].start() if i + 1 < len(matches) else m.end() + 800]
        email = EMAIL.search(chunk)
        tel = TEL_RX.search(chunk)
        post = re.search(r"\b(\d{2}-\d{3})\s+([A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ-]+(?:\s+[A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźż-]+)?)", chunk)
        street = re.search(r"(?:ul\.|al\.|ulica)\s*[^,\n/]{3,40}", chunk, re.I)
        city = post.group(2) if post else ""
        addr = ", ".join(x for x in ((street.group(0).strip() if street else ""), (post.group(0) if post else "")) if x)
        ll = geocode(f"{post.group(1)} {city}, Polska", cache) if post else None
        orgs[no] = {"id": "ulc-pl-" + re.sub(r"\W+", "-", no.lower()).strip("-"), "name": name[:120], "kind": "maintenance",
                    "brands": [label.replace("Organizacja obsługowa ", "")], "country": "PL", "city": city, "address": addr,
                    "lat": ll[0] if ll else None, "lon": ll[1] if ll else None,
                    "phone": norm_phone(tel.group(1)) if tel else "", "email": email.group(0) if email else "",
                    "note": f"{label} {no}", "source": url}
    return [{k: v for k, v in o.items() if v not in (None, "", [])} for o in orgs.values()]


def main():
    cache = json.load(open(GEOCACHE)) if os.path.exists(GEOCACHE) else {}
    collected = {}
    try:
        ex = links(EXAM_PAGE)
        a = pick(ex, "LISTA_EGZAMINATOR", "_A_FE") or pick(ex, "EGZAMINATOR", "FE_TRE")
        u = pick(ex, "EGZAMINATOR", "UACP")
        if a:
            collected["ulc-exam-"] = parse_examiners(a, "Egzaminator ULC (samoloty/szybowce/balony)", False)
        if u:
            collected["ulc-exam-ul-"] = parse_examiners(u, "Egzaminator ULC (UL, wiatrakowce, paralotnie)", True)
        notice(f"examiner lists: A={a} UL={u}")
    except Exception as e:
        notice(f"examiners: ERROR {type(e).__name__}: {e}")
    try:
        org = links(ORG_PAGE)
        cao = pick(org, "CAO", exclude=("NCAO", "145", "CAMO"))
        p145 = pick(org, "145", exclude=("NCAO", "dodatk"))
        found = []
        if cao:
            found += parse_orgs(cao, "Organizacja obsługowa Part-CAO", r"PL\.CAO\.\d{3,4}", cache)
        if p145:
            found += parse_orgs(p145, "Organizacja obsługowa Part-145", r"PL\.145\.\d{3,4}", cache)
        collected["ulc-pl-"] = found
        notice(f"organisation lists: CAO={cao} 145={p145}")
    except Exception as e:
        notice(f"organisations: ERROR {type(e).__name__}: {e}")

    for prefix, items in collected.items():
        sample = " || ".join(f"{c['name']} [{c.get('city','')}] {c.get('email','')} {c.get('phone','')} {c.get('note','')[:80]}" for c in items[:3])
        notice(f"{prefix}: {len(items)} entries; e.g. {sample}")

    if DRY or not collected:
        notice("dry run – nothing written" if DRY else "nothing collected")
        return
    feed = json.load(open(CONTACTS, encoding="utf-8"))
    def owner(cid):  # longest matching prefix, so "ulc-exam-ul-…" belongs to the UL list, not "ulc-exam-"
        ps = [p for p in collected if cid.startswith(p)]
        return max(ps, key=len) if ps else None

    keep = [c for c in feed["contacts"] if not (owner(c["id"]) and collected[owner(c["id"])])]
    new = [c for items in collected.values() for c in items]
    ids = set()
    merged = []
    for c in keep + new:
        if c["id"] in ids:
            continue
        ids.add(c["id"])
        merged.append(c)
    feed["contacts"] = merged
    feed["version"] = feed.get("version", 0) + 1
    feed["updated"] = TODAY.isoformat()
    json.dump(feed, open(CONTACTS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(cache, open(GEOCACHE, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    notice(f"contacts written: {len(merged)}")


if __name__ == "__main__":
    main()
