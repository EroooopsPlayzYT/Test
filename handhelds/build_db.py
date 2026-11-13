"""Merge data/*.jsonl into handhelds.db (SQLite), deduped by akakce_id."""
import glob
import json
import re
import sqlite3
from datetime import date
from urllib.parse import quote_plus

# Search results can pull in games, cases and accessories; keep only real consoles.
HANDHELD_CATEGORIES = {"gameboy", "nintendo-switch", "telefon-oyun-konsolu"}

# Links so sahibinden can be browsed by hand (we never fetch it). Category slugs marked
# verified were seen in web search results; the keyword URL pattern is a best guess.
SAHIBINDEN_SEARCH = "https://www.sahibinden.com/oyun-konsol?query_text={q}"
SAHIBINDEN_CATEGORIES = {
    "MSI Claw": "https://www.sahibinden.com/oyunculara-ozel-oyun-konsolu-msi-claw",
    "PlayStation Portal": "https://www.sahibinden.com/oyunculara-ozel-oyun-konsolu-playstation-portal",
    "Switch 2": "https://www.sahibinden.com/oyunculara-ozel-oyun-konsolu-nintendo-switch-2",
    "Retroid Pocket": "https://www.sahibinden.com/oyunculara-ozel-oyun-konsolu-retroid",
    "PS Vita": "https://www.sahibinden.com/ps-vita",
    "Nintendo 3DS": "https://www.sahibinden.com/nintendo-ds",
    "Nintendo 2DS": "https://www.sahibinden.com/nintendo-ds",
    "El Atarisi": "https://www.sahibinden.com/arama?category=243472",
}
SAHIBINDEN_MODELS = [
    "Steam Deck", "Steam Deck OLED", "ROG Ally", "ROG Ally X", "ROG Xbox Ally", "Legion Go",
    "Legion Go S", "MSI Claw", "Ayaneo", "PlayStation Portal", "PSP", "PS Vita",
    "Nintendo Switch", "Switch OLED", "Switch Lite", "Switch 2", "Nintendo 3DS", "Nintendo 2DS",
    "Game Boy", "Anbernic", "Retroid Pocket", "Miyoo Mini", "Powkiddy", "R36S", "El Atarisi"]

