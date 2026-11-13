"""Merge data/*.jsonl into handhelds.db (SQLite), deduped by akakce_id."""
import glob
import json
import re
import sqlite3
from datetime import date

# Search results can pull in games, cases and accessories; keep only real consoles.
HANDHELD_CATEGORIES = {"gameboy", "nintendo-switch", "telefon-oyun-konsolu"}

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
CREATE TABLE IF NOT EXISTS offers (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    akakce_id     INTEGER NOT NULL REFERENCES handhelds(akakce_id),
    seller        TEXT,
    marketplace   TEXT,   -- NULL = the seller's own web shop
    price_try     REAL,
    listing_title TEXT,
    shipping      TEXT,
    stock         TEXT,
    ship_time     TEXT,
    seller_rating TEXT
);
CREATE INDEX IF NOT EXISTS idx_offer_product ON offers(akakce_id);
CREATE INDEX IF NOT EXISTS idx_offer_seller ON offers(seller);
CREATE VIEW IF NOT EXISTS small_shop_offers AS
    SELECT o.*, h.title FROM offers o JOIN handhelds h USING (akakce_id)
    WHERE o.marketplace IS NULL;
"""
OFFER_COLS = ["akakce_id", "seller", "marketplace", "price_try", "listing_title",
              "shipping", "stock", "ship_time", "seller_rating"]


def category(url):
    m = re.match(r"https://www\.akakce\.com/([^/]+)/", url or "")
    return m.group(1) if m else None


def main():
    rows = {}
    for path in sorted(glob.glob("data/part_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            r = json.loads(line)
            cat = category(r["url"])
            if cat == "playstation-aksesuarlari":
                # PlayStation Portal sits among accessories; keep the device, not its cases/films
                if not (re.search(r"portal", r["title"], re.I) and (r["min_price_try"] or 0) > 5000):
                    continue
            elif cat not in HANDHELD_CATEGORIES:
                continue
            r["category"] = cat
            prev = rows.get(r["akakce_id"])
            if prev:
                prev["sources"].add(r["source"])
            else:
                r["sources"] = {r["source"]}
                rows[r["akakce_id"]] = r

    db = sqlite3.connect("handhelds.db")
    db.executescript("DROP VIEW IF EXISTS small_shop_offers; DROP TABLE IF EXISTS offers;"
                     "DROP TABLE IF EXISTS handhelds;" + SCHEMA)
    today = date.today().isoformat()
    db.executemany(
        "INSERT INTO handhelds VALUES (?,?,?,?,?,?,?,?,?,?)",
        [(r["akakce_id"], r["brand"], r["title"], r["category"], r["min_price_try"],
          r["extra_offers"] + 1, r["url"], r["image"], ",".join(sorted(r["sources"])), today)
         for r in rows.values()])
    offers = []
    for path in sorted(glob.glob("data/offers_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            o = json.loads(line)
            if o.get("seller") and o["akakce_id"] in rows:  # skip "no offers" marker rows
                offers.append(tuple(o.get(c) for c in OFFER_COLS))
    db.executemany(f"INSERT INTO offers ({','.join(OFFER_COLS)}) VALUES ({','.join('?' * len(OFFER_COLS))})",
                   offers)
    db.commit()
    print(f"{len(rows)} unique handhelds, {len(offers)} offers -> handhelds.db")


if __name__ == "__main__":
    main()
