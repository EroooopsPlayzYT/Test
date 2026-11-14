# ADR-0001: Keep TypeScript + Python; speed up scraping with incremental fetching, not a Rust/assembly port

**Status:** Proposed
**Date:** 2026-11-14
**Deciders:** project owner

## Context

The handheld price database is built by:

- **`scraper-ts/`** (TypeScript, Node 22): polite HTTP client (robots.txt, per-host delays,
  circuit breaker), site adapters (Akakçe, idefix, DonanımHaber, Amazon TR, Trendyol hybrid,
  WooCommerce/Shopify), SQLite output.
- **`analysis/` + `build_db.py`** (Python): parsing, catalogue, binary-search queries, dedupe,
  scikit-learn fair-price model and title classifier.

The question raised: *port to Rust (or assembly) for better performance?*

Measured on this project (2026-11-14):

| Stage | Measured | Where the time goes |
|---|---|---|
| Scrape, per request | **~3.0 s** (343 requests in ~17 min, forum history pass) | 2.5 s deliberate politeness delay + network/server time |
| HTML/JSON-LD parse, per page | **11–14 ms** (Akakçe product page 200 KB, forum page 250 KB) | cheerio in Node |
| `build_db.py` | **0.4 s** | SQLite inserts |
| `analysis.run` | **23 s**, 201 MB peak | scikit-learn 5-fold CV (already native C/Cython) |

Forces:

- Delays are **intentional and non-negotiable**: going faster per site triggers blocks
  (Akakçe answered 429 once; Trendyol/Hepsiburada/sahibinden block bots outright).
- Parsing is **~0.4 %** of each request's wall time.
- A single maintainer, working through a chat interface; changes must stay easy to make.
- The scraper may run on a Windows/Linux PC or the user's ARM device.

## Decision

Keep the current stack (TypeScript scraper, Python analysis). Do **not** port to Rust or
assembly. Get speed from **doing fewer requests**: an incremental mode that skips unchanged
pages, plus conditional requests and sitemaps where available.

## Options Considered

### Option A: Keep TS + Python, add incremental fetching (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low: one "seen" table plus `If-Modified-Since`/ETag in `http.ts` |
| Cost | ~1 day |
| Speed-up | Repeat runs: ~1 h to minutes (only new/changed listings fetched) |
| Team familiarity | High: existing code, existing tests |

**Pros:** attacks the real bottleneck (request count); fewer requests is also politer, so less
block risk; keeps 32 tests and all adapters as they are.
**Cons:** first full run is still slow (by design); needs care so stale listings expire.

### Option B: Port the scraper to Rust

| Dimension | Assessment |
|-----------|------------|
| Complexity | High: rewrite HTTP client, robots, 8 adapters, SQLite layer, tests |
| Cost | ~2–4 weeks |
| Speed-up | Parse 11–14 ms to ~2–5 ms, i.e. **< 1 %** of a 3 s request cycle |
| Team familiarity | Low |

**Pros:** smaller binary, lower memory, nice learning project.
**Cons:** no measurable end-to-end gain (delay-bound); loses `trendyol.py` integration and
Playwright browser mode (Rust options are weaker); slower to change selectors when sites move.

### Option C: Port to assembly

| Dimension | Assessment |
|-----------|------------|
| Complexity | Extreme: TLS, HTTP, gzip, HTML/JSON parsing, Unicode, SQLite by hand |
| Cost | Months |
| Speed-up | Same < 1 % as Option B |
| Team familiarity | None |

**Pros:** none for this workload.
**Cons:** hand-rolled TLS is a security risk; not portable (x86 vs ARM); unmaintainable.

### Option D: Rewrite analysis in Rust (polars + linfa)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium–High |
| Cost | ~1–2 weeks |
| Speed-up | 23 s to maybe 5–10 s on a job run occasionally |
| Team familiarity | Low |

**Pros:** faster CV loop.
**Cons:** scikit-learn's monotonic gradient boosting, TF-IDF classifier and CV tooling have
no drop-in equivalents; 23 s per manual run is not a pain point.

## Trade-off Analysis

The pipeline is **I/O- and politeness-bound**, not CPU-bound. Even an infinitely fast parser
saves 11–14 ms of every ~3,000 ms request (~0.4 %). The only lever that changes wall time is
the **number of requests**, which a language port does not touch. Cutting requests (Option A)
both speeds things up and lowers block risk; a port (B/C) costs weeks to months for no visible
gain and gives up the browser mode and the unmodified `trendyol.py` integration. The analysis
side (D) runs in 23 s on native-code libraries: fine for a manual or daily job.

Revisit only if profiling shows CPU above ~20 % of wall time (e.g. if delays ever drop, or
the data grows past ~1M rows, at which point move analysis to DuckDB/polars before considering
a rewrite).

## Consequences

- **Easier:** frequent re-runs (daily price history for the ML), fewer blocks, less load on sites.
- **Harder:** a little more state to manage (what was seen, when, with which ETag); listings
  need an expiry rule (e.g. re-check sold/closed after N days).
- **Revisit:** per-request CPU share after incremental mode lands; DuckDB if row counts explode.

## Action Items

1. [ ] Add a `seen` table (url, etag, last_modified, content_hash, last_checked, status).
2. [ ] `http.ts`: send `If-None-Match` / `If-Modified-Since`; treat 304 as "unchanged".
3. [ ] Adapters: skip forum threads / Akakçe products checked within N hours unless `--refresh`.
4. [ ] Prefer sitemaps or category pages over search pages where robots.txt allows.
5. [ ] Benchmark: full run vs incremental run; record requests and wall time here.
6. [ ] Optional learning track: port `parseRobots` + `isAllowed` to Rust as a standalone crate
       (not on the critical path).
