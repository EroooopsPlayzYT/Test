"""Merge data/*.jsonl into handhelds.db (SQLite), deduped by akakce_id."""
import glob
import json
import re
import sqlite3
from datetime import date

# Search results can pull in games, cases and accessories; keep only real consoles.
HANDHELD_CATEGORIES = {"gameboy", "oyun-konsolu"}
HANDHELD_WORDS = re.compile(
    r"switch|portal|steam deck|ally|legion go|claw|ayaneo|anbernic|retroid|miyoo|powkiddy|"
    r"el konsol|el atari|gameboy|game boy|retro|psp|vita", re.I)

SCHEMA = """
CREATE TABLE IF NOT EXISTS handhelds (
    akakce_id     INTEGER PRIMARY KEY,
    brand         TEXT,
    title         TEXT NOT NULL,
    category      TEXT,
    min_price_try REAL,
    offer_count   INTEGER,
    url           TEXT,
    image         TEXT,
    sources       TEXT,
    scraped_on    TEXT
);
CREATE INDEX IF NOT EXISTS idx_brand ON handhelds(brand);
CREATE INDEX IF NOT EXISTS idx_price ON handhelds(min_price_try);
"""


def category(url):
    m = re.match(r"https://www\.akakce\.com/([^/]+)/", url or "")
    return m.group(1) if m else None


def main():
    rows = {}
    for path in sorted(glob.glob("data/part_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            r = json.loads(line)
            cat = category(r["url"])
            if cat not in HANDHELD_CATEGORIES:
                continue
            if cat == "oyun-konsolu" and not HANDHELD_WORDS.search(r["title"]):
                continue  # e.g. PS5 / Xbox home consoles from a search
            r["category"] = cat
            prev = rows.get(r["akakce_id"])
            if prev:
                prev["sources"].add(r["source"])
            else:
                r["sources"] = {r["source"]}
                rows[r["akakce_id"]] = r

    db = sqlite3.connect("handhelds.db")
    db.executescript("DROP TABLE IF EXISTS handhelds;" + SCHEMA)
    today = date.today().isoformat()
    db.executemany(
        "INSERT INTO handhelds VALUES (?,?,?,?,?,?,?,?,?,?)",
        [(r["akakce_id"], r["brand"], r["title"], r["category"], r["min_price_try"],
          r["extra_offers"] + 1, r["url"], r["image"], ",".join(sorted(r["sources"])), today)
         for r in rows.values()])
    db.commit()
    print(f"{len(rows)} unique handhelds -> handhelds.db")


if __name__ == "__main__":
    main()
