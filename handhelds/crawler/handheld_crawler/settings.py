"""Polite defaults: robots.txt, one request at a time per site, AutoThrottle, conditional
requests via the HTTP cache (ADR-0001 incremental mode), stop on blocks, never retry 403/429."""
from pathlib import Path

BOT_NAME = "handheld_crawler"
SPIDER_MODULES = ["handheld_crawler.spiders"]
NEWSPIDER_MODULE = "handheld_crawler.spiders"

USER_AGENT = "Mozilla/5.0 (compatible; handheld-price-notebook/1.0; personal use)"
ROBOTSTXT_OBEY = True

CONCURRENT_REQUESTS = 8               # across different sites
CONCURRENT_REQUESTS_PER_DOMAIN = 1    # never more than one at a time per site
DOWNLOAD_DELAY = 2.5
RANDOMIZE_DOWNLOAD_DELAY = True
AUTOTHROTTLE_ENABLED = True           # slows down further if the site gets slow
AUTOTHROTTLE_START_DELAY = 2.5
AUTOTHROTTLE_MAX_DELAY = 30
AUTOTHROTTLE_TARGET_CONCURRENCY = 0.5

# Retries: transient errors only. 403/429 are blocks and are handled by BlockGuard, not retried.
RETRY_ENABLED = True
RETRY_TIMES = 2
RETRY_HTTP_CODES = [500, 502, 503, 504, 522, 524, 408]

# Incremental crawling: RFC2616 cache sends If-Modified-Since / If-None-Match on re-runs,
# so unchanged pages cost a tiny 304 instead of a full download.
HTTPCACHE_ENABLED = True
HTTPCACHE_POLICY = "scrapy.extensions.httpcache.RFC2616Policy"
HTTPCACHE_DIR = str(Path(__file__).resolve().parent.parent / "out" / "httpcache")
HTTPCACHE_IGNORE_HTTP_CODES = [403, 429, 500, 502, 503, 504]

DOWNLOADER_MIDDLEWARES = {
    "handheld_crawler.middlewares.BlockGuard": 950,      # sees responses before the HTTP cache (900) stores them
    "handheld_crawler.middlewares.SkipRecentlySeen": 100,
}
ITEM_PIPELINES = {
    "handheld_crawler.pipelines.CleanAndFilter": 100,
    "handheld_crawler.pipelines.SqlitePipeline": 300,
}
CRAWL_DB = str(Path(__file__).resolve().parent.parent / "out" / "crawl.db")
RECHECK_HOURS = 24          # SkipRecentlySeen: don't refetch an item page seen within this many hours

REQUEST_FINGERPRINTER_IMPLEMENTATION = "2.7"
TWISTED_REACTOR = "twisted.internet.asyncioreactor.AsyncioSelectorReactor"
FEED_EXPORT_ENCODING = "utf-8"
LOG_LEVEL = "INFO"
