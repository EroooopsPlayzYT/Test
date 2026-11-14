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
    comment_id INTEGER UNIQUE,  -- Akakce shows the same comment on related product pages
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
    price_usd   REAL,           -- official store reference prices (goretroid, ayntec)
    offer_count INTEGER,
    in_stock    INTEGER,
    seen_date   TEXT,
    url         TEXT,           -- found via web search; the site itself was not fetched
    UNIQUE (url, title)         -- one page can list several variants
);
CREATE TABLE IF NOT EXISTS used_listings (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    source     TEXT NOT NULL,   -- donanimhaber (forum second-hand section)
    listing_id TEXT,
    title      TEXT NOT NULL,
    price_try  REAL,
    sold       INTEGER,         -- 1 = SATILDI
    condition  TEXT,
    url        TEXT UNIQUE,     -- no usernames or contact details stored
    scraped_at TEXT
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


PRIORITY = [  # (model label, regex on lowercased title); first match wins, most specific first
    # --- top: Retroid Pocket 6 (8GB / 12GB) ---
    ("Retroid Pocket 6", r"retroid\s*pocket\s*6|\brp\s*6\b"),
    # --- Anbernic RG556 / RG557 ---
    ("Anbernic RG557", r"rg\s*-?\s*557"),
    ("Anbernic RG556", r"rg\s*-?\s*556"),
    # --- AYN ---
    ("AYN Odin 3", r"odin\s*3"),
    ("AYN Odin 2 Portal", r"odin\s*2\s*portal"),
    ("AYN Odin 2 Mini", r"odin\s*2\s*mini"),
    ("AYN Odin 2 Max", r"odin\s*2\s*max"),
    ("AYN Odin 2", r"odin\s*2"),
    ("AYN Odin Lite", r"odin\s*lite"),
    ("AYN Thor", r"\bayn\b.*\bthor\b|\bthor\b.*\bayn\b"),
    ("AYN Loki", r"\bayn\b.*\bloki\b|\bloki\b\s*(max|zero|mini)"),
    ("AYN Odin", r"\bodin\b"),
    ("AYN (other)", r"\bayn\b|ayntec"),
    # --- Retroid ---
    ("Retroid Pocket 5", r"retroid\s*pocket\s*5|\brp\s*5\b"),
    ("Retroid Pocket Flip 2", r"retroid\s*pocket\s*flip\s*2|\brp\s*flip\s*2\b"),
    ("Retroid Pocket Flip", r"retroid\s*pocket\s*flip"),
    ("Retroid Pocket Mini V2", r"retroid\s*pocket\s*mini\s*v\s*2"),
    ("Retroid Pocket Mini", r"retroid\s*pocket\s*mini"),
    ("Retroid Pocket Classic", r"retroid\s*pocket\s*classic"),
    ("Retroid Pocket 4 Pro", r"retroid\s*pocket\s*4\s*pro|\brp\s*4\s*pro\b"),
    ("Retroid Pocket 4", r"retroid\s*pocket\s*4|\brp\s*4\b"),
    ("Retroid Pocket 3+", r"retroid\s*pocket\s*3\s*(\+|plus)"),
    ("Retroid Pocket 3", r"retroid\s*pocket\s*3"),
    ("Retroid Pocket 2S", r"retroid\s*pocket\s*2\s*s"),
    ("Retroid (other)", r"retroid"),
]
PRIORITY_RANK = {m: i for i, (m, _) in enumerate(PRIORITY)}
NOT_DEVICE = re.compile(r"kılıf|kilif|çanta|canta|koruyucu|temperli|\bcam\b|grip|stand|şarj|sarj|kablo|"
                        r"thumb|analog|sticker|kaplama|case|skin|film|dock|silikon", re.I)


def variant(title):
    """'8GB/128GB' style RAM/storage from a title, as far as it can be told."""
    t = title.lower()
    ram = (re.search(r"(\d{1,2})\s*gb\s*(?:ram|lpddr|/\s*\d)", t) or re.search(r"ram\s*:?\s*(\d{1,2})\s*gb", t)
           or re.search(r"(?<![\d.])(\d{1,2})\s*/\s*(?:\d{3,4}\b|\d\s*tb)", t) or re.search(r"(?<![\d.])(\d{1,2})\s*gb\s*\+", t))
    rom = (re.search(r"(?<![\d.])\d{1,2}\s*/\s*(\d{3,4})\b", t) or re.search(r"(\d{3,4})\s*gb(?!\s*ram)", t)
           or re.search(r"(\d)\s*tb", t))
    r = ram.group(1) if ram else None
    m = rom.group(1) if rom else None
    if m:
        m = f"{m}TB" if len(m) == 1 else f"{m}GB"
    return f"{r + 'GB' if r else '?'}/{m or '?'}" if (r or m) else None


def load_wikidata(db, path="data/wikidata.json"):
    """Handheld specs from Wikidata (CC0): release date, manufacturer, CPU, RAM."""
    import os
    db.execute("DROP TABLE IF EXISTS catalog_wikidata")
    db.execute("""CREATE TABLE catalog_wikidata (name TEXT, release TEXT, manufacturer TEXT, cpu TEXT, ram TEXT,
                  launch_price TEXT, wikidata TEXT PRIMARY KEY)""")
    if os.path.exists(path):
        rows = json.load(open(path, encoding="utf-8"))
        db.executemany("INSERT OR IGNORE INTO catalog_wikidata VALUES (?,?,?,?,?,?,?)",
                       [(r["name"], r["release"], r["manufacturer"], r["cpu"], r["ram"], r["launch_price"],
                         r["wikidata"]) for r in rows])
        print(f"catalog_wikidata: {len(rows)} models")


def load_manual_listings(db, path="data/manual_listings.jsonl"):
    """Listings reported by the user (or another agent) that the scrapers did not find."""
    import os
    db.execute("DROP TABLE IF EXISTS manual_listings")
    db.execute("""CREATE TABLE manual_listings (title TEXT NOT NULL, price_try REAL, price_usd REAL, condition TEXT,
                  location TEXT, source TEXT, url TEXT, seen TEXT, note TEXT)""")
    if not os.path.exists(path):
        return
    rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    db.executemany("INSERT INTO manual_listings VALUES (?,?,?,?,?,?,?,?,?)",
                   [(r["title"], r.get("price_try"), r.get("price_usd"), r.get("condition"), r.get("location"),
                     r.get("source", "user-report"), r.get("url"), r.get("seen"), r.get("note")) for r in rows])
    print(f"manual_listings: {len(rows)} rows")


def load_scraper_observations(db, patterns=("scraper-ts/out/*.db", "crawler/out/crawl.db",
                                             "crawler/out/commoncrawl.db")):
    """Copy observations (every run, timestamped) from the TS scraper, the Scrapy crawler and the
    Common Crawl history (whose scraped_at is the archive capture time)."""
    db.execute("DROP TABLE IF EXISTS scraper_observations")
    db.execute("""CREATE TABLE scraper_observations (source TEXT, product_id TEXT, title TEXT, price_try REAL,
                  in_stock INTEGER, seller TEXT, condition TEXT, url TEXT, query TEXT, scraped_at TEXT)""")
    rows = []
    for path in sorted(p for pat in patterns for p in glob.glob(pat)):
        if path.endswith(("test.db", "t2.db", "t3.db")):
            continue  # throwaway test runs
        src = sqlite3.connect(path)
        try:
            rows += src.execute("SELECT source, product_id, title, price_try, in_stock, seller, condition, url, "
                                "query, scraped_at FROM observations").fetchall()
        except sqlite3.OperationalError:
            pass
        src.close()
    rows = list(dict.fromkeys(rows))  # same observation copied into two dbs
    db.executemany("INSERT INTO scraper_observations VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
    print(f"scraper_observations: {len(rows)} rows")


def build_priority(db):
    db.executescript("""DROP TABLE IF EXISTS priority_watchlist;
    CREATE TABLE priority_watchlist (
        priority INTEGER, model TEXT, variant TEXT, title TEXT, price_try REAL, price_usd REAL, condition TEXT,
        source_table TEXT, source TEXT, url TEXT, seen TEXT);""")
    queries = [
        ("handhelds", "SELECT title, min_price_try, NULL, 'new', 'akakce', url, scraped_on FROM handhelds"),
        ("offers", "SELECT h.title || ' [' || COALESCE(o.listing_title,'') || ']', o.price_try, NULL, 'new', "
                   "COALESCE(o.marketplace || '/', '') || o.seller, h.url, h.scraped_on "
                   "FROM offers o JOIN handhelds h USING (akakce_id)"),
        ("shop_products", "SELECT title, price_try, NULL, 'new', shop, url, NULL FROM shop_products"),
        ("search_index_listings", "SELECT title, price_try, price_usd, NULL, source, url, seen_date FROM search_index_listings"),
        ("used_listings", "SELECT title, price_try, NULL, 'used' || CASE WHEN sold THEN ' (sold)' ELSE '' END, source, url, scraped_at FROM used_listings"),
        ("sahibinden_listings", "SELECT title, price_try, NULL, condition, 'sahibinden', COALESCE(url, 'ilan ' || ilan_no), listed_date FROM sahibinden_listings WHERE kind='ad'"),
    ]
    rows = []
    for table, q in queries:
        for title, price, usd, cond, source, url, seen in db.execute(q):
            low = (title or "").lower()
            model = next((m for m, rx in PRIORITY if re.search(rx, low)), None)
            if not model or NOT_DEVICE.search(low):
                continue
            v = variant(title)
            # 1 = RP6 8GB/12GB (what you're hunting), 2 = other RP6, 3+ = other AYN/Retroid by list order
            prio = 1 if model == "Retroid Pocket 6" and v and v.split("/")[0] in ("8GB", "12GB") \
                else 2 if model == "Retroid Pocket 6" else 3 + PRIORITY_RANK[model]
            rows.append((prio, model, v, title, price, usd, cond, table, source, url, seen))
    db.executemany("INSERT INTO priority_watchlist VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    db.execute("CREATE INDEX idx_watch ON priority_watchlist(priority, price_try)")
    print(f"priority_watchlist: {len(rows)} rows")


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
                     "DROP TABLE IF EXISTS search_index_listings; DROP TABLE IF EXISTS used_listings;"
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
    db.executemany("INSERT OR IGNORE INTO reviews (akakce_id, comment_id, added, rating, text) VALUES (?,?,?,?,?)",
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
            title = s.get("title") or ""
            if s.get("variant") and s["variant"] not in title:
                title = f"{title} [{s['variant']}]"
            db.execute("INSERT OR IGNORE INTO search_index_listings (source, model, title, price_try, price_usd,"
                       " offer_count, in_stock, seen_date, url) VALUES (?,?,?,?,?,?,?,?,?)",
                       (s.get("source"), s.get("model"), title, s.get("price_try"), s.get("price_usd"),
                        s.get("offer_count"), None if stock is None else int(bool(stock)),
                        s.get("seen_date"), s.get("url")))
    for path in sorted(glob.glob("data/donanimhaber*.jsonl")):
        for line in open(path, encoding="utf-8"):
            u = json.loads(line)
            db.execute("INSERT OR IGNORE INTO used_listings (source, listing_id, title, price_try, sold, condition,"
                       " url, scraped_at) VALUES (?,?,?,?,?,?,?,?)",
                       (u["source"], u["id"], u["title"],
                        u["priceTry"] if (u.get("priceTry") or 0) >= 100 else None,  # 1 TL = "make an offer"
                        None if u.get("inStock") is None else int(u["inStock"] is False),
                        u.get("condition"), u["url"], u.get("scrapedAt")))
    db.executemany("INSERT INTO sahibinden_searches VALUES (?, ?, ?)",
                   [(m, SAHIBINDEN_CATEGORIES.get(m), SAHIBINDEN_SEARCH.format(q=quote_plus(m)))
                    for m in SAHIBINDEN_MODELS])
    load_scraper_observations(db)
    load_manual_listings(db)
    load_wikidata(db)
    # priority_watchlist is now a view built by analysis/run.py (one priority system)
    db.commit()
    n_shop =db.execute("SELECT count(*) FROM shop_products").fetchone()[0]
    print(f"{len(rows)} unique handhelds, {len(offers)} offers, {len(review_rows)} reviews, "
          f"{n_shop} small-shop products -> handhelds.db")


if __name__ == "__main__":
    main()
