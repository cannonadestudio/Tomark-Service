#!/usr/bin/env python3
"""Weekly contact collector (GitHub Actions) – official ULC lists:
  * practical examiners – aeroplanes / gliders / balloons (FE, CRE, IRE, FIE …)
  * examiners – ultralights, gyroplanes, paragliders (UACP, UAGP …)
  * examiners – aircraft mechanics
  * approved maintenance organisations Part-CAO and Part-145
  * aeromedical examiners (AME, lekarze orzecznicy) and aeromedical centres (AeMC)
The newest PDF is always taken from the ULC page that links it. Entries are replaced as a whole
(prefix ulc-…), so the app always shows the current lists. Coordinates: region (voivodeship) centre for
examiners, OpenStreetMap geocoding of the postcode/city for organisations (cached in tools/geocache.json).
DRY_RUN=1 prints a summary only (personal data masked).  Needs: pip install pdfplumber
"""
import datetime, html, json, os, re, sys, time, urllib.parse, urllib.request

import pdfplumber

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTACTS = os.path.join(ROOT, "data/contacts.json")
EXAMINERS = os.path.join(ROOT, "data/examiners.json")
GEOCACHE = os.path.join(ROOT, "tools/geocache.json")
DRY = os.environ.get("DRY_RUN") == "1"
TODAY = datetime.date.today()
UA = {"User-Agent": "Mozilla/5.0 (AeroHangar contacts; +https://github.com/cannonadestudio/Tomark-Service)"}

EXAM_PAGE = "https://ulc.gov.pl/personel-lotniczy/komisja-egzaminacyjna/egzaminy-praktyczne"
ORG_PAGE = "https://ulc.gov.pl/technika-lotnicza/wykazy-organizacji"
MED_PAGE = "https://ulc.gov.pl/personel-lotniczy/medycyna-lotnicza/lekarze-orzecznicy"
AEMC_PAGE = "https://ulc.gov.pl/personel-lotniczy/medycyna-lotnicza/centra-medycyny-lotniczej"
# Used when the examiner page does not link a mechanics list (ULC sometimes keeps it under "dla egzaminatora").
MECH_FALLBACK = "https://ulc.gov.pl/_download/personel_lotniczy/dla-egzaminatora/01-2026/LISTA_EGZAMINATOR%C3%93W_PRAKTYCZNYCH_-_MECHANICY.pdf"

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


