"""Parse, categorise, score and rank every listing in handhelds.db (offline except one FX lookup).

Usage (from handhelds/):  python3 -m analysis.run [--budget 25000] [--model "Retroid Pocket 6"]
                                                   [--import-tax 0.60] [--usd-try 50.37]
Writes to handhelds.db:
  listings_unified    every listing from every source: parsed fields, category, wanted/accessory/
                      clone flags, TRY price (or landed cost for USD-only rows), group median,
                      outlier flag, ML fair price, deal %, deal signal, percentile rank, priority
  ml_metrics          cross-validated price-model error, classifier accuracy per category, FX rate
  listings_clean      view: no clones, accessories, wanted posts or sold items
  priority_watchlist  view: the single priority system (1 RP6 8/12GB, 2 RP6, 3 AYN, 4 Retroid, 5 RG556/557)
  priority_deals      view: same, cheapest effective price first
  best_deals          view: clean listings that beat their fair price by more than the model's error
"""
import argparse
import json
import sqlite3
import urllib.request
import warnings
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from statistics import median

from . import algorithms as alg
from .catalog import CLONE, classify, tier
from .ml import classify_titles, predict_fair, train_price_model, train_title_classifier
from .parse import parse_title

FX_CACHE = Path("data/fx_usd_try.json")
# Shops selling only abroad. ships_to_tr: 1 yes, 0 no, None unknown.
# goretroid: reported as not shipping to Turkey (unverified; adjust if wrong).
SHIPPING = {"goretroid.com": 0, "ayntec.com": None}
# Second-hand marketplaces / forums: rows from them default to "used" when the title says nothing.
USED_SITES = ("donanimhaber", "technopat", "letgo", "sahibinden", "dolap", "gardrops")

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
    ("manual_listings", "user_report",
     "SELECT title, price_try, price_usd, condition, source, url, seen FROM manual_listings"),
]

SCHEMA = """
DROP VIEW IF EXISTS best_deals;
DROP VIEW IF EXISTS priority_deals;
DROP VIEW IF EXISTS listings_clean;
DROP TABLE IF EXISTS listings_unified;
DROP TABLE IF EXISTS ml_metrics;
CREATE TABLE listings_unified (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    priority INTEGER,            -- 1 = RP6 8GB/12GB, 2 = other RP6, 3 = AYN, 4 = Retroid, 5 = RG556/557, 9 = rest
    model TEXT, brand TEXT, category TEXT, category_source TEXT, tier TEXT,
    ram_gb INTEGER, storage_gb INTEGER, variant TEXT,
    condition TEXT, sold INTEGER, bundle INTEGER, is_accessory INTEGER, wanted INTEGER, is_clone INTEGER,
    title TEXT, price_try REAL, price_usd REAL,
    ships_to_tr INTEGER, landed_cost_try REAL,   -- USD-only rows: estimated price delivered to Turkey
    effective_price_try REAL,                    -- price_try, else landed_cost_try
    group_n INTEGER, group_median REAL, outlier INTEGER,
    fair_price REAL, deal_pct REAL, deal_signal TEXT, pct_rank REAL,
    source_table TEXT, source_kind TEXT, source TEXT, url TEXT, seen TEXT
);
CREATE INDEX idx_lu_prio ON listings_unified(priority, effective_price_try);
CREATE INDEX idx_lu_model ON listings_unified(model, variant, effective_price_try);
CREATE TABLE ml_metrics (name TEXT PRIMARY KEY, value TEXT, trained_at TEXT);
CREATE VIEW listings_clean AS
    SELECT * FROM listings_unified
    WHERE is_clone = 0 AND is_accessory = 0 AND wanted = 0 AND COALESCE(sold, 0) = 0
      AND model != 'Unknown';   -- neither catalogue nor classifier recognised it (books, toys...)
CREATE VIEW priority_watchlist AS
    SELECT priority, model, variant, condition, sold, price_try, price_usd, ships_to_tr, landed_cost_try,
           effective_price_try, ROUND(fair_price) AS fair_price, ROUND(100 * deal_pct, 1) AS deal_percent,
           deal_signal, source, title, url, seen
    FROM listings_unified
    WHERE priority < 9 AND is_accessory = 0 AND wanted = 0;
CREATE VIEW priority_deals AS
    SELECT * FROM priority_watchlist
    WHERE COALESCE(sold, 0) = 0
    ORDER BY priority, ships_to_tr = 0, effective_price_try IS NULL, effective_price_try;
"""


