"""Clean items with the shared analysis parser, drop accessories/wanted posts, store in SQLite.
Schema matches scraper-ts observations, so build_db.py loads both the same way."""
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

from scrapy.exceptions import DropItem

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))   # handhelds/ -> analysis package
from analysis.catalog import classify   # noqa: E402
from analysis.parse import parse_title  # noqa: E402


class CleanAndFilter:
    def process_item(self, item, spider):
        title = (item.get("title") or "").strip()
        if not title:
            raise DropItem("no title")
        p = parse_title(title, item.get("condition"))
        if p["is_accessory"]:
            raise DropItem(f"accessory/not a handheld: {title[:60]}")
        if p["wanted"]:
            raise DropItem(f"want-to-buy post: {title[:60]}")
        model = classify(title)[0]
        if model is None and not item.get("keep_unknown"):
            raise DropItem(f"not recognised as a handheld: {title[:60]}")
        item["condition"] = item.get("condition") or p["condition"]
        if p["sold"]:
            item["in_stock"] = False
        item.setdefault("scraped_at", datetime.now(timezone.utc).isoformat())
        return item


class SqlitePipeline:
    def open_spider(self, spider):
        path = spider.settings.get("CRAWL_DB")
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.con = sqlite3.connect(path)
        self.con.executescript("""
            CREATE TABLE IF NOT EXISTS observations (
              id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, product_id TEXT NOT NULL,
              title TEXT NOT NULL, price_try REAL, in_stock INTEGER, seller TEXT, condition TEXT,
              url TEXT NOT NULL, query TEXT, scraped_at TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_obs_url ON observations(url, scraped_at);""")

    def process_item(self, item, spider):
        stock = item.get("in_stock")
        self.con.execute(
            "INSERT INTO observations (source, product_id, title, price_try, in_stock, seller, condition, url, "
            "query, scraped_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (item["source"], str(item.get("product_id") or item["url"]), item["title"], item.get("price_try"),
             None if stock is None else int(bool(stock)), item.get("seller"), item.get("condition"),
             item["url"], spider.name, item["scraped_at"]))
        return item

    def close_spider(self, spider):
        self.con.commit()
        self.con.close()