def mask(s):  # keep personal contact data out of public CI logs
    s = re.sub(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", "<e-mail>", s)
    return re.sub(r"\+?\d[\d\s-]{7,}\d", "<tel>", s)


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
    pass


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


def parse_examiners(url, label, ul, prefix=None, layout=None):
    """Lines of: certificate | name / e-mail / tel. | region | category | scope | description | valid until.
    One examiner spans many lines; a new examiner starts at a line with a name in column 2."""
    rows = pdf_rows(url)
    people, cur = [], None
    skipped = []
    for raw in rows:
        r = [" ".join((c or "").split()) for c in logical(raw)]
        if layout == "mech":
            # certificate | – | name, e-mail, tel. | – | scope (aircraft / engines) | – | Part-66 categories
            r = [r[0], r[2], "", r[6], "", r[4], ""]
        c0, c1, c2, c3, c4, c5, c6 = r
        if c1.lower().startswith("nazwisko") or c0.lower().startswith("nr"):
            continue
        cert = re.search(r"PLE?/[\w./-]*\d\w*|\b\d{2,5}/\d{2,4}\b", c0)
        # Name = text of column 2 before the first e-mail / "tel."
        head = re.split(r"\S+@\S+|\btel\.?", c1, maxsplit=1, flags=re.I)[0].strip(" ,;/")
        if cert and head and cur is not None and head == cur["name"]:
            pass  # the PDF repeats a row – same person
        elif cert and head and re.match(r"^[A-ZŁŚŻŹĆŃÓĄĘ][^\d@]{3,}$", head):
            cur = {"name": head, "email": "", "phone": "", "regions": [], "quals": [], "valid": [], "certs": [cert.group(0)]}
            people.append(cur)
        elif cert and cur is not None and cert.group(0) not in cur["certs"]:
            cur["certs"].append(cert.group(0))
        elif not cert and re.match(r"^[A-ZŁŚŻŹĆŃÓĄĘ][a-ząćęłńóśźż]+ [A-ZŁŚŻŹĆŃÓĄĘ]", c1):
            skipped.append(" ¦ ".join(mask(x)[:30] for x in r))
        if cur is None:
            continue
        em = EMAIL.search(c1)
        if em and not cur["email"]:
            cur["email"] = em.group(0)
        tel = TEL_RX.search(c1)
        if tel and not cur["phone"]:
            cur["phone"] = norm_phone(tel.group(1))
        for v in VOIV:
            if v in c2.lower() and v not in cur["regions"]:
                cur["regions"].append(v)
        q = " – ".join(x for x in (c3, c5) if x)
        if q and q not in cur["quals"] and not q.lower().startswith(("kategoria", "zakres", "teoria", "praktyka", "mml ust")):
            cur["quals"].append(q[:80])
        for m in re.finditer(r"\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b", c6):
            try:
                cur["valid"].append(datetime.date(int(m.group(3)), int(m.group(2)), int(m.group(1))))
            except ValueError:
                pass

    if DRY and skipped:
        notice(mask(f"UNMATCHED {label[-25:]} ({len(skipped)}): " + " ## ".join(skipped[:30])))

    if DRY:
        pass
    out, seen = [], set()
    for p in people:
        if p["valid"] and max(p["valid"]) < TODAY:
            continue  # all authorisations expired
        key = re.sub(r"\W+", "-", p["name"].lower()).strip("-")
        cid = f"{prefix or ('ulc-exam-ul-' if ul else 'ulc-exam-')}{key}"[:80]
        if cid in seen:
            continue
        seen.add(cid)
        reg = p["regions"][0] if p["regions"] else None
        lat, lon, _ = VOIV[reg] if reg else (None, None, "")
        quals = p["quals"][:12]
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


# ---------------------------------------------------------------- aeromedical examiners / centres

def doc_links(page_url):
    """PDF / XLSX / DOCX links on a ULC page."""
    page = get(page_url)
    hrefs = re.findall(r'href="([^"]+\.(?:pdf|xlsx?|docx?))"', page, re.I)
    return page, [urllib.parse.urljoin(page_url, html.unescape(h)) for h in hrefs]


def html_rows(page):
    rows = []
    for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", page, re.S | re.I):
        cells = [" ".join(html.unescape(re.sub(r"<[^>]+>", " ", c)).split()) for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, re.S | re.I)]
        if any(cells):
            rows.append(cells)
    return rows


def xlsx_rows(url):
    import zipfile, io, xml.etree.ElementTree as ET
    z = zipfile.ZipFile(io.BytesIO(get(url, binary=True)))
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", ns):
            shared.append("".join(t.text or "" for t in si.iter("{%s}t" % ns["m"])))
    rows = []
    for name in sorted(n for n in z.namelist() if n.startswith("xl/worksheets/sheet")):
        for row in ET.fromstring(z.read(name)).iter("{%s}row" % ns["m"]):
            cells = []
            for c in row.findall("m:c", ns):
                v = c.find("m:v", ns)
                t = (shared[int(v.text)] if c.get("t") == "s" and v is not None else (v.text if v is not None else "")) or ""
                cells.append(" ".join(t.split()))
            if any(cells):
                rows.append(cells)
    return rows


AME_NO = re.compile(r"\bPL[.\s/-]*(?:AME|MED|AeMC|AMC)[.\s/-]*\d+[\w./-]*", re.I)
POST = re.compile(r"\b(\d{2}-\d{3})\s+([A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ-]+(?:[ -][A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ-]+)?)")
PERSON = re.compile(r"(?:(?:lek\.?|dr|n\. ?med\.|hab\.|prof\.|med\.|lekarz)\s*)*([A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+(?:-[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+)?\s+[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+(?:-[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+)?)")


