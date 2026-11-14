"""Small-shop spider: discovers product pages from each shop's sitemaps (found via robots.txt),
keeps handheld-looking URLs and reads schema.org Product data.

  scrapy crawl shops
  scrapy crawl shops -a only=gaming.gen.tr
"""
import json
import re
from pathlib import Path

from scrapy.spiders import SitemapSpider

from ..jsonld import products

HANDHELD_URL = re.compile(r"steam-deck|rog-ally|xbox-ally|legion-go|msi-claw|claw|ayaneo|ayn-|odin|retroid|anbernic|"
                          r"\brg-?\d|miyoo|powkiddy|r36s|trimui|nintendo-switch|switch-2|switch-lite|switch-oled|"
                          r"playstation-portal|ps-portal|el-konsol|retro-oyun-konsol|psp|vita", re.I)
CONFIG = Path(__file__).resolve().parents[2] / "sources.json"


class ShopsSpider(SitemapSpider):
    name = "shops"

    def __init__(self, only=None, *a, **kw):
        shops = json.loads(CONFIG.read_text(encoding="utf-8"))["shops"]
        self.shops = [s for s in shops if not only or s["name"] in only.split(",")]
        self.sitemap_urls = [s["sitemap"] for s in self.shops]
        self.allowed_domains = [s["sitemap"].split("/")[2] for s in self.shops]
        super().__init__(*a, **kw)

    def sitemap_filter(self, entries):
        for e in entries:
            loc = e.get("loc", "")
            if "sitemap" in loc or HANDHELD_URL.search(loc):   # follow sub-sitemaps, keep handheld products
                yield e

    def parse(self, response):
        host = response.url.split("/")[2]
        shop = next((s for s in self.shops if s["sitemap"].split("/")[2] == host), {"name": host})
        for p in products(response):
            if not p["name"]:
                continue
            yield {"source": shop["name"], "product_id": p["sku"] or response.url, "title": p["name"],
                   "price_try": p["price"], "in_stock": "InStock" in p["availability"] if p["availability"] else None,
                   "condition": p["condition"] or "new", "url": response.url, "seller": shop["name"]}
            break
