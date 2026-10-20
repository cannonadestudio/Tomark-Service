#!/usr/bin/env python3
"""Daily job (GitHub Actions): reads the public TOMARK technical-support list and
adds documents not seen before straight to data/bulletins.json (the online feed).

Only the public table (date / number / title) is read – the PDFs need a login.
New entries get mandatory=None ("check document") unless they are AD / ASB.
"""
import datetime, html.parser, json, os, re, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = "https://www.tomarkaero.com/en/technical-support/"
FEED = os.path.join(ROOT, "data/bulletins.json")
SEEN = os.path.join(ROOT, "tools/tomark_seen.json")

SECTIONS = [  # heading prefix -> group
    ("Viper SD-4 RTC", "SD4_RTC"),
    ("Viper SD-4 Night", "SD4_NVFR"),
    ("Viper SD-4 (", "SD4_UL"),
    ("Skyper GT9", "GT9"),
]
MONTHS = {m: i + 1 for i, m in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}


def norm(s):
    return re.sub(r"[^A-Z0-9]", "", s.upper())


class P(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.group, self.in_h, self.in_td, self.h, self.row, self.cell = None, False, False, "", None, ""
        self.rows = []

    def handle_starttag(self, tag, a):
        if tag in ("h2", "h3", "h4"):
            self.in_h, self.h = True, ""
        elif tag == "tr":
            self.row = []
        elif tag == "td" and self.row is not None:
            self.in_td, self.cell = True, ""

    def handle_endtag(self, tag):
        if tag in ("h2", "h3", "h4") and self.in_h:
            self.in_h = False
            t = self.h.strip()
            for prefix, g in SECTIONS:
                if t.startswith(prefix):
                    self.group = g
                    break
            else:
                if t:
                    self.group = None
        elif tag == "td" and self.in_td:
            self.in_td = False
            self.row.append(" ".join(self.cell.split()))
        elif tag == "tr" and self.row is not None:
            if self.group and len(self.row) >= 3:
                self.rows.append((self.group, *self.row[:3]))
            self.row = None

    def handle_data(self, d):
        if self.in_h:
            self.h += d
        if self.in_td:
            self.cell += d


def parse_date(s):
    m = re.match(r"(\d{1,2})\.\s*([A-Za-z]{3})\w*\s+(\d{4})", s)
    if not m:
        return None
    return datetime.date(int(m.group(3)), MONTHS.get(m.group(2).title(), 1), int(m.group(1))).isoformat()


def kind(num):
    n = num.upper()
    if n.startswith("EASA AD"):
        return "AD"
    if n.startswith("ASB"):
        return "ASB"
    if n.startswith("SIB"):
        return "SIB"
    if re.match(r"^(SB|ZB)", n):
        return "SB"
    return "DOC"


def main():
    req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0 (AeroHangar bulletin check)"})
    page = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    p = P()
    p.feed(page)
    if len(p.rows) < 10:
        sys.exit(f"Parsed only {len(p.rows)} rows – page layout changed? Not touching data.")

    seen = set(json.load(open(SEEN)))
    feed = json.load(open(FEED, encoding="utf-8"))
    auto = feed["bulletins"]
    by_id = {a["id"]: a for a in auto}
    added = 0
    for group, date, num, title in p.rows:
        key = norm(num)
        if not key:
            continue
        if key in seen:
            # Same document listed under another section: extend its groups.
            a = by_id.get(key)
            if a and group not in a["groups"]:
                a["groups"].append(group)
            continue
        k = kind(num)
        entry = {"id": key, "date": parse_date(date) or datetime.date.today().isoformat(),
                 "title": f"{num}: {title}", "groups": [group], "kind": k,
                 "mandatory": True if k in ("AD", "ASB") else None, "url": URL}
        auto.append(entry)
        by_id[key] = entry
        seen.add(key)
        added += 1

    json.dump(sorted(seen), open(SEEN, "w"), indent=0)
    if added:
        auto.sort(key=lambda x: x["date"], reverse=True)
        feed["version"] = feed.get("version", 0) + 1
        feed["updated"] = max(x["date"] for x in auto)
        json.dump(feed, open(FEED, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"rows: {len(p.rows)}, new: {added}")


if __name__ == "__main__":
    main()
