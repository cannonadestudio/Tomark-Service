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


def pick(urls, *needles):
    """Newest link whose decoded file name contains all needles (case-insensitive)."""
    hits = [u for u in urls if all(n.lower() in urllib.parse.unquote(u).lower() for n in needles)]
    return hits[0] if hits else None


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

def parse_examiners(url, label, ul):
    """Columns: certificate | name, e-mail, phone | region | category | scope ref | description | valid until.
    Continuation rows (same examiner, more categories) have an empty name cell."""
    rows = pdf_rows(url)
    people, cur = {}, None
    for r in rows:
        if len(r) < 4:
            continue
        joined = " ".join(r)
        cert = CERT.search(r[0] or "")
        namecell = r[1] if len(r) > 1 else ""
        if cert and namecell and not namecell.lower().startswith("nazwisko"):
            lines = [x.strip() for x in namecell.split("\n") if x.strip()]
            name = next((x for x in lines if not EMAIL.search(x) and not re.search(r"\d{3}", x)), lines[0] if lines else "")
            email = (EMAIL.search(namecell) or [None])[0] if EMAIL.search(namecell) else ""
            phone = norm_phone(PHONE.search(namecell).group(0)) if PHONE.search(namecell) else ""
            key = re.sub(r"\W+", "-", name.lower()).strip("-")
            cur = people.setdefault(key, {"name": " ".join(name.split()), "email": email, "phone": phone,
                                          "regions": [], "quals": [], "valid": [], "certs": []})
            cur["certs"].append(cert.group(0))
        if cur is None:
            continue
        region = r[2] if len(r) > 2 else ""
        for v in VOIV:
            if v in region.lower() and v not in cur["regions"]:
                cur["regions"].append(v)
        cat = " ".join((r[3] if len(r) > 3 else "").split())
        desc = " ".join((r[5] if len(r) > 5 else "").split())
        if cat or desc:
            q = (cat + (" – " + desc if desc else "")).strip(" –")
            if q and q not in cur["quals"]:
                cur["quals"].append(q)
        m = re.search(r"\b(\d{2})\.(\d{2})\.(\d{4})\b", r[-1] if r else "")
        if m:
            cur["valid"].append(datetime.date(int(m.group(3)), int(m.group(2)), int(m.group(1))))

    out = []
    for key, p in people.items():
        if p["valid"] and max(p["valid"]) < TODAY:
            continue  # all authorisations expired
        reg = p["regions"][0] if p["regions"] else None
        lat, lon, city = VOIV[reg] if reg else (None, None, "")
        quals = p["quals"][:6]
        valid = max(p["valid"]).strftime("%d.%m.%Y") if p["valid"] else ""
        c = {"id": f"ulc-exam-{'ul-' if ul else ''}{key}"[:80], "name": p["name"], "kind": "examiner",
             "brands": [q.split(" – ")[0] for q in quals][:4] + (["UL"] if ul else []),
             "country": "PL", "city": ", ".join(p["regions"]) or "",
             "lat": lat, "lon": lon, "phone": p["phone"], "email": p["email"],
             "note": (label + ". " + "; ".join(quals) + (f". Ważne do {valid}" if valid else "") + ". " + " ".join(p["certs"][:3])).strip(),
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
    rows = pdf_rows(url)
    orgs = {}
    for r in rows:
        joined = "\n".join(r)
        m = re.search(approval_rx, joined)
        if not m:
            continue
        no = m.group(0)
        email = EMAIL.search(joined)
        phone = PHONE.search(re.sub(EMAIL, "", joined))
        post = re.search(r"\b(\d{2}-\d{3})\s+([A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ .-]+)", joined)
        # name = first cell that is not the number and has letters
        name = next((" ".join(c.split()) for c in r if c and not re.search(approval_rx, c) and re.search(r"[A-Za-zĄĆĘŁŃÓŚŹŻ]{3}", c)
                     and not EMAIL.search(c) and not re.match(r"^\d", c)), no)
        addr_cell = next((" ".join(c.split()) for c in r if post and post.group(1) in c), "")
        city = " ".join(post.group(2).split()) if post else ""
        city = re.split(r"\s{2,}|,|\btel\b|\bul\b", city)[0].strip() if city else ""
        prev = orgs.get(no)
        if prev:  # multi-row organisation: keep first name/address, add missing contact
            prev.setdefault("email", email.group(0) if email else None)
            continue
        ll = geocode(f"{post.group(1)} {city}, Polska", cache) if post else None
        orgs[no] = {"id": "ulc-" + re.sub(r"\W+", "-", no.lower()).strip("-"), "name": name[:120], "kind": "maintenance",
                    "brands": [label], "country": "PL", "city": city, "address": addr_cell[:160],
                    "lat": ll[0] if ll else None, "lon": ll[1] if ll else None,
                    "phone": norm_phone(phone.group(0)) if phone else "", "email": email.group(0) if email else "",
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
        cao = pick(org, "CAO")
        p145 = pick(org, "145")
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
