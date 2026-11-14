# Running the handheld pipeline

Everything is polite by design: robots.txt obeyed, one request at a time per site, stop on any
block (403/429/challenge, never retried under another identity), no usernames or contact details stored.

## Setup (once)
```bash
git clone -b ccr-88da3889-pay4g2 --recurse-submodules https://github.com/EroooopsPlayzYT/Test.git
cd Test/handhelds
pip install scikit-learn numpy duckdb pytz "scrapy==2.13.3" "twisted==24.11.0" beautifulsoup4 lxml
cd scraper-ts && npm install && cd ..
```

## Collect
| What | Command (from `handhelds/`) | Notes |
|---|---|---|
| Shops via Akakçe, idefix, Amazon TR, gaming.gen.tr | `cd scraper-ts && npm run scrape -- --queries queries.txt -n 30` | TypeScript scraper |
| Trendyol (home connection only) | `cd scraper-ts && npm run scrape -- -q "retroid pocket 6" -s trendyol-browse,trendyol-hybrid` | robots-allowed category pages, not /sr |
| Forums (DonanımHaber, R10) | `cd crawler && scrapy crawl forums -a pages=20` | incremental: recently seen threads are skipped |
| Small shops via sitemaps | `cd crawler && scrapy crawl shops` | add shops in `crawler/sources.json` |
| Common Crawl discovery | `python3 -m indexers.commoncrawl discover --crawls 3` | URL Index via DuckDB, ~20 s per crawl |
| Common Crawl host check | `python3 -m indexers.commoncrawl hosts --limit 60` | open = reachable + robots allows |
| Common Crawl price history | `python3 -m indexers.commoncrawl history --limit 500` | archived content for open hosts only |
| Other archives | `python3 -m indexers.archives --limit 30` | Arquivo.pt CDX, Wayback availability |
| Search APIs (free keys) | `python3 -m indexers.websearch --engine brave --max 150` | needs a Brave key (or `GOOGLE_CSE_CX` + Google key) |
| Wikidata specs | `python3 -m indexers.wikidata` | CC0, no key |

## Build + analyse + package
```bash
python3 build_db.py                  # load everything into handhelds.db
python3 -m analysis.run              # parse, categorise, ML fair price, deals
python3 -m dataset.build_dataset     # Parquet/CSV + DATASET.md in dataset/latest
python3 export_txt.py                # handhelds_db.txt (readable dump)
```

## Not used (blocked or disallowed)
sahibinden, Technopat, Techolay, ShiftDelete forum, Dolap, Letgo, Gardrops, Reddit (robots), PttAVM, n11,
Teknosa, Vatan, MediaMarkt, itopya, incehesap (from cloud servers). Their prices still arrive through Akakçe
offers and search snippets. Paid scraping/unblocking services are deliberately not used.
