import re
import sqlite3
import time
from datetime import datetime, timedelta, timezone

from scrapy.exceptions import IgnoreRequest

CHALLENGE = re.compile(r"Just a moment\.\.\.|Attention Required! \| Cloudflare|captcha|"
                       r"Olağandışı bir durum|Güvenlik doğrulaması", re.I)


class BlockGuard:
    """Circuit breaker: the first 403/429/challenge from a site stops all further requests to it
    for this run. Nothing is retried under another identity."""

    def __init__(self, crawler):
        self.crawler = crawler
        self.blocked = {}

    @classmethod
    def from_crawler(cls, crawler):
        return cls(crawler)

    def process_request(self, request, spider):
        host = request.url.split("/")[2]
        if host in self.blocked:
            raise IgnoreRequest(f"{host} blocked earlier this run ({self.blocked[host]})")

    def process_response(self, request, response, spider):
        host = request.url.split("/")[2]
        reason = None
        if response.status in (403, 429):
            reason = f"HTTP {response.status}"
        elif response.headers.get(b"cf-mitigated"):
            reason = "Cloudflare challenge"
        elif response.status == 200 and CHALLENGE.search(response.body[:5000].decode("utf-8", "ignore")):
            reason = "challenge page"
        if reason:
            self.blocked[host] = reason
            spider.logger.warning(f"BLOCKED by {host}: {reason}; skipping this site for the rest of the run")
            self.crawler.stats.inc_value(f"blocked/{host}")
            raise IgnoreRequest(reason)
        return response


class SkipRecentlySeen:
    """Incremental mode: item pages (meta['item_page']=True) already stored within RECHECK_HOURS
    are skipped. Listing/section pages are always fetched (cheap 304s thanks to the HTTP cache)."""

    def __init__(self, db_path, hours):
        self.recent = set()
        try:
            con = sqlite3.connect(db_path)
            since = (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat()
            self.recent = {u for (u,) in con.execute(
                "SELECT DISTINCT url FROM observations WHERE scraped_at >= ?", (since,))}
            con.close()
        except sqlite3.Error:
            pass

    @classmethod
    def from_crawler(cls, crawler):
        s = crawler.settings
        hours = s.getfloat("RECHECK_HOURS")
        if s.getbool("REFRESH"):
            hours = 0
        return cls(s.get("CRAWL_DB"), hours)

    def process_request(self, request, spider):
        if request.meta.get("item_page") and request.url in self.recent:
            raise IgnoreRequest("seen recently (incremental mode); use -s REFRESH=1 to force")