def parse_medical(rows, url, kind, cache):
    """One row = one examiner / centre: name, certificate number, address (postcode + city), phone, e-mail, classes."""
    out, seen = [], set()
    for r in rows:
        line = " | ".join(c for c in r if c)
        low = line.lower()
        if not line or low.startswith(("lp", "l.p", "imię", "nazwa", "nr ")) or "nazwisko" in low[:40]:
            continue
        no = AME_NO.search(line)
        post = POST.search(line)
        if not (no or post):
            continue
        if kind == "ame":
            m = PERSON.search(line)
            if not m:
                continue
            name = m.group(1)
        else:
            name = next((c for c in r if c and not AME_NO.search(c) and not POST.search(c) and not EMAIL.search(c) and len(c) > 4), "")
            if not name:
                continue
        email = EMAIL.search(line)
        tel = TEL_RX.search(line) or PHONE.search(line)
        classes = sorted(set(re.findall(r"\b(?:klas[ay]?\s*)?(1|2|3)\b(?=[^\d]|$)", " ".join(c for c in r if re.search(r"klas|class|LAPL|1|2", c, re.I) and len(c) < 40))))
        lapl = "LAPL" in line.upper()
        city = post.group(2) if post else ""
        street = re.search(r"(?:ul\.|al\.|pl\.|os\.)\s*[^,|]{3,40}", line, re.I)
        addr = ", ".join(x for x in ((street.group(0).strip() if street else ""), (post.group(0) if post else "")) if x)
        ll = geocode(f"{post.group(1)} {city}, Polska", cache) if post else None
        key = re.sub(r"\W+", "-", (name + "-" + city).lower()).strip("-")
        cid = ("ulc-ame-" if kind == "ame" else "ulc-aemc-") + key[:60]
        if cid in seen:
            continue
        seen.add(cid)
        scope = ", ".join(([f"klasa {'/'.join(classes)}"] if classes else []) + (["LAPL"] if lapl else []))
        label = "Lekarz orzecznik medycyny lotniczej (AME)" if kind == "ame" else "Centrum medycyny lotniczej (AeMC)"
        c = {"id": cid, "name": name[:120], "kind": kind, "brands": [b for b in (scope, no.group(0) if no else "") if b][:3],
             "country": "PL", "city": city, "address": addr, "lat": ll[0] if ll else None, "lon": ll[1] if ll else None,
             "phone": norm_phone(tel.group(1) if tel and tel.lastindex else (tel.group(0) if tel else "")), "email": email.group(0) if email else "",
             "note": label + (f". {scope}" if scope else "") + (f". {no.group(0)}" if no else ""), "source": url}
        out.append({k: v for k, v in c.items() if v not in (None, "", [])})
    return out


