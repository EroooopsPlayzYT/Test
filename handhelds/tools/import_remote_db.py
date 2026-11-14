"""Import observations from another machine's handhelds.db (e.g. the PC session) into a committed file.

The PC session's raw scraper output folders are gitignored, so its data reaches GitHub only inside
its handhelds.db. This pulls that db's observation tables into data/pc_observations.jsonl
(append-only, deduplicated), which build_db.py loads, so neither side's data is lost on rebuild.

Usage (from handhelds/):
  git show origin/<branch>:handhelds/handhelds.db > /tmp/remote.db
  python3 tools/import_remote_db.py /tmp/remote.db
"""
import json
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "pc_observations.jsonl"
COLS = ["source", "product_id", "title", "price_try", "in_stock", "seller", "condition", "url", "query", "scraped_at"]


def main(path):
    remote = sqlite3.connect(path)
    tables = {n for (n,) in remote.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    rows = []
    if "scraper_observations" in tables:
        rows += remote.execute(f"SELECT {','.join(COLS)} FROM scraper_observations").fetchall()
    have = set()
    if OUT.exists():
        have = {tuple(json.loads(l)[c] for c in ("source", "url", "price_try", "scraped_at")) for l in OUT.open()}
    # skip anything this machine already has in its own scraper dbs
    local = sqlite3.connect(ROOT / "handhelds.db")
    try:
        have |= set(local.execute("SELECT source, url, price_try, scraped_at FROM scraper_observations").fetchall())
    except sqlite3.Error:
        pass
    new = 0
    with OUT.open("a", encoding="utf-8") as f:
        for r in rows:
            d = dict(zip(COLS, r))
            key = (d["source"], d["url"], d["price_try"], d["scraped_at"])
            if key in have:
                continue
            have.add(key)
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
            new += 1
    print(f"remote observations: {len(rows)}, new: {new} -> {OUT}")


if __name__ == "__main__":
    main(sys.argv[1])
