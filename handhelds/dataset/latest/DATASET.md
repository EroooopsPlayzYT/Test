# Turkish Handheld Console Prices: dataset card

Built 2026-11-14T09:24 UTC from `handhelds.db`. Regenerate with `python3 -m dataset.build_dataset`.

## What it is
Asking prices for handheld game consoles in Turkey (new and used), collected for **personal research**
(finding fair prices and deals, e.g. Retroid Pocket 6 8/12GB). One snapshot plus history where available.

## Sources and how they were collected
| Source | Method | Notes |
|---|---|---|
| Akakçe | polite HTTP scraper (robots.txt, 1.5–4 s/req) | per-seller offers for ~500 shops incl. big marketplaces |
| idefix | brand pages + product JSON-LD | category pages are robots-disallowed, so brand pages |
| DonanımHaber forum | Scrapy / TS scraper, for-sale section | used listings; titles + prices only, no usernames |
| R10.net | Scrapy, second-hand board | low yield, keyword-filtered |
| Small shops | WooCommerce/Shopify APIs, sitemaps | gaming.gen.tr etc. |
| Common Crawl | URL Index via DuckDB; archived content only for hosts open to us | history; see indexers/commoncrawl.py |
| Search engines | WebSearch snippets | prices may be stale or mis-attributed |
| sahibinden | user screenshots only | site blocks bots; no seller data stored |
| Official stores | goretroid, ayntec, anbernic (USD) | landed cost estimated, import tax assumed |
| Wikidata | SPARQL (CC0) | specs / release dates (data/wikidata.json) |

## Privacy
No usernames, seller person names, phone numbers or avatars are stored. Shop/marketplace seller names are kept.

## Licence / use
Compiled for personal, non-commercial research. Listing titles and prices belong to their sources; check each
source's terms before republishing. Wikidata-derived fields are CC0. Common Crawl data is used under its Terms
of Use (third-party content stays under the original site's terms).

## Known biases and caveats
- New-market coverage is good (Akakçe aggregates most shops); used-market coverage is partial (sahibinden is
  only in via screenshots).
- Prices are asking prices, not transaction prices (forum SATILDI rows are the closest to real sales).
- Search-snippet prices can be stale or attached to the wrong product.
- The ML fair price has ~15 % median error (see ml_metrics); treat smaller deal % as noise.
- Retro clones dominate raw counts; use `listings_clean` for real devices.

## Quick start
```python
import duckdb
duckdb.sql("SELECT model, variant, condition, median(price_try) FROM 'dataset/latest/listings_clean.parquet' "
           "GROUP BY ALL ORDER BY 1").show()
```

## Files
| File | Rows | Description |
|---|---|---|
| `listings.parquet` / `.csv` | 3785 | Every listing from every source after parsing, categorising, dedupe and ML scoring. |
| `listings_clean.parquet` / `.csv` | 1171 | Real handhelds only: no clones, accessories, want-to-buy posts or sold items. |
| `priority_watchlist.parquet` / `.csv` | 44 | RP6 8/12GB, AYN, Retroid, RG556/557 rows (priority < 9). |
| `akakce_offers.parquet` / `.csv` | 2641 | Raw per-seller offers from Akakçe product pages. |
| `akakce_products.parquet` / `.csv` | 1043 | Raw Akakçe handheld catalogue (lowest price, offer count). |
| `forum_used_listings.parquet` / `.csv` | 509 | DonanımHaber second-hand section (title, price, sold flag). |
| `reviews.parquet` / `.csv` | 9 | Akakçe product comments (text, stars, date). No reviewer names. |
| `ml_metrics.parquet` / `.csv` | 6 | Cross-validated model metrics, FX rate and assumptions of the last analysis run. |
| `price_history.parquet` / `.csv` | 1378 | Every timestamped observation from the scrapers, the Scrapy crawler and Common Crawl archives (scraped_at = capture time): the price history for ML. |

## Data dictionary (`listings` / `listings_clean`)

| Column | Type | Meaning |
|---|---|---|
| `id` | BIGINT |  |
| `priority` | BIGINT | 1 = Retroid Pocket 6 8/12GB, 2 = other RP6, 3 = AYN, 4 = other Retroid, 5 = Anbernic RG556/557, 9 = rest |
| `model` | VARCHAR | Canonical model from analysis/catalog.py (or 'Unknown <category>' from the ML classifier) |
| `brand` | VARCHAR |  |
| `category` | VARCHAR | PC handheld (x86) / Android handheld / Linux retro handheld / Nintendo / Streaming / Sony legacy / Retro clone / Phone controller |
| `category_source` | VARCHAR | 'catalog' (regex) or 'ml:<confidence>' (title classifier) |
| `tier` | VARCHAR |  |
| `ram_gb` | BIGINT | RAM parsed from the title (NULL if not stated) |
| `storage_gb` | BIGINT | Storage parsed from the title, GB (1TB = 1024) |
| `variant` | VARCHAR | RAM/storage label, e.g. 8GB/128GB ('?' for an unknown part) |
| `condition` | VARCHAR | new / used / NULL (title wins over the source default) |
| `sold` | BIGINT | 1 if the listing says SATILDI / ALINDI / KAPANDI / sold out |
| `bundle` | BIGINT | 1 if the title mentions a bundle (+ game, set, hediye…) |
| `is_accessory` | BIGINT | 1 for cases, grips, chargers, games, and non-handhelds that matched a keyword |
| `wanted` | BIGINT | 1 for want-to-buy posts (ALINIK / arıyorum) |
| `is_clone` | BIGINT | 1 for no-name retro clones (400-in-1 etc.) |
| `title` | VARCHAR |  |
| `price_try` | DOUBLE | Asking price in TRY (NULL if missing or a placeholder under 100 TL) |
| `price_usd` | DOUBLE | Asking price in USD for foreign stores |
| `ships_to_tr` | BIGINT | 1 domestic listing, 0 store does not ship to Turkey, NULL unknown |
| `landed_cost_try` | DOUBLE | USD price × ECB USD/TRY × (1 + assumed import tax), see ml_metrics |
| `effective_price_try` | DOUBLE | price_try, else landed_cost_try |
| `group_n` | BIGINT | Listings in the same (model, variant, condition) group |
| `group_median` | DOUBLE | Median price of that group |
| `outlier` | BIGINT | 1 if outside the group's Tukey IQR fences |
| `fair_price` | DOUBLE | Out-of-fold ML fair price, calibrated per model (NULL when the model has no TL data) |
| `deal_pct` | DOUBLE | (fair_price - effective_price) / fair_price |
| `deal_signal` | VARCHAR | good deal / fair (within model error) / overpriced / no TR price data / does not ship to TR |
| `pct_rank` | DOUBLE | Share of the group priced below this listing (binary search) |
| `source_table` | VARCHAR | Which raw table the row came from |
| `source_kind` | VARCHAR | shop / classified / aggregator / search_snippet / scraper / user_report |
| `source` | VARCHAR | Canonical source, e.g. 'Hepsiburada/Mini More Türkiye', 'donanimhaber', 'idefix' |
| `url` | VARCHAR |  |
| `seen` | VARCHAR | When the row was scraped / listed / seen in a snippet |