SCHEMA = """
CREATE TABLE IF NOT EXISTS handhelds (
    akakce_id     INTEGER PRIMARY KEY,
    brand         TEXT,
    title         TEXT NOT NULL,
    category      TEXT,
    min_price_try REAL,
    offer_count   INTEGER,
    rating_avg    REAL,
    rating_count  INTEGER,
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
CREATE TABLE IF NOT EXISTS reviews (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    akakce_id  INTEGER NOT NULL REFERENCES handhelds(akakce_id),
    comment_id INTEGER,
    added      TEXT,
    rating     INTEGER,   -- 1..5, NULL if the commenter gave no stars
    text       TEXT       -- reviewer names intentionally not stored
);
CREATE INDEX IF NOT EXISTS idx_review_product ON reviews(akakce_id);
CREATE TABLE IF NOT EXISTS sahibinden_listings (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    model       TEXT,
    kind        TEXT,            -- ad | store | category | mod-service
    source      TEXT,            -- web-search | screenshot (sent by the user)
    ilan_no     TEXT UNIQUE,     -- search this number on sahibinden to open the ad
    title       TEXT NOT NULL,
    price_try   REAL,
    location    TEXT,            -- city / district only
    listed_date TEXT,
    condition   TEXT,
    url         TEXT UNIQUE      -- no seller info stored
);
CREATE TABLE IF NOT EXISTS sahibinden_searches (
    model        TEXT PRIMARY KEY,
    category_url TEXT,          -- verified category page (from search results), if known
    search_url   TEXT NOT NULL  -- keyword search inside the console category
);
CREATE TABLE IF NOT EXISTS search_index_listings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    source      TEXT NOT NULL,  -- site the listing is on (cimri, itopya.com, ...)
    model       TEXT,
    title       TEXT NOT NULL,
    price_try   REAL,           -- as shown in a search-engine snippet; may be stale
    offer_count INTEGER,
    in_stock    INTEGER,
    seen_date   TEXT,
    url         TEXT UNIQUE     -- found via web search; the site itself was not fetched
);
CREATE TABLE IF NOT EXISTS shop_products (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    shop      TEXT NOT NULL,   -- domain scraped directly
    platform  TEXT,
    title     TEXT NOT NULL,
    brand     TEXT,
    price_try REAL,
    in_stock  INTEGER,
    url       TEXT UNIQUE
);
CREATE INDEX IF NOT EXISTS idx_shop ON shop_products(shop);
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
                     "DROP TABLE IF EXISTS shop_products; DROP TABLE IF EXISTS reviews;"
                     "DROP TABLE IF EXISTS sahibinden_listings; DROP TABLE IF EXISTS sahibinden_searches;"
                     "DROP TABLE IF EXISTS search_index_listings;"
                     "DROP TABLE IF EXISTS handhelds;" + SCHEMA)
    today = date.today().isoformat()
    db.executemany(
        "INSERT INTO handhelds VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        [(r["akakce_id"], r["brand"], r["title"], r["category"], r["min_price_try"],
          r["extra_offers"] + 1, None, None, r["url"], r["image"], ",".join(sorted(r["sources"])), today)
         for r in rows.values()])

    review_rows = []
    for path in sorted(glob.glob("data/reviews_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            rec = json.loads(line)
            if rec["akakce_id"] not in rows:
                continue
            db.execute("UPDATE handhelds SET rating_avg=?, rating_count=? WHERE akakce_id=?",
                       (rec["rating_avg"], rec["rating_count"], rec["akakce_id"]))
            review_rows += [(rec["akakce_id"], rv["comment_id"], rv["added"], rv["rating"], rv["text"])
                            for rv in rec["reviews"]]
    db.executemany("INSERT INTO reviews (akakce_id, comment_id, added, rating, text) VALUES (?,?,?,?,?)",
                   review_rows)
    offers = []
    for path in sorted(glob.glob("data/offers_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            o = json.loads(line)
            if o.get("seller") and o["akakce_id"] in rows:  # skip "no offers" marker rows
                offers.append(tuple(o.get(c) for c in OFFER_COLS))
    db.executemany(f"INSERT INTO offers ({','.join(OFFER_COLS)}) VALUES ({','.join('?' * len(OFFER_COLS))})",
                   offers)
    shop_rows = []
    for path in sorted(glob.glob("data/shop_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            p = json.loads(line)
            shop_rows.append((p["shop"], p["platform"], p["title"], p.get("brand"), p.get("price_try"),
                              None if p.get("in_stock") is None else int(bool(p["in_stock"])), p["url"]))
    db.executemany("INSERT OR IGNORE INTO shop_products (shop, platform, title, brand, price_try, in_stock, url)"
                   " VALUES (?,?,?,?,?,?,?)", shop_rows)
    for path in sorted(glob.glob("data/sahibinden_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            s = json.loads(line)
            url = s.get("url")
            if url and "sahibinden.com" not in url:
                continue
            ilan = s.get("ilan_no")
            if not ilan and url:
                m = re.search(r"-(\d{8,})/(?:detay|detail)", url)
                ilan = m.group(1) if m else None
            if not url and not ilan:
                continue
            db.execute("INSERT OR IGNORE INTO sahibinden_listings (model, kind, source, ilan_no, title, price_try,"
                       " location, listed_date, condition, url) VALUES (?,?,?,?,?,?,?,?,?,?)",
                       (s.get("model"), s.get("kind", "ad"), s.get("source", "web-search"), ilan, s["title"],
                        s.get("price_try"), s.get("snippet_location"), s.get("listed_date"),
                        s.get("condition"), url))
    for path in sorted(glob.glob("data/search_*.jsonl")):
        for line in open(path, encoding="utf-8"):
            if not line.strip():
                continue
            s = json.loads(line)
            stock = s.get("in_stock")
            db.execute("INSERT OR IGNORE INTO search_index_listings (source, model, title, price_try, offer_count,"
                       " in_stock, seen_date, url) VALUES (?,?,?,?,?,?,?,?)",
                       (s.get("source"), s.get("model"), s.get("title") or "", s.get("price_try"),
                        s.get("offer_count"), None if stock is None else int(bool(stock)),
                        s.get("seen_date"), s.get("url")))
    db.executemany("INSERT INTO sahibinden_searches VALUES (?, ?, ?)",
                   [(m, SAHIBINDEN_CATEGORIES.get(m), SAHIBINDEN_SEARCH.format(q=quote_plus(m)))
                    for m in SAHIBINDEN_MODELS])
    db.commit()
    n_shop =db.execute("SELECT count(*) FROM shop_products").fetchone()[0]
    print(f"{len(rows)} unique handhelds, {len(offers)} offers, {len(review_rows)} reviews, "
          f"{n_shop} small-shop products -> handhelds.db")


if __name__ == "__main__":
    main()
