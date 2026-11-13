"""Scrape every seller offer from Akakce product pages.

Usage: python3 scrape_offers.py <ids.txt> <out.jsonl>
ids.txt holds "akakce_id<TAB>url" lines. Sleeps 2s between requests and
skips ids already present in out.jsonl, so it can be re-run after a failure.
"""
import html
import json
import os
import re
import sys
import time
import urllib.request

LI_RE = re.compile(r"<li[^>]*>(<span><span class=\"pb_v8\">.*?)</li>", re.S)


def text(s):
    return html.unescape(re.sub(r"<[^>]+>", " ", s or "")).split() and \
        " ".join(html.unescape(re.sub(r"<[^>]+>", " ", s)).split()) or None


def grab(pattern, block):
    m = re.search(pattern, block, re.S)
    return text(m.group(1)) if m else None


def parse_offers(page, pid):
    pl = page.find('id="PL"')
    if pl < 0:
        return []
    end = page.find("</ul>", pl)
    offers = []
    for block in LI_RE.findall(page[pl:end]):
        price = re.search(r'class="pt_v8[^"]*">([\d.]+)<i>,(\d+)', block)
        seller_box = re.search(r'<span class="v_v8">(.*?)</span>\s*</span>', block, re.S)
        marketplace = seller = None
        if seller_box:
            box = seller_box.group(1)
            img = re.search(r'<img alt="([^"]*)"', box)
            bold = re.search(r"<b>(.*?)</b>", box)
            if bold:
                seller = text(bold.group(1))
            elif img:
                marketplace = html.unescape(img.group(1))
                rest = text(re.sub(r"<img[^>]*>", "", box).split("<span")[0])
                seller = rest.lstrip("/ ").strip() if rest else marketplace
        offers.append({
            "akakce_id": pid,
            "seller": seller,
            "marketplace": marketplace,
            "price_try": float(price.group(1).replace(".", "") + "." + price.group(2)) if price else None,
            "listing_title": grab(r'<span class="pn_v8">(.*?)</span>', block),
            "shipping": grab(r'<em class="uk_v8">(.*?)</em>', block),
            "stock": grab(r'<span class="stock_v8">(.*?)</span>', block),
            "ship_time": grab(r'<span class="sd_v8"[^>]*>(.*?)</span>', block),
            "seller_rating": grab(r'class="r_v8[^"]*">(.*?)</span>', block),
        })
    return offers


def main(ids_path, out_path):
    done = set()
    if os.path.exists(out_path):
        done = {json.loads(l)["akakce_id"] for l in open(out_path, encoding="utf-8")}
    todo = [l.rstrip("\n").split("\t") for l in open(ids_path, encoding="utf-8") if l.strip()]
    for pid, url in todo:
        pid = int(pid)
        if pid in done:
            continue
        for attempt in (1, 2):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "tr-TR"})
                page = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
                break
            except Exception as e:  # retry once, then record nothing for this id
                print(f"{pid} attempt {attempt} failed: {e}")
                page = None
                time.sleep(10)
        offers = parse_offers(page, pid) if page else []
        with open(out_path, "a", encoding="utf-8") as f:
            for o in offers or [{"akakce_id": pid, "seller": None}]:  # marker row so re-runs skip it
                f.write(json.dumps(o, ensure_ascii=False) + "\n")
        print(f"{pid}: {len(offers)} offers")
        time.sleep(2)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
