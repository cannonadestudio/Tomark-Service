#!/usr/bin/env python3
"""Daily bulletin collector (GitHub Actions).

Sources (tools/bulletin_sources.json):
  * EASA AD list (newest pages) – rows whose holder/type matches a known manufacturer or Rotax
  * manufacturers' public bulletin pages (Aquila, Evektor, Aerospool, Bristell, Flight Design,
    Shark, Aeroprakt, Comco Ikarus, Remos, TL-Ultralight)
  * TOMARK technical-support list (tools/fetch_tomark.py)

New documents are appended to data/bulletins.json. DRY_RUN=1 only prints what would be added.
Each source fails independently – one broken page never stops the others.
"""
import datetime, html, html.parser, json, os, re, sys, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEED = os.path.join(ROOT, "data/bulletins.json")
CFG = json.load(open(os.path.join(ROOT, "tools/bulletin_sources.json"), encoding="utf-8"))
DRY = os.environ.get("DRY_RUN") == "1"
TODAY = datetime.date.today().isoformat()
UA = {"User-Agent": "Mozilla/5.0 (AeroHangar bulletin check; +https://github.com/cannonadestudio/Tomark-Service)"}

MONTHS = {m.lower(): i + 1 for i, m in enumerate(
    "January February March April May June July August September October November December".split())}
MONTHS.update({m[:3].lower(): i for m, i in list(MONTHS.items())})
CZ_MONTHS = {"leden": 1, "únor": 2, "březen": 3, "duben": 4, "květen": 5, "červen": 6, "červenec": 7,
             "srpen": 8, "září": 9, "říjen": 10, "listopad": 11, "prosinec": 12}


def notice(msg):
    print(("::notice::" if os.environ.get("GITHUB_ACTIONS") else "") + msg.replace("\n", " | "))


def get(url):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=40).read().decode("utf-8", "replace")


def norm(s):
    return re.sub(r"[^A-Z0-9]", "", s.upper())


def find_date(text, fmt="dmy"):
    t = text
    m = re.search(r"\b(\d{4})-(\d{2})-(\d{2})\b", t)
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    m = re.search(r"\b(\d{1,2})[./-](\d{1,2})[./-](\d{4})\b", t)
    if m:
        a, b, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        d, mo = (b, a) if fmt == "mdy" else (a, b)
        if 1 <= mo <= 12 and 1 <= d <= 31:
            return f"{y:04d}-{mo:02d}-{d:02d}"
    m = re.search(r"\b([A-Za-z]{3,9})\.? (\d{1,2}),? (\d{4})\b", t)          # April 29, 2026
    if m and m.group(1).lower() in MONTHS:
        return f"{int(m.group(3)):04d}-{MONTHS[m.group(1).lower()]:02d}-{int(m.group(2)):02d}"
    m = re.search(r"\b(\d{1,2})\.? ([A-Za-z]{3,9}) (\d{4})\b", t)          # 03 Sep 2026
    if m and m.group(2).lower() in MONTHS:
        return f"{int(m.group(3)):04d}-{MONTHS[m.group(2).lower()]:02d}-{int(m.group(1)):02d}"
    m = re.search(r"\b(\d{1,2})\.? (\w+) (\d{4})\b", t)                    # 9 únor 2015
    if m and m.group(2).lower() in CZ_MONTHS:
        return f"{int(m.group(3)):04d}-{CZ_MONTHS[m.group(2).lower()]:02d}-{int(m.group(1)):02d}"
    return None


def kind_of(num, context):
    n, c = num.upper(), context.lower()
    if n.startswith("EASA AD") or re.match(r"^\d{4}-\d{4}", n):
        return "AD"
    if "ASB" in n or "alert" in c:
        return "ASB"
    if n.startswith("SIB") or "SI-" in n or "IB" in n[:3]:
        return "SIB"
    return "SB"


def mandatory_of(context, kind):
    c = context.lower()
    if kind in ("AD", "ASB") or re.search(r"mandatory|povinn|obligatoire|verbindlich|\bzb\b|sbm", c):
        return True
    if re.search(r"optional|informative|recommended|voliteln|\binfo\b", c):
        return False
    return None


class Blocks(html.parser.HTMLParser):
    """Collects every link with the text of its enclosing row / list item / paragraph."""
    BLOCK = {"tr", "li", "p", "div", "article", "section", "h2", "h3", "h4", "td"}

    def __init__(self):
        super().__init__()
        self.stack = []        # text buffers of open blocks
        self.links = []        # [href, anchor_text, block_index]
        self.blocks = []       # finished block texts by index
        self.cur_a = None

    def handle_starttag(self, tag, attrs):
        if tag in ("td", "th", "br", "span", "div"):
            for i in self.stack:              # keep table cells apart: "a | b | c"
                self.blocks[i] += " | "
        if tag in ("tr", "li", "p", "article"):
            self.stack.append(len(self.blocks))
            self.blocks.append("")
        if tag == "a":
            href = dict(attrs).get("href") or ""
            self.cur_a = [href, "", self.stack[-1] if self.stack else None]

    def handle_endtag(self, tag):
        if tag == "a" and self.cur_a:
            self.links.append(self.cur_a)
            self.cur_a = None
        if tag in ("tr", "li", "p", "article") and self.stack:
            self.stack.pop()

    def handle_data(self, d):
        for i in self.stack:
            self.blocks[i] += d
        if self.cur_a is not None:
            self.cur_a[1] += d


