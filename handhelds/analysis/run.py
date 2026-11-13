"""Parse, categorise, score and rank every listing in handhelds.db (offline, no requests).

Usage (from handhelds/):  python3 -m analysis.run [--budget 25000] [--model "Retroid Pocket 6"]
Writes to handhelds.db:
  listings_unified  one row per listing from every source, with parsed fields, category,
                    group median, outlier flag, ML fair price, deal %, percentile rank, priority
  ml_metrics        how well the price model did (cross-validated)
  best_deals (view) clean, unsold listings at least 10 % under their fair price, priority first
"""
import argparse
import warnings
import json
import sqlite3
from collections import defaultdict
from datetime import datetime, timezone

from . import algorithms as alg
from .catalog import classify, tier
from .ml import classify_titles, predict_fair, train_price_model, train_title_classifier
from .parse import parse_title

SOURCES = [  # (source_table, source_kind, SQL -> title, price_try, price_usd, condition_hint, source, url, seen)
    ("handhelds", "aggregator",
     "SELECT title, min_price_try, NULL, 'new', 'akakce', url, scraped_on FROM handhelds"),
    ("offers", "shop",
     "SELECT h.title, o.price_try, NULL, 'new', COALESCE(o.marketplace || '/', '') || o.seller, h.url, h.scraped_on "
     "FROM offers o JOIN handhelds h USING (akakce_id)"),
    ("shop_products", "shop",
     "SELECT title, price_try, NULL, 'new', shop, url, NULL FROM shop_products"),
    ("search_index_listings", "search_snippet",
     "SELECT title, price_try, price_usd, NULL, source, url, seen_date FROM search_index_listings"),
    ("used_listings", "classified",
     "SELECT title, price_try, NULL, 'used' || CASE WHEN sold THEN ' sold' ELSE '' END, source, url, scraped_at "
     "FROM used_listings"),
    ("scraper_observations", "scraper",
     "SELECT title, price_try, NULL, COALESCE(condition, '') || CASE WHEN in_stock = 0 THEN ' sold' ELSE '' END, "
     "source || COALESCE('/' || seller, ''), url, scraped_at FROM scraper_observations "
     "WHERE source NOT IN ('donanimhaber')"),   # forum rows already come via used_listings
    ("sahibinden_listings", "classified",
     "SELECT title, price_try, NULL, COALESCE(condition, 'used'), 'sahibinden', COALESCE(url, 'ilan ' || ilan_no), "
     "listed_date FROM sahibinden_listings WHERE kind = 'ad'"),
]

SCHEMA = """
DROP VIEW IF EXISTS best_deals;
DROP VIEW IF EXISTS priority_deals;
DROP TABLE IF EXISTS listings_unified;
DROP TABLE IF EXISTS ml_metrics;
CREATE TABLE listings_unified (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    priority INTEGER,            -- 1 = RP6 8GB/12GB, 2 = other RP6, 3 = AYN, 4 = Retroid, 5 = RG556/557, 9 = rest
    model TEXT, brand TEXT, category TEXT, category_source TEXT, tier TEXT,
    ram_gb INTEGER, storage_gb INTEGER, variant TEXT,
    condition TEXT, sold INTEGER, bundle INTEGER, is_accessory INTEGER,
    title TEXT, price_try REAL, price_usd REAL,
    group_n INTEGER, group_median REAL, outlier INTEGER,
    fair_price REAL, deal_pct REAL, pct_rank REAL,
    source_table TEXT, source_kind TEXT, source TEXT, url TEXT, seen TEXT
);
CREATE INDEX idx_lu_prio ON listings_unified(priority, deal_pct);
CREATE INDEX idx_lu_model ON listings_unified(model, variant, price_try);
CREATE TABLE ml_metrics (name TEXT PRIMARY KEY, value TEXT, trained_at TEXT);
CREATE VIEW best_deals AS
    SELECT priority, model, variant, condition, price_try, ROUND(fair_price) AS fair_price,
           ROUND(100 * deal_pct, 1) AS deal_percent, ROUND(100 * pct_rank) AS cheaper_than_pct_of_group,
           source, title, url
    FROM listings_unified
    WHERE is_accessory = 0 AND outlier = 0 AND COALESCE(sold, 0) = 0
      AND price_try IS NOT NULL AND deal_pct >= 0.10
      AND group_n >= 3            -- need a few comparables before calling something a deal
    ORDER BY priority, deal_pct DESC;
DROP VIEW IF EXISTS priority_deals;
CREATE VIEW priority_deals AS          -- only RP6 / AYN / Retroid / RG556-557, cheapest first
    SELECT priority, model, variant, condition, sold, price_try, price_usd, ROUND(fair_price) AS fair_price,
           ROUND(100 * deal_pct, 1) AS deal_percent, source, title, url
    FROM listings_unified
    WHERE priority < 9 AND is_accessory = 0
    ORDER BY priority, price_try IS NULL, price_try;
"""


def priority_of(model, catalog_prio, ram_gb):
    if model == "Retroid Pocket 6" and ram_gb in (8, 12):
        return 1
    return catalog_prio or 9


def load_rows(db):
    rows = []
    for table, kind, sql in SOURCES:
        try:
            cur = db.execute(sql)
        except sqlite3.OperationalError:
            continue  # table not built yet
        for title, price, usd, cond_hint, source, url, seen in cur:
            if not title:
                continue
            p = parse_title(title, cond_hint)
            model, brand, cat, cprio = classify(title)
            rows.append({**p, "title": title, "price_try": price if (price or 0) >= 100 else None,
                         "price_usd": usd, "model": model, "brand": brand, "category": cat,
                         "category_source": "catalog" if cat else None, "catalog_priority": cprio,
                         "source_table": table, "source_kind": kind, "source": source, "url": url, "seen": seen})
    # algorithm: dedupe identical observations
    seen_keys, out = set(), []
    for r in rows:
        k = alg.dedupe_key(r) + (r["url"],)
        if k not in seen_keys:
            seen_keys.add(k)
            out.append(r)
    return out


