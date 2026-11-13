# handheld-scraper (TypeScript)

A TypeScript port of [muratcansarkalkan/ProductScrape](https://github.com/muratcansarkalkan/ProductScrape),
for collecting Turkish handheld console prices into a local database for personal analysis.

Every run **appends** to SQLite with a timestamp. You build up a price history once and run
ML on the local copy, instead of re-requesting the sites.

## Run

```bash
npm install
npm run scrape -- --query "steam deck" --limit 30
npm run scrape -- --queries queries.txt --sources akakce,idefix,donanimhaber
DH_PAGES=50 npm run scrape -- --query "*" --limit 1000 --sources donanimhaber   # all handheld sale threads
npm test
```

Output goes to `out/prices.db` (tables `observations`, `runs`, view `latest_prices`) and `out/results.jsonl`.
Accessories (cases, films, grips, chargers…) are dropped unless you pass `--include-accessories`.

## Sources

| `--sources` name | What | Status from a cloud server (2026-11-13) |
|---|---|---|
| `akakce` | Search → product pages → **one row per seller offer** (all big and small shops) | ✅ works |
| `idefix` | Brand pages → product JSON-LD (search is JS-only, categories disallowed) | ✅ works (~24 products per brand) |
| `donanimhaber` | Forum "İkinci El Konsollar" section → sale threads (used, SATILIK/SATILDI) | ✅ works |
| `amazontr` | Search result cards (ported from `amazontr.py`) | ✅ works, sometimes 503 |
| `gaming.gen.tr`, `woocommerce:<domain>` | WooCommerce Store API | ✅ works |
| `shopify:<domain>` | Shopify `/products.json` | for any Shopify shop |
| `trendyol` | Ported from `trendyol.py` | ⛔ Trendyol's robots.txt disallows `/sr` (search), so it stops |

Blocked or disallowed sources are logged in the `runs` table (`blocked` / `robots`) and skipped.

## What changed vs ProductScrape

| ProductScrape (Python) | This port |
|---|---|
| SSL verification disabled | TLS verification stays on |
| Random User-Agent rotation "to minimize 503s" | One honest User-Agent. A 403, captcha or Cloudflare/Akamai/AWS challenge stops that source; it is never worked around |
| No robots.txt check | robots.txt (`User-agent: *`) checked before every request |
| One thread per product, unbounded; only the last thread joined | Concurrency pool plus a per-host delay (1.5 s default, 2.5 s for the forum) |
| CSS class names from 2021 (`p-card-wrppr`, `prc-box-dscntd`) | schema.org JSON-LD first, then embedded state, then links |
| Opens every product page for id/price | Reads id and price from search results where possible (fewer requests) |
| Price kept as a string ("Not available…") | Numeric TRY or `null` |
| Concatenated JSON objects (invalid file) | JSON Lines plus SQLite with timestamps |
| Bare `except:` swallows errors | Typed errors (`BlockedError`, `RobotsDisallowedError`), retries only for timeouts/5xx |
| `input()` prompts | CLI flags and a query file, so it can run on a schedule |

## Privacy

Rows hold product, price, condition and shop names only. For forum and classified sources, no
usernames, avatars, phone numbers or post bodies are stored. Instagram, Facebook and sahibinden
are intentionally not supported (login walls / scraping bans / bot blocks).