def clean(s):
    s = " ".join(html.unescape(s).split())
    s = re.sub(r"(\s*\|\s*)+", " | ", s)
    return s.strip(" |")


DATE_RX = re.compile(r"\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}[./-]\d{1,2}[./-]\d{4}\b|\b[A-Za-z]{3,9}\.? \d{1,2},? \d{4}\b|\b\d{1,2}\.? [^\W\d_]{3,9} \d{4}\b")


def title_of(text, num):
    t = re.sub(re.escape(num), "", text, flags=re.I)
    t = DATE_RX.sub("", t)
    t = re.sub(r"\b(mandatory|optional|informative|recommended|bulletin|download|pdf)\b", "", t, flags=re.I)
    t = re.sub(r"(\s*\|\s*)+", " | ", t)
    return t.strip(" |:–-_").strip()[:220]


# ---------------------------------------------------------------- sources

def from_links(src):
    page = get(src["url"])
    p = Blocks()
    p.feed(page)
    rx = re.compile(src["id_re"], re.I)
    out = {}
    for href, text, bi in p.links:
        text = clean(text)
        full = urllib.parse.urljoin(src["url"], href)
        fname = urllib.parse.unquote(full.rsplit("/", 1)[-1])
        m = rx.search(text) or rx.search(fname) or rx.search(urllib.parse.unquote(full))
        if not m:
            continue
        num = m.group(0).replace("_", "-").strip(" -")
        key = norm(src["id"] + "-" + m.group(0))
        block = clean(p.blocks[bi]) if bi is not None else text
        title = title_of(block or text, m.group(0)) or title_of(text, m.group(0)) or fname
        prev = out.get(key)
        if prev and len(prev["title"]) >= len(title):
            continue
        k = kind_of(num, block)
        out[key] = {"id": key, "date": find_date(block, src.get("date_fmt", "dmy")) or TODAY,
                    "title": f"{num}: {title}" if title and title.upper() != num.upper() else num,
                    "groups": src["groups"], "kind": k, "mandatory": mandatory_of(block, k), "url": full}
    return list(out.values())


def from_easa():
    cfg = CFG["easa"]
    items = []
    for page_no in range(1, cfg.get("pages", 3) + 1):
        page = get(f"https://ad.easa.europa.eu/home/?page={page_no}")
        p = Blocks()
        p.feed(page)
        for href, text, bi in p.links:
            m = re.search(r"/ad/(\d{4}-\d{4}(?:R\d+)?(?:-E)?)$", href)
            if not m or bi is None:
                continue
            row = clean(p.blocks[bi])
            row_u = row.upper()
            groups = sorted({g for key, gs in cfg["holders"].items() if key in row_u for g in gs})
            if not groups:
                continue
            no = m.group(1)
            cells = [c.strip() for c in row.split("|") if c.strip()]
            # Number | Issued by | Issue date | Subject | Approval holder / type | Effective date | …
            subject = cells[3] if len(cells) > 3 else title_of(row, no)
            holder = cells[4] if len(cells) > 4 else ""
            items.append({"id": "EASA-AD-" + no, "date": find_date(row) or TODAY,
                          "title": f"EASA AD {no}: {subject}" + (f" ({holder})" if holder else ""), "groups": groups, "kind": "AD",
                          "mandatory": True, "url": "https://ad.easa.europa.eu/ad/" + no})
    # one row may list the same AD twice (links to detail and PDF)
    return list({i["id"]: i for i in items}.values())


def main():
    feed = json.load(open(FEED, encoding="utf-8"))
    have = {b["id"] for b in feed["bulletins"]}
    have_norm = {norm(b["id"]) for b in feed["bulletins"]}
    added_total = 0
    jobs = [("easa", from_easa)] + [(s["id"], (lambda s=s: from_links(s))) for s in CFG["sources"]]
    for name, fn in jobs:
        try:
            found = fn()
        except Exception as e:  # one source never breaks the others
            notice(f"{name}: ERROR {type(e).__name__}: {e}")
            continue
        new = [b for b in found if b["id"] not in have and norm(b["id"]) not in have_norm]
        notice(f"{name}: found {len(found)}, new {len(new)}" + ("; e.g. " + " || ".join(f"{b['date']} {b['title'][:90]}" for b in new[:3]) if new else ""))
        for b in new:
            feed["bulletins"].append(b)
            have.add(b["id"])
            have_norm.add(norm(b["id"]))
        added_total += len(new)

    if added_total and not DRY:
        feed["bulletins"].sort(key=lambda x: x["date"], reverse=True)
        feed["version"] = feed.get("version", 0) + 1
        feed["updated"] = TODAY
        json.dump(feed, open(FEED, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    notice(f"TOTAL new: {added_total}" + (" (dry run – nothing written)" if DRY else ""))


if __name__ == "__main__":
    main()