def usd_try(override=None):
    """USD->TRY from Frankfurter (ECB). Cached in data/fx_usd_try.json for offline runs."""
    if override:
        return override, "manual"
    try:
        with urllib.request.urlopen("https://api.frankfurter.dev/v1/latest?base=USD&symbols=TRY", timeout=15) as r:
            data = json.load(r)
        FX_CACHE.write_text(json.dumps(data))
        return float(data["rates"]["TRY"]), f"ECB {data['date']}"
    except Exception:
        if FX_CACHE.exists():
            data = json.loads(FX_CACHE.read_text())
            return float(data["rates"]["TRY"]), f"ECB {data['date']} (cached)"
        return None, "unavailable"


def priority_of(model, catalog_prio, ram_gb):
    if model == "Retroid Pocket 6" and ram_gb in (8, 12):
        return 1
    return catalog_prio or 9


def load_rows(db, fx, import_tax):
    rows = []
    for table, kind, sql in SOURCES:
        try:
            cur = db.execute(sql)
        except sqlite3.OperationalError:
            continue  # table not built yet
        for title, price, usd, cond_hint, source, url, seen in cur:
            if not title:
                continue
            if not cond_hint and any(site in (source or "") for site in USED_SITES):
                cond_hint = "used"
            p = parse_title(title, cond_hint)
            model, brand, cat, cprio = classify(title)
            price = price if (price or 0) >= 100 else None
            domain = (source or "").split("/")[0]
            landed = round(usd * fx * (1 + import_tax), 2) if usd and fx else None
            rows.append({**p, "title": title, "price_try": price, "price_usd": usd,
                         "ships_to_tr": SHIPPING.get(domain) if usd else None, "landed_cost_try": landed,
                         "effective_price_try": price or landed,
                         "model": model, "brand": brand, "category": cat,
                         "category_source": "catalog" if cat else None, "catalog_priority": cprio,
                         "source_table": table, "source_kind": kind, "source": source, "url": url, "seen": seen})
    seen_keys, out = set(), []
    for r in rows:  # algorithm: dedupe identical observations
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
    ap.add_argument("--import-tax", type=float, default=0.60,
                    help="share added on top of USD price for import duty + taxes (estimate, default 0.60)")
    ap.add_argument("--usd-try", type=float, help="override the USD->TRY rate")
    args = ap.parse_args()

    fx, fx_src = usd_try(args.usd_try)
    db = sqlite3.connect(args.db)
    rows = load_rows(db, fx, args.import_tax)

    # --- ML categorisation for titles the catalogue could not place
    labelled = [r for r in rows if r["category"] and not r["is_accessory"]]
    clf, clf_metrics = train_title_classifier([r["title"] for r in labelled], [r["category"] for r in labelled])
    unknown = [r for r in rows if not r["category"] and not r["is_accessory"]]
    for r, (label, conf) in zip(unknown, classify_titles(clf, [r["title"] for r in unknown])):
        if label:
            r["category"], r["category_source"] = label, f"ml:{conf:.2f}"
    for r in rows:
        r["model"] = r["model"] or (f"Unknown {r['category']}" if r["category"] else "Unknown")
        r["brand"] = r["brand"] or "Unknown"
        r["category"] = r["category"] or "Unknown"
        r["is_clone"] = int(r["category"] == CLONE)
        r["priority"] = priority_of(r["model"], r["catalog_priority"], r["ram_gb"])
        r["tier"] = tier(r["effective_price_try"])

    usable = lambda r: r["price_try"] and not r["is_accessory"] and not r["wanted"]

    # --- algorithm: group statistics + IQR outliers per (model, variant, condition)
    groups = defaultdict(list)
    for r in rows:
        if usable(r):
            groups[(r["model"], r["variant"], r["condition"])].append(r["price_try"])
    sorted_groups = {k: sorted(v) for k, v in groups.items()}
    for r in rows:
        g = sorted_groups.get((r["model"], r["variant"], r["condition"]), [])
        stats = alg.group_stats(g)
        lo, hi = alg.iqr_bounds(g)
        r["group_n"], r["group_median"] = stats["n"], stats["median"]
        p = r["effective_price_try"]
        r["outlier"] = int(bool(r["price_try"] and lo is not None and not (lo <= r["price_try"] <= hi)))
        r["pct_rank"] = alg.percentile_rank(g, p) if p and g else None

    # --- ML fair price on real handhelds (clones are ~70 % of rows and would dominate the fit)
    train = [r for r in rows if usable(r) and not r["outlier"] and not r["is_clone"]]
    metrics = {"usd_try": {"rate": fx, "source": fx_src}, "import_tax_assumed": args.import_tax,
               "title_classifier": clf_metrics}
    model, m = train_price_model(train)
    metrics.update(m)
    err = min(m["cv_median_abs_pct_error"].values())
    ml_fair = predict_fair(model, rows)

    # calibrate the ML price to each model's own observed level (empirical-Bayes shrinkage, k=3):
    # keeps the ML variant ordering (8GB < 12GB) but stops rare models collapsing onto one shared value
    ratios = defaultdict(list)
    for r, f in zip(rows, ml_fair):
        if usable(r) and not r["outlier"] and not r["is_clone"]:
            ratios[(r["model"], r["condition"])].append(r["price_try"] / f)
    k = 3
    for r, f in zip(rows, ml_fair):
        if r["is_clone"]:
            r["fair_price"] = r["group_median"]   # clones: plain median of comparable clones
        else:
            rs = ratios.get((r["model"], r["condition"]), [])
            factor = (len(rs) * median(rs) + k) / (len(rs) + k) if rs else 1.0
            r["fair_price"] = f * factor
        p = r["effective_price_try"]
        r["deal_pct"] = (r["fair_price"] - p) / r["fair_price"] if p and r["fair_price"] else None
        d = r["deal_pct"]
        r["deal_signal"] = (None if d is None else "good deal" if d >= err
                            else "overpriced" if d <= -err else "fair (within model error)")
        if d is not None and not r["is_clone"] and not ratios.get((r["model"], r["condition"])) \
                and not any(key[0] == r["model"] for key in ratios):
            r["deal_signal"] = "no TR price data for this model (estimate only)"
        if r["ships_to_tr"] == 0:
            r["deal_signal"] = "does not ship to TR"

    # priority_watchlist used to be a table built by build_db.py; drop whatever form it has now
    for name, typ in db.execute("SELECT name, type FROM sqlite_master WHERE name = 'priority_watchlist'").fetchall():
        db.execute(f"DROP {typ.upper()} {name}")
    db.executescript(SCHEMA)
    db.executescript(f"""
    CREATE VIEW best_deals AS
        SELECT priority, model, variant, condition, price_try, ROUND(fair_price) AS fair_price,
               ROUND(100 * deal_pct, 1) AS deal_percent, ROUND(100 * pct_rank) AS cheaper_than_pct_of_group,
               source, title, url
        FROM listings_clean
        WHERE price_try IS NOT NULL AND outlier = 0 AND group_n >= 3 AND COALESCE(ships_to_tr, 1) = 1
          AND deal_pct >= {max(0.10, err):.4f}      -- must beat the model's own error
        ORDER BY priority, deal_pct DESC;""")
    cols = ["priority", "model", "brand", "category", "category_source", "tier", "ram_gb", "storage_gb", "variant",
            "condition", "sold", "bundle", "is_accessory", "wanted", "is_clone", "title", "price_try", "price_usd",
            "ships_to_tr", "landed_cost_try", "effective_price_try", "group_n", "group_median", "outlier",
            "fair_price", "deal_pct", "deal_signal", "pct_rank", "source_table", "source_kind", "source", "url", "seen"]
    db.executemany(f"INSERT INTO listings_unified ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                   [tuple(int(r[c]) if isinstance(r.get(c), bool) else r.get(c) for c in cols) for r in rows])
    now = datetime.now(timezone.utc).isoformat()
    db.executemany("INSERT INTO ml_metrics VALUES (?,?,?)", [(k_, json.dumps(v), now) for k_, v in metrics.items()])
    db.commit()

    n_clean = db.execute("SELECT count(*) FROM listings_clean").fetchone()[0]
    print(f"listings_unified: {len(rows)} rows ({n_clean} clean), price model trained on {len(train)}")
    print("price model:", json.dumps({"chosen": metrics["chosen"], "cv_err": metrics["cv_median_abs_pct_error"]}))
    if clf_metrics:
        print(f"classifier: accuracy {clf_metrics['accuracy']}, balanced {clf_metrics['balanced_accuracy']}")
    print(f"USD->TRY {fx} ({fx_src}), import tax assumed {args.import_tax:.0%}")

    if args.budget:   # binary-search view
        pool = sorted((r for r in rows if r["effective_price_try"] and not r["is_accessory"] and not r["sold"]
                       and not r["wanted"] and (not args.model or r["model"] == args.model)),
                      key=lambda r: r["effective_price_try"])
        hits = alg.under_budget(pool, args.budget, key=lambda r: r["effective_price_try"])
        print(f"\n{len(hits)} listings <= {args.budget:,.0f} TL" + (f" for {args.model}" if args.model else ""))
        for r in sorted(hits, key=lambda r: (r["priority"], r["effective_price_try"]))[:25]:
            print(f"  p{r['priority']} {r['effective_price_try']:>9,.0f} TL  {r['model']:<22} "
                  f"{r['variant'] or '':<11} {r['condition'] or '':<5} {r['source']}")
    print("\nPriority deals:")
    for row in db.execute("SELECT priority, model, variant, condition, effective_price_try, fair_price, "
                          "deal_percent, deal_signal, source FROM priority_deals LIMIT 30"):
        print("  ", row)


if __name__ == "__main__":
    main()
