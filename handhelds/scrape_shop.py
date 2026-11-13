"""Scrape handheld products from one small shop's public catalogue.

Usage: python3 scrape_shop.py <domain> <platform> <out.jsonl> [search_url_template]
platform: shopify | woocommerce | html  (html = search page + JSON-LD Product data)

Checks robots.txt first and skips anything it disallows. Never retries around
403/captcha responses; a blocked shop is reported and skipped.
"""
import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.robotparser

UA = "Mozilla/5.0"
QUERIES = ["steam deck", "rog ally", "legion go", "msi claw", "ayaneo", "nintendo switch",
           "switch 2", "switch lite", "playstation portal", "anbernic", "retroid", "miyoo",
           "powkiddy", "el konsolu", "retro oyun konsolu"]
HANDHELD = re.compile(
    r"steam ?deck|rog (xbox )?ally|legion go|msi claw|claw 8|ayaneo|switch|portal|anbernic|retroid|"
    r"miyoo|powkiddy|el konsol|el atari|retro.*konsol|game ?boy|r36s|rg ?\d", re.I)
NOT_DEVICE = re.compile(
    r"kılıf|kilif|çanta|canta|koruyucu|cam |temperli|stand|şarj|sarj|kablo|adaptör|joy-?con|"
    r"kontrolc|oyun kolu|kol |grip|sticker|kaplama|dock|oyunu|amiibo|kart|case|skin|film|"
    r"hafıza|hafiza|thumb|başlık|baslik|silikon|kulaklık|kulaklik|hediye kart", re.I)


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "tr-TR"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def is_device(title):
    return bool(HANDHELD.search(title)) and not NOT_DEVICE.search(title)


def tr_price(s):
    """'51.457,00' / '51457.00' / 5145700 (cents) -> float."""
    if s is None:
        return None
    s = str(s).strip()
    if re.fullmatch(r"\d+", s):
        return float(s)
    if "," in s:
        s = s.replace(".", "").replace(",", ".")
    try:
        return float(re.sub(r"[^\d.]", "", s))
    except ValueError:
        return None


def shopify(base):
    page = 1
    while page <= 20:
        data = json.loads(get(f"{base}/products.json?limit=250&page={page}"))
        prods = data.get("products", [])
        if not prods:
            return
        for p in prods:
            v = (p.get("variants") or [{}])[0]
            yield {"title": p["title"], "brand": p.get("vendor"), "price_try": tr_price(v.get("price")),
                   "in_stock": any(x.get("available") for x in p.get("variants", [])),
                   "url": f"{base}/products/{p['handle']}"}
        page += 1
        time.sleep(1)


def woocommerce(base):
    for q in QUERIES:
        url = f"{base}/wp-json/wc/store/v1/products?per_page=100&search={urllib.parse.quote(q)}"
        for p in json.loads(get(url)):
            pr = p.get("prices", {})
            minor = int(pr.get("currency_minor_unit", 2))
            price = int(pr["price"]) / 10 ** minor if pr.get("price") else None
            yield {"title": html.unescape(p["name"]), "brand": None, "price_try": price,
                   "in_stock": p.get("is_in_stock"), "url": p.get("permalink")}
        time.sleep(1)


def jsonld_products(page):
    for blob in re.findall(r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>', page, re.S):
        try:
            data = json.loads(blob.strip())
        except ValueError:
            continue
        stack = data if isinstance(data, list) else [data]
        while stack:
            d = stack.pop()
            if isinstance(d, dict):
                if d.get("@type") == "Product":
                    yield d
                stack.extend(v for k, v in d.items() if isinstance(v, (dict, list)))
            elif isinstance(d, list):
                stack.extend(d)


def html_search(base, template, robots):
    seen = set()
    for q in QUERIES:
        url = template.replace("{q}", urllib.parse.quote_plus(q))
        if not robots.can_fetch(UA, url):
            print(f"  robots.txt disallows {url}")
            return
        page = get(url)
        links = {urllib.parse.urljoin(url, h) for h in re.findall(r'href="([^"#]+)"', page)}
        prods = [l for l in links if l.startswith(base) and l not in seen
                 and re.search(r"/(urun|product|p)/|-p-\d|\.html$|-\d{3,}$", l)]
        for link in prods[:40]:
            seen.add(link)
            if not robots.can_fetch(UA, link):
                continue
            time.sleep(1)
            try:
                for p in jsonld_products(get(link)):
                    off = p.get("offers") or {}
                    off = off[0] if isinstance(off, list) and off else off
                    brand = p.get("brand")
                    yield {"title": html.unescape(p.get("name", "")),
                           "brand": brand.get("name") if isinstance(brand, dict) else brand,
                           "price_try": tr_price(off.get("price") or off.get("lowPrice")),
                           "in_stock": "InStock" in str(off.get("availability", "")),
                           "url": link}
                    break
            except Exception as e:
                print(f"  {link}: {e}")
        time.sleep(1)


def main(domain, platform, out, template=None):
    base = f"https://{domain}"
    robots = urllib.robotparser.RobotFileParser(base + "/robots.txt")
    try:
        robots.read()
    except Exception:
        robots.parse([])
    gen = {"shopify": lambda: shopify(base), "woocommerce": lambda: woocommerce(base),
           "html": lambda: html_search(base, template, robots)}[platform]
    n, seen = 0, set()
    try:
        with open(out, "a", encoding="utf-8") as f:
            for p in gen():
                if not p["title"] or p["url"] in seen or not is_device(p["title"]):
                    continue
                seen.add(p["url"])
                if not p.get("price_try"):
                    p["price_try"] = None  # 0 means "no price shown" (usually out of stock)
                p.update(shop=domain, platform=platform)
                f.write(json.dumps(p, ensure_ascii=False) + "\n")
                n += 1
    except urllib.error.HTTPError as e:
        print(f"{domain}: HTTP {e.code}, skipped (not working around blocks)")
    print(f"{domain}: {n} handheld products")


if __name__ == "__main__":
    main(*sys.argv[1:5])
