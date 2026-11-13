"""Dump every table and view of handhelds.db to one readable text file.

Usage: python3 export_txt.py [handhelds.db] [handhelds_db.txt]
"""
import sqlite3
import sys
from datetime import datetime, timezone

MAX_COL = 60  # truncate long cells (titles, URLs) so columns stay readable


def cell(v):
    if v is None:
        return ""
    if isinstance(v, float):
        return f"{v:,.2f}".rstrip("0").rstrip(".")
    s = str(v).replace("\n", " ").replace("\t", " ")
    return s if len(s) <= MAX_COL else s[:MAX_COL - 1] + "…"


def dump(db, name, out):
    cur = db.execute(f'SELECT * FROM "{name}"')
    cols = [d[0] for d in cur.description]
    rows = [[cell(v) for v in r] for r in cur.fetchall()]
    widths = [max([len(c)] + [len(r[i]) for r in rows]) for i, c in enumerate(cols)]
    out.write(f"\n{'=' * 100}\n{name}  ({len(rows)} rows)\n{'=' * 100}\n")
    out.write("  ".join(c.ljust(w) for c, w in zip(cols, widths)).rstrip() + "\n")
    out.write("  ".join("-" * w for w in widths) + "\n")
    for r in rows:
        out.write("  ".join(v.ljust(w) for v, w in zip(r, widths)).rstrip() + "\n")


def main(db_path="handhelds.db", out_path="handhelds_db.txt"):
    db = sqlite3.connect(db_path)
    objs = db.execute("SELECT name, type FROM sqlite_master WHERE type IN ('table','view') "
                      "AND name NOT LIKE 'sqlite%' ORDER BY type DESC, name").fetchall()
    # most useful first
    first = ["priority_deals", "best_deals", "priority_watchlist", "listings_unified"]
    objs.sort(key=lambda o: (first.index(o[0]) if o[0] in first else len(first), o[0]))
    with open(out_path, "w", encoding="utf-8") as out:
        out.write(f"handhelds.db text export, {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC\n")
        out.write("Contents: " + ", ".join(f"{n} ({t})" for n, t in objs) + "\n")
        for name, _ in objs:
            dump(db, name, out)
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
