"""Scrape handhelds from idefix.com (the one big store that serves plain requests).

Search and category pages are JS-rendered / disallowed in robots.txt, so this
reads the server-rendered brand pages, keeps handheld-looking product links,
and reads each product page's JSON-LD (allowed by robots.txt: Allow /*-p-).
Usage: python3 scrape_idefix.py <out.jsonl>
"""
import json
import re
import sys
import time
import urllib.request
import urllib.robotparser

from scrape_shop import UA, is_device, jsonld_products, tr_price

BASE = "https://www.idefix.com"
GAME = re.compile(r"\b(oyun|oyunu|versiyon|edition|sports|adventure|zelda|mario|pokemon|minecraft|smash|cyberpunk)\b", re.I)
BRANDS = ["valve-20047255", "nintendo-1001386", "anbernic-20081785", "ayaneo-20083672",
          "playstation-20122520"]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "tr-TR"})
    return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")


def main(out):
    robots = urllib.robotparser.RobotFileParser(BASE + "/robots.txt")
    robots.read()
    links = set()
    for b in BRANDS:
        page = get(f"{BASE}/marka/{b}")
        for path in re.findall(r'href="(/[^"#?]*-p-\d+)', page):
            slug = path.replace("-", " ")
            if is_device(slug):
                links.add(BASE + path)
        time.sleep(1)
    n = 0
    with open(out, "w", encoding="utf-8") as f:
        for url in sorted(links):
            if not robots.can_fetch(UA, url):
                print(f"robots.txt disallows {url}")
                continue
            for p in jsonld_products(get(url)):
                title = p.get("name") or ""
                if GAME.search(title) and not re.search(r"konsol", title, re.I):
                    break  # a Switch game, not a console
                off = p.get("offers") or {}
                off = off[0] if isinstance(off, list) and off else off
                brand = p.get("brand")
                f.write(json.dumps({
                    "title": p.get("name"), "brand": brand.get("name") if isinstance(brand, dict) else brand,
                    "price_try": tr_price(off.get("price")) or None,
                    "in_stock": "InStock" in str(off.get("availability", "")),
                    "url": url, "shop": "idefix.com", "platform": "idefix-brand-page"},
                    ensure_ascii=False) + "\n")
                n += 1
                break
            time.sleep(1)
    print(f"idefix: {len(links)} handheld links, {n} products saved")


if __name__ == "__main__":
    main(sys.argv[1])