def main():
    warnings.filterwarnings("ignore")
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="handhelds.db")
    ap.add_argument("--budget", type=float, help="show listings under this TRY budget (binary search)")
    ap.add_argument("--model", help="restrict the budget view to one model, e.g. 'Retroid Pocket 6'")
    args = ap.parse_args()

    db = sqlite3.connect(args.db)
    rows = load_rows(db)

    # ML categorisation for titles the catalogue could not place
    labelled = [r for r in rows if r["category"] and not r["is_accessory"]]
    clf, clf_acc = train_title_classifier([r["title"] for r in labelled], [r["category"] for r in labelled])
    unknown = [r for r in rows if not r["category"] and not r["is_accessory"]]
    for r, (label, conf) in zip(unknown, classify_titles(clf, [r["title"] for r in unknown])):
        if label:
            r["category"], r["category_source"] = label, f"ml:{conf:.2f}"
    for r in rows:
        r["model"] = r["model"] or (f"Unknown {r['category']}" if r["category"] else "Unknown")
        r["brand"] = r["brand"] or "Unknown"
        r["category"] = r["category"] or "Unknown"
        r["priority"] = priority_of(r["model"], r["catalog_priority"], r["ram_gb"])
        r["tier"] = tier(r["price_try"])

    # algorithm: group statistics + IQR outliers per (model, variant, condition)
    groups = defaultdict(list)
    for r in rows:
        if r["price_try"] and not r["is_accessory"]:
            groups[(r["model"], r["variant"], r["condition"])].append(r["price_try"])
    sorted_groups = {k: sorted(v) for k, v in groups.items()}
    for r in rows:
        g = sorted_groups.get((r["model"], r["variant"], r["condition"]), [])
        stats = alg.group_stats(g)
        lo, hi = alg.iqr_bounds(g)
        r["group_n"], r["group_median"] = stats["n"], stats["median"]
        r["outlier"] = int(bool(r["price_try"] and lo is not None and not (lo <= r["price_try"] <= hi)))
        r["pct_rank"] = alg.percentile_rank(g, r["price_try"]) if r["price_try"] and g else None

    # ML fair price
    # Unknown models train too (as "Unknown <category>"), so cheap clones get clone-level fair prices.
    train = [r for r in rows if r["price_try"] and not r["is_accessory"] and not r["outlier"]]
    metrics = {"title_classifier_cv_accuracy": clf_acc}
    if len(train) >= 20:
        model, m = train_price_model(train)
        metrics.update(m)
        fair = predict_fair(model, rows)
        for r, f in zip(rows, fair):
            r["fair_price"] = f
            r["deal_pct"] = (f - r["price_try"]) / f if r["price_try"] else None
    else:
        for r in rows:
            r["fair_price"] = r["group_median"]
            r["deal_pct"] = ((r["group_median"] - r["price_try"]) / r["group_median"]
                             if r["price_try"] and r["group_median"] else None)

    db.executescript(SCHEMA)
    cols = ["priority", "model", "brand", "category", "category_source", "tier", "ram_gb", "storage_gb", "variant",
            "condition", "sold", "bundle", "is_accessory", "title", "price_try", "price_usd", "group_n",
            "group_median", "outlier", "fair_price", "deal_pct", "pct_rank", "source_table", "source_kind",
            "source", "url", "seen"]
    db.executemany(f"INSERT INTO listings_unified ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                   [tuple(int(r[c]) if isinstance(r.get(c), bool) else r.get(c) for c in cols) for r in rows])
    now = datetime.now(timezone.utc).isoformat()
    db.executemany("INSERT INTO ml_metrics VALUES (?,?,?)", [(k, json.dumps(v), now) for k, v in metrics.items()])
    db.commit()

    print(f"listings_unified: {len(rows)} rows, trained on {len(train)}")
    print("price model:", json.dumps({k: metrics[k] for k in ("chosen", "cv_median_abs_pct_error") if k in metrics}))
    print("title classifier CV accuracy:", clf_acc)

    # binary-search views
    if args.budget:
        pool = sorted((r for r in rows if r["price_try"] and not r["is_accessory"] and not r["sold"]
                       and (not args.model or r["model"] == args.model)), key=lambda r: r["price_try"])
        hits = alg.under_budget(pool, args.budget)
        print(f"\n{len(hits)} listings <= {args.budget:,.0f} TL"
              + (f" for {args.model}" if args.model else "") + " (cheapest first):")
        for r in sorted(hits, key=lambda r: (r["priority"], r["price_try"]))[:25]:
            print(f"  p{r['priority']} {r['price_try']:>9,.0f} TL  {r['model']:<22} {r['variant'] or '':<11} "
                  f"{r['condition'] or '':<5} {r['source']}")
    print("\nPriority watchlist (RP6 8/12GB first):")
    for row in db.execute("SELECT priority, model, variant, condition, price_try, price_usd, source "
                          "FROM priority_deals LIMIT 30"):
        print("  ", row)
    print("\nTop deals (priority first):")
    for row in db.execute("SELECT priority, model, variant, price_try, fair_price, deal_percent, source "
                          "FROM best_deals LIMIT 15"):
        print("  ", row)


if __name__ == "__main__":
    main()