def article_text(page):
    """Article body as text: one line per <br>/<p>; Joomla's anti-spam e-mail scripts decoded in place."""
    body = page
    m = re.search(r'<div[^>]+(?:itemprop="articleBody"|class="[^"]*com-content-article__body[^"]*")[^>]*>(.*)', page, re.S | re.I)
    if m:
        body = m.group(1)
    # <span id="cloakXXXX">…</span><script>…addyXXXX = 'name' + '&#64;'; addyXXXX = addyXXXX + 'domain' + '&#46;' + 'pl';…</script>
    def uncloak(mm):
        script = mm.group(2)
        parts = re.findall(r"addy[0-9a-f]+\s*=\s*(?:addy[0-9a-f]+\s*\+\s*)?((?:'[^']*'\s*\+?\s*)+);", script)
        email = html.unescape("".join("".join(re.findall(r"'([^']*)'", x)) for x in parts[:2]))
        return " " + email + " " if EMAIL.fullmatch(email.strip()) else " "
    body = re.sub(r'<span id="cloak([0-9a-f]+)"[^>]*>.*?</span>\s*<script[^>]*>(.*?)</script>', uncloak, body, flags=re.S | re.I)
    # Joomla 4/5: <joomla-hidden-mail first="base64(name)" last="base64(domain)" …>
    def hidden(mm):
        import base64
        at = dict(re.findall(r'(\w+)="([^"]*)"', mm.group(1)))
        try:
            email = base64.b64decode(at.get("first", "")).decode() + "@" + base64.b64decode(at.get("last", "")).decode()
        except Exception:
            return " "
        return " " + email + " " if EMAIL.fullmatch(email) else " "
    body = re.sub(r"<joomla-hidden-mail([^>]*)>.*?</joomla-hidden-mail>", hidden, body, flags=re.S | re.I)
    body = re.sub(r"<script.*?</script>|<style.*?</style>", " ", body, flags=re.S | re.I)
    body = re.sub(r'<a [^>]*href="mailto:([^"]+)"[^>]*>.*?</a>', lambda mm: " " + html.unescape(mm.group(1)) + " ", body, flags=re.S | re.I)
    body = re.sub(r"<br\s*/?>|</p>|</li>|</div>|</h\d>", "\n", body, flags=re.I)
    txt = html.unescape(re.sub(r"<[^>]+>", " ", body))
    txt = txt.replace("Ten adres pocztowy jest chroniony przed spamowaniem. Aby go zobaczyć, konieczne jest włączenie w przeglądarce obsługi JavaScript.", " ")
    return "\n".join(" ".join(l.split()) for l in txt.split("\n"))


TITLE = re.compile(r"(?:(?<=\n)|(?<=\s))(?=(?:lek\.\s*med\.|lek\.\s*dent\.|lek\.|dr\s+n\.\s*med\.|dr\s+hab\.|dr\s+med\.|dr\s+|prof\.)\s*(?:n\.\s*med\.\s*|hab\.\s*|med\.\s*|dr\s*)*[A-ZŁŚŻŹĆŃÓ])")
TITLE_HEAD = re.compile(r"^(?:(?:lek\.\s*med\.|lek\.\s*dent\.|lek\.|dr|n\.\s*med\.|hab\.|med\.|prof\.)\s*)+")
NAME2 = re.compile(r"^([A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+(?:-[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+)?(?:\s+[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+(?:-[A-ZŁŚŻŹĆŃÓ][a-ząćęłńóśźż]+)?){1,2})")


def block_contact(block, kind, url, cache, scope_default=""):
    one = " ".join(block.split())
    if kind == "ame":
        rest = TITLE_HEAD.sub("", one).strip()
        first = TITLE_HEAD.sub("", block.strip().split("\n")[0]).strip()
        m = NAME2.match(rest)
        if not m:
            return None
        # The name is the block's first line when the page breaks lines; otherwise first name + surname
        # (a third capitalised word is the practice: "Przychodnia", "Centrum"…).
        words = m.group(1).split()
        name = first if (first and len(first.split()) <= 3 and first.split()[:2] == words[:2] and "\n" in block.strip()) else " ".join(words[:2])
        org = rest[len(name):].split(" ul.")[0].split(" al.")[0].strip(" ,;-")[:100]
    else:
        line0 = block.strip().split("\n")[0]
        name = line0.split(" ul.")[0].split(" al.")[0].strip(" ,;-")[:120]
        org = ""
        susp = re.search(r"\s*[-–]\s*czasowe zawieszenie[^\n]*", name, re.I)
        if susp:
            name = name[:susp.start()]
            scope_default = "certyfikat czasowo zawieszony"
        if len(name) < 4:
            return None
    post = POST.search(one)
    if not post:
        return None
    email = EMAIL.search(one)
    tel = TEL_RX.search(one) or PHONE.search(one)
    street = re.search(r"(?:ul\.|al\.|pl\.|os\.)\s*[^,]{3,40}?(?=\s+\d{2}-\d{3}|,)", one, re.I)
    city = post.group(2)
    ll = geocode(f"{post.group(1)} {city}, Polska", cache)
    key = re.sub(r"\W+", "-", (name + "-" + city).lower()).strip("-")
    label = "Lekarz orzecznik medycyny lotniczej (AME)" if kind == "ame" else "Centrum medycyny lotniczej (AeMC)"
    c = {"id": ("ulc-ame-" if kind == "ame" else "ulc-aemc-") + key[:60], "name": name, "kind": kind,
         "brands": [b for b in [scope_default] if b], "country": "PL", "city": city,
         "address": ", ".join(x for x in ((org if org else ""), (street.group(0).strip() if street else ""), post.group(0)) if x),
         "lat": ll[0] if ll else None, "lon": ll[1] if ll else None,
         "phone": norm_phone(tel.group(1) if (tel and tel.re is TEL_RX) else (tel.group(0) if tel else "")),
         "email": email.group(0) if email else "",
         "note": label + (f". {scope_default}" if scope_default else ""), "source": url}
    return {k: v for k, v in c.items() if v not in (None, "", [])}


