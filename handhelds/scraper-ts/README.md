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
| `trendyol` | **ProductScrape's original `trendyol.py`, unmodified** (git submodule `handhelds/vendor/ProductScrape`, run via `py/run_trendyol.py`; needs `pip install beautifulsoup4 lxml`). Instead of `/sr` (disallowed in robots.txt) the wrapper hands `Trendyol.parser()` the allowed category/brand pages | ⛔ 403 from cloud servers; try from home |
| `trendyol-browse` | TypeScript: walks the allowed category / brand / landing pages (`-x-c108889`, `-x-b157692-c104044`, `-y-s56517`…) with `?pi=` paging, filters titles by the query | ⛔ 403 from cloud servers; try from home |
| `trendyol-ts` | TypeScript re-implementation of `/sr` search | stops on robots.txt (`/sr` disallowed) |

Blocked or disallowed sources are logged in the `runs` table (`blocked` / `robots`) and skipped.

## Kept from ProductScrape

* `trendyol.py` itself, byte-for-byte (submodule). The wrapper only runs it in a temp dir, waits for
  **all** its threads (the original joins only the last one), and turns errors into JSON.
* `main.py`'s interactive mode: run with no flags and it asks `Search by product...` and
  `How many results do you want?`, quitting with the same messages on a non-number.
* ProductCard output (`id`, `title`, `price`, `link`) in `out/results<site>.json` and `out/results.json`,
  now as valid JSON arrays. The price text keeps `"Not available, please view the product page"`.
* Amazon: search URL, the `keywords` organic-link rule, `slredirect` / `/gp/` ad filtering, ASIN from
  `dp/<ASIN>`, and the per-product page read (`#productTitle`, first `.a-offscreen`).
* Multithreading (now a bounded pool), timeouts, the 208-page Trendyol cap, the unit tests (ported).

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

## Trendyol hybrid (ProductScrape `trendyol.py` + OmBayus/Trendyol-Web-Scraping)

`src/hybrid/trendyol-hybrid.ts` combines both projects; nothing from either is dropped except OmBayus' email.

```bash
# ProductScrape flow: listing pages -> cards -> product pages (id) -> out/resultstrendyol.json
npm run trendyol -- search -q "retroid pocket 6" -n 30            # HTTP mode
npm run trendyol -- search -q "ayn odin" -n 30 --browser          # headless browser mode
# OmBayus flow: watch product pages, alert when another seller is cheaper
npm run trendyol -- track "https://www.trendyol.com/...-p-123" "https://...-p-456"   # every 30 min
npm run trendyol -- track "<url>" --once --http                    # one check, plain requests
```

| Feature | From | Here |
|---|---|---|
| 24 cards/page, 208-page cap, `limit // 24` + remainder page | trendyol.py | `pagePlan()` |
| Card selectors `p-card-wrppr`, `prdct-desc-cntnr-name[title]`, `prc-box-dscntd`; "Not available…" price | trendyol.py | `parseListing()` (+ JSON-LD / embedded-state fallback) |
| Product page id = last word of `h1.pr-new-br` | trendyol.py | `parseProductPage()` |
| Threads per product, `time.sleep(1)`, results file written as products finish | trendyol.py | bounded pool, 1 s sleep, `out/resultstrendyol.json` |
| Headless browser, one tab per URL, `page.reload()` per check | OmBayus | Playwright (`--browser`, default for `track`) |
| Click `.omc-mr-btn`, read `.prc-dsc` and `.mrc-new-prc span`, cheapest < yours | OmBayus | `checkPrice()` |
| Cron schedule with run-on-init | OmBayus | `--cron`, default `0 */30 * * * *` (OmBayus' `* */30 * * * *` fired every second in minutes 0 and 30) |
| Email via nodemailer + `.env` | OmBayus | **dropped**; alerts go to console, `alerts` table and `out/alerts.jsonl` |
| `/sr` search | trendyol.py | robots-allowed category/brand pages instead |

## User-Agent rotation (opt-in)

`--ua-rotate` (or `UA_ROTATE=1`) picks a random browser User-Agent per request from a list, like
ProductScrape's `user_agent_list`. robots.txt, per-host delays and stop-on-block still apply: a 403 or
challenge is never retried under a different User-Agent.

Credits: OmBayus/Trendyol-Web-Scraping © 2021 Omer Bayramcavus, MIT License. ProductScrape by
muratcansarkalkan (used unmodified as a git submodule).
