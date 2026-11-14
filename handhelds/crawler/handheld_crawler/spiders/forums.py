"""Generic for-sale forum spider, configured in sources.json.

  scrapy crawl forums                       # all forums, default page depth
  scrapy crawl forums -a only=donanimhaber -a pages=60
  scrapy crawl forums -s REFRESH=1          # ignore incremental skip
Stores title, price, condition, sold flag, thread URL. No usernames, avatars or contact info."""
import json
import re
from pathlib import Path

import scrapy

from ..jsonld import products, tr_price

HANDHELD = re.compile(r"deck|ally|legion-go|claw|ayaneo|ayn|odin|thor|retroid|anbernic|rg-?\d|miyoo|powkiddy|"
                      r"r36s|trimui|switch|portal|psp|vita|3ds|2ds|game-?boy|el-konsol|handheld|retro", re.I)
CONFIG = Path(__file__).resolve().parents[2] / "sources.json"


class ForumsSpider(scrapy.Spider):
    name = "forums"

    def __init__(self, only=None, pages=None, *a, **kw):
        super().__init__(*a, **kw)
        forums = json.loads(CONFIG.read_text(encoding="utf-8"))["forums"]
        self.forums = [f for f in forums if not only or f["name"] in only.split(",")]
        self.pages = int(pages) if pages else None

    async def start(self):
        for f in self.forums:
            for page in range(1, (self.pages or f.get("max_pages", 10)) + 1):
                url = f["section_url"] if page == 1 else f["page_url"].format(page=page)
                yield scrapy.Request(url, callback=self.parse_section, cb_kwargs={"forum": f})

    def parse_section(self, response, forum):
        rx = re.compile(forum["thread_link_re"])
        for href in response.css("a::attr(href)").getall():
            path = href.split("?")[0].split("#")[0]
            if href.startswith("http"):
                path = "/" + path.split("/", 3)[-1]
            if rx.match(path) and HANDHELD.search(path):
                yield response.follow(path, callback=self.parse_thread, cb_kwargs={"forum": forum},
                                      meta={"item_page": True})

    def parse_thread(self, response, forum):
        title = None
        price = None
        cond = None
        avail = ""
        for p in products(response):
            title, price, cond, avail = p["name"], p["price"], p["condition"], p["availability"]
            break
        if not title:   # no JSON-LD: page title + first TL price in the first post
            title = (response.css("h1::text").get() or response.css("title::text").get() or "").strip()
            price = tr_price(" ".join(response.css("article *::text, .post *::text").getall()[:400]))
        sold = bool(re.search(r"\b(SATILDI|ALINDI)\b", title, re.I)) or "OutOfStock" in avail
        clean = re.sub(r"^\s*[\[\(#]*\s*(SATILIK|SATILDI)\s*[\]\)#]*\s*[-:]?\s*", "", title, flags=re.I).strip()
        yield {
            "source": forum["name"],
            "product_id": re.search(r"(\d{6,})", response.url).group(1) if re.search(r"(\d{6,})", response.url)
                          else response.url,
            "title": clean or title,
            "price_try": price if (price or 0) >= 100 else None,
            "in_stock": False if sold else True,
            "condition": cond or "used",
            "url": response.url,
        }