def collect_medical(page_url, kind, cache):
    page = get(page_url)
    txt = article_text(page)
    scope = ""
    sm = re.search(r"Klasa\s+1[^\n]{0,40}", txt)
    if sm:
        scope = " ".join(sm.group(0).split())[:60]
    if kind == "ame":
        start = re.search(r"personelu pokładowego\s*:|orzeczników[^\n]{0,200}:\s", txt)
        body = txt[start.end():] if start else txt
        blocks = [b for b in TITLE.split(body) if b.strip()]
    else:
        # Centres: the name is the nearest line above the address that is not an address / phone / e-mail.
        lines = [l for l in txt.split("\n") if l.strip()]
        blocks, i = [], 0
        while i < len(lines):
            if POST.search(lines[i]):
                j = i
                while j > 0 and re.match(r"^(ul\.|al\.|pl\.|os\.|tel|fax|e-?mail|www|\d)", lines[j].strip(), re.I) and not re.search(r"[A-ZŁŚ][a-z]+ [A-ZŁŚ]", lines[j][:3]):
                    j -= 1
                name_i = j if j != i else max(0, i - 1) if re.match(r"^(ul\.|al\.)", lines[i].strip(), re.I) else i
                end = i + 1
                while end < len(lines) and not POST.search(lines[end]) and re.match(r"^(tel|fax|e-?mail|www|\+|\d|[\w.+-]+@)", lines[end].strip(), re.I):
                    end += 1
                blocks.append("\n".join(lines[name_i:end]))
                i = end
            else:
                i += 1
    out, seen = [], set()
    for b in blocks:
        c = block_contact(b, kind, page_url, cache, scope if kind == "ame" else "")
        if c and c["id"] not in seen:
            seen.add(c["id"])
            out.append(c)
    notice(mask(f"{kind}: {len(blocks)} blocks → {len(out)} entries, {sum(1 for c in out if c.get('email'))} with e-mail, "
                f"{sum(1 for c in out if c.get('lat'))} with coords; e.g. " + " || ".join(f"{c['name']} [{c.get('city','')}] {c.get('address','')[:50]}" for c in out[:4])))
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
        chunk = re.sub(r"\b(Legal|Registered|Address|address|Seat|seat)\b", " ", chunk)
        post = re.search(r"\b(\d{2}-\d{3})[^\S\n]+([A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ-]+(?:[^\S\n]+[A-ZŁŚŻŹĆŃÓ][\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ-]+)?)", chunk)
        street = re.search(r"(?:ul\.|al\.|ulica)\s*[^,\n/]{3,40}", chunk, re.I)
        city = " ".join((post.group(2) if post else "").split("|")[0].split())
        if city.isupper():
            city = city.title()
        if not post:
            seat = re.search(r"Siedziba organizacji[^:|]*[:|]?\s*([^|\n]{4,80})", chunk)
            if seat:
                city = seat.group(1).split(",")[-1].strip()
        # GA focus: Part-145 only when it maintains small aircraft / piston engines
        if "145" in label and not re.search(r"\bA2\b|\bA3\b|\bB2\b|tłokow|piston|ELA|CS-LSA|CS-VLA", chunk, re.I):
            continue
        addr = ", ".join(x for x in ((street.group(0).strip() if street else ""), (post.group(0) if post else "")) if x)
        ll = geocode(f"{post.group(1)} {city}, Polska", cache) if post else (geocode(f"{city}, Polska", cache) if city else None)
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
        mech = pick(ex, "EGZAMINATOR", "MECHANI") or MECH_FALLBACK
        collected["ulc-exam-mech-"] = parse_examiners(mech, "Egzaminator ULC – mechanicy lotniczy", False, prefix="ulc-exam-mech-", layout="mech")
        for c in collected["ulc-exam-mech-"]:
            c["brands"] = ["Mechanik"] + [b for b in c.get("brands", []) if b != "Mechanik"][:3]
        notice(f"examiner lists: A={a} UL={u} MECH={mech}")
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

    try:
        collected["ulc-ame-"] = collect_medical(MED_PAGE, "ame", cache)
    except Exception as e:
        notice(f"ame: ERROR {type(e).__name__}: {e}")
    try:
        collected["ulc-aemc-"] = collect_medical(AEMC_PAGE, "aemc", cache)
    except Exception as e:
        notice(f"aemc: ERROR {type(e).__name__}: {e}")

    for prefix, items in collected.items():
        sample = " || ".join(f"{c['name']} [{c.get('city','')}] {'e-mail' if c.get('email') else '-'} {'tel' if c.get('phone') else '-'} {c.get('note','')[:90]}" for c in items[:4])
        with_contact = sum(1 for c in items if c.get("email") or c.get("phone"))
        with_coords = sum(1 for c in items if c.get("lat"))
        notice(mask(f"{prefix}: {len(items)} entries, {with_contact} with contact, {with_coords} with coords; e.g. {sample}"))

    if DRY or not collected:
        notice("dry run – nothing written" if DRY else "nothing collected")
        return
    def write(path, prefixes_all, wanted):
        """Replace entries of the lists that were collected; keep everything else in that file."""
        feed = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {"version": 0, "updated": "", "contacts": []}
        def owner(cid):  # longest matching prefix, so "ulc-exam-ul-…" belongs to the UL list, not "ulc-exam-"
            ps = [p for p in prefixes_all if cid.startswith(p)]
            return max(ps, key=len) if ps else None
        keep = [c for c in feed["contacts"] if not (owner(c["id"]) and collected.get(owner(c["id"])))]
        merged, ids = [], set()
        for c in keep + [c for p in wanted for c in collected.get(p, [])]:
            if c["id"] not in ids:
                ids.add(c["id"])
                merged.append(c)
        feed["contacts"] = merged
        feed["version"] = feed.get("version", 0) + 1
        feed["updated"] = TODAY.isoformat()
        json.dump(feed, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        return len(merged)

    # Aeromedical examiners are people as well: on-demand file, like examiners (only when something was found).
    exam_prefixes = [p for p in collected if (p.startswith("ulc-exam-") or p == "ulc-ame-") and collected[p]]
    # Examiners (personal data published by ULC) live in a separate file the app downloads only on request.
    n_exam = write(EXAMINERS, exam_prefixes, exam_prefixes)
    # …and are removed from the general contacts list, which is bundled with the app.
    feed = json.load(open(CONTACTS, encoding="utf-8"))
    feed["contacts"] = [c for c in feed["contacts"] if not c["id"].startswith(("ulc-exam-", "ulc-ame-"))]
    json.dump(feed, open(CONTACTS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    cont_prefixes = [p for p in ("ulc-pl-", "ulc-aemc-") if collected.get(p)]
    n_cont = write(CONTACTS, cont_prefixes, cont_prefixes) if cont_prefixes else len(feed["contacts"])
    json.dump(cache, open(GEOCACHE, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    notice(f"written: contacts {n_cont}, examiners {n_exam}")


if __name__ == "__main__":
    main()
