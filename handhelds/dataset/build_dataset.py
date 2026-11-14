"""Package handhelds.db as a versioned dataset: Parquet + CSV, data dictionary, dataset card.

Usage (from handhelds/):  python3 build_db.py && python3 -m analysis.run && python3 -m dataset.build_dataset
Output: dataset/latest/*.parquet, *.csv, manifest.json, DATASET.md (regenerated each time).
"""
import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(__file__).resolve().parent / "latest"

# table/view in handhelds.db -> (file name, description)
TABLES = {
    "listings_unified": ("listings", "Every listing from every source after parsing, categorising, dedupe and ML scoring."),
    "listings_clean": ("listings_clean", "Real handhelds only: no clones, accessories, want-to-buy posts or sold items."),
    "priority_watchlist": ("priority_watchlist", "RP6 8/12GB, AYN, Retroid, RG556/557 rows (priority < 9)."),
    "offers": ("akakce_offers", "Raw per-seller offers from Akakçe product pages."),
    "handhelds": ("akakce_products", "Raw Akakçe handheld catalogue (lowest price, offer count)."),
    "used_listings": ("forum_used_listings", "DonanımHaber second-hand section (title, price, sold flag)."),
    "reviews": ("reviews", "Akakçe product comments (text, stars, date). No reviewer names."),
    "ml_metrics": ("ml_metrics", "Cross-validated model metrics, FX rate and assumptions of the last analysis run."),
    "scraper_observations": ("price_history", "Every timestamped observation from the scrapers, the Scrapy crawler and "
                             "Common Crawl archives (scraped_at = capture time): the price history for ML."),
}

COLUMN_DOCS = {
    "priority": "1 = Retroid Pocket 6 8/12GB, 2 = other RP6, 3 = AYN, 4 = other Retroid, 5 = Anbernic RG556/557, 9 = rest",
    "model": "Canonical model from analysis/catalog.py (or 'Unknown <category>' from the ML classifier)",
    "category": "PC handheld (x86) / Android handheld / Linux retro handheld / Nintendo / Streaming / Sony legacy / Retro clone / Phone controller",
    "category_source": "'catalog' (regex) or 'ml:<confidence>' (title classifier)",
    "ram_gb": "RAM parsed from the title (NULL if not stated)",
    "storage_gb": "Storage parsed from the title, GB (1TB = 1024)",
    "variant": "RAM/storage label, e.g. 8GB/128GB ('?' for an unknown part)",
    "condition": "new / used / NULL (title wins over the source default)",
    "sold": "1 if the listing says SATILDI / ALINDI / KAPANDI / sold out",
    "bundle": "1 if the title mentions a bundle (+ game, set, hediye…)",
    "is_accessory": "1 for cases, grips, chargers, games, and non-handhelds that matched a keyword",
    "wanted": "1 for want-to-buy posts (ALINIK / arıyorum)",
    "is_clone": "1 for no-name retro clones (400-in-1 etc.)",
    "price_try": "Asking price in TRY (NULL if missing or a placeholder under 100 TL)",
    "price_usd": "Asking price in USD for foreign stores",
    "ships_to_tr": "1 domestic listing, 0 store does not ship to Turkey, NULL unknown",
    "landed_cost_try": "USD price × ECB USD/TRY × (1 + assumed import tax), see ml_metrics",
    "effective_price_try": "price_try, else landed_cost_try",
    "group_n": "Listings in the same (model, variant, condition) group",
    "group_median": "Median price of that group",
    "outlier": "1 if outside the group's Tukey IQR fences",
    "fair_price": "Out-of-fold ML fair price, calibrated per model (NULL when the model has no TL data)",
    "deal_pct": "(fair_price - effective_price) / fair_price",
    "deal_signal": "good deal / fair (within model error) / overpriced / no TR price data / does not ship to TR",
    "pct_rank": "Share of the group priced below this listing (binary search)",
    "source_table": "Which raw table the row came from",
    "source_kind": "shop / classified / aggregator / search_snippet / scraper / user_report",
    "source": "Canonical source, e.g. 'Hepsiburada/Mini More Türkiye', 'donanimhaber', 'idefix'",
    "seen": "When the row was scraped / listed / seen in a snippet",
}


