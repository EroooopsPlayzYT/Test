"""Scrape one Akakce listing page into JSON lines.

Usage: python3 scrape_page.py <listing-url> <out.jsonl> [source_tag]
Polite: one request, browser-like UA, caller sleeps between calls.
"""
import html
import json
import re
import sys
import urllib.request

LI_RE = re.compile(r'<li data-pr="(\d+)" data-mk="([^"]*)"[^>]*>(.*?)</li>', re.S)


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "tr-TR"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def parse_price(block):
    m = re.search(r'class="pt_v9">\s*(?:<!-- -->)?([\d.]+)<i>,(?:<!-- -->)?(\d+)', block)
    if not m:
        return None
    return float(m.group(1).replace(".", "") + "." + m.group(2))


def parse(page, source):
    rows = []
    for pid, brand, block in LI_RE.findall(page):
        title = re.search(r'<h3 class="pn_v8">(.*?)</h3>', block, re.S)
        href = re.search(r'<a href="([^"]+)"', block)
        img = re.search(r'<img[^>]*src="([^"]+)"', block)
        offers = re.search(r'\+(\d+)\s*FİYAT', block)
        if not title:
            continue
        rows.append({
            "akakce_id": int(pid),
            "brand": None if brand in ("", "-") else html.unescape(brand),
            "title": html.unescape(re.sub(r"<[^>]+>", "", title.group(1))).strip(),
            "min_price_try": parse_price(block),
            "extra_offers": int(offers.group(1)) if offers else 0,
            "url": "https://www.akakce.com" + href.group(1) if href else None,
            "image": html.unescape(img.group(1)) if img else None,
            "source": source,
        })
    return rows


if __name__ == "__main__":
    url, out = sys.argv[1], sys.argv[2]
    source = sys.argv[3] if len(sys.argv) > 3 else url
    rows = parse(fetch(url), source)
    with open(out, "a", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{len(rows)} rows from {url}")