def sha256(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    db_path = ROOT / "handhelds.db"
    src = sqlite3.connect(db_path)
    existing = {n for (n,) in src.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view')")}
    con = duckdb.connect()
    con.execute("INSTALL sqlite; LOAD sqlite;")
    con.execute(f"ATTACH '{db_path}' AS h (TYPE sqlite, READ_ONLY)")
    manifest = {"built_at": datetime.now(timezone.utc).isoformat(), "source_db": "handhelds.db", "files": {}}
    schemas = {}
    for table, (name, desc) in TABLES.items():
        if table not in existing:
            continue
        for ext, opts in (("parquet", "FORMAT parquet, COMPRESSION zstd"), ("csv", "HEADER, DELIMITER ','")):
            f = OUT / f"{name}.{ext}"
            con.execute(f"COPY (SELECT * FROM h.{table}) TO '{f}' ({opts})")
            manifest["files"][f.name] = {"rows": None, "bytes": f.stat().st_size, "sha256": sha256(f)}
        rows = con.execute(f"SELECT count(*) FROM h.{table}").fetchone()[0]
        for ext in ("parquet", "csv"):
            manifest["files"][f"{name}.{ext}"]["rows"] = rows
        schemas[name] = (desc, rows, con.execute(f"DESCRIBE SELECT * FROM h.{table}").fetchall())
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    write_card(schemas, manifest)
    print(f"dataset -> {OUT}  ({len(schemas)} tables)")
    for n, (_, rows, _) in schemas.items():
        print(f"  {n:<22} {rows:>6} rows")


def write_card(schemas, manifest):
    lines = [
        "# Turkish Handheld Console Prices: dataset card",
        "",
        f"Built {manifest['built_at'][:16]} UTC from `handhelds.db`. Regenerate with "
        "`python3 -m dataset.build_dataset`.",
        "",
        "## What it is",
        "Asking prices for handheld game consoles in Turkey (new and used), collected for **personal research**",
        "(finding fair prices and deals, e.g. Retroid Pocket 6 8/12GB). One snapshot plus history where available.",
        "",
        "## Sources and how they were collected",
        "| Source | Method | Notes |",
        "|---|---|---|",
        "| Akakçe | polite HTTP scraper (robots.txt, 1.5–4 s/req) | per-seller offers for ~500 shops incl. big marketplaces |",
        "| idefix | brand pages + product JSON-LD | category pages are robots-disallowed, so brand pages |",
        "| DonanımHaber forum | Scrapy / TS scraper, for-sale section | used listings; titles + prices only, no usernames |",
        "| R10.net | Scrapy, second-hand board | low yield, keyword-filtered |",
        "| Small shops | WooCommerce/Shopify APIs, sitemaps | gaming.gen.tr etc. |",
        "| Common Crawl | URL Index via DuckDB; archived content only for hosts open to us | history; see indexers/commoncrawl.py |",
        "| Search engines | WebSearch snippets | prices may be stale or mis-attributed |",
        "| sahibinden | user screenshots only | site blocks bots; no seller data stored |",
        "| Official stores | goretroid, ayntec, anbernic (USD) | landed cost estimated, import tax assumed |",
        "| Wikidata | SPARQL (CC0) | specs / release dates (data/wikidata.json) |",
        "",
        "## Privacy",
        "No usernames, seller person names, phone numbers or avatars are stored. Shop/marketplace seller names are kept.",
        "",
        "## Licence / use",
        "Compiled for personal, non-commercial research. Listing titles and prices belong to their sources; check each",
        "source's terms before republishing. Wikidata-derived fields are CC0. Common Crawl data is used under its Terms",
        "of Use (third-party content stays under the original site's terms).",
        "",
        "## Known biases and caveats",
        "- New-market coverage is good (Akakçe aggregates most shops); used-market coverage is partial (sahibinden is",
        "  only in via screenshots).",
        "- Prices are asking prices, not transaction prices (forum SATILDI rows are the closest to real sales).",
        "- Search-snippet prices can be stale or attached to the wrong product.",
        "- The ML fair price has ~15 % median error (see ml_metrics); treat smaller deal % as noise.",
        "- Retro clones dominate raw counts; use `listings_clean` for real devices.",
        "",
        "## Quick start",
        "```python",
        "import duckdb",
        "duckdb.sql(\"SELECT model, variant, condition, median(price_try) FROM 'dataset/latest/listings_clean.parquet' \"",
        "           \"GROUP BY ALL ORDER BY 1\").show()",
        "```",
        "",
        "## Files",
        "| File | Rows | Description |",
        "|---|---|---|",
    ]
    for name, (desc, rows, _) in schemas.items():
        lines.append(f"| `{name}.parquet` / `.csv` | {rows} | {desc} |")
    lines += ["", "## Data dictionary (`listings` / `listings_clean`)", "", "| Column | Type | Meaning |", "|---|---|---|"]
    for col, typ, *_ in schemas.get("listings", ("", 0, []))[2]:
        lines.append(f"| `{col}` | {typ} | {COLUMN_DOCS.get(col, '')} |")
    (OUT / "DATASET.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
