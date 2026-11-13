"""Scrape product comments + average rating from Akakce product pages.

Usage: python3 scrape_reviews.py <ids.txt> <out.jsonl>
Writes one {"akakce_id", "rating_avg", "rating_count", "reviews": [...]} line per
product. Reviewer names are deliberately not stored. Resumable, 2s between requests.
"""
import html
import json
import os
import re
import sys
import time
import urllib.request

COMMENT_RE = re.compile(
    r'<li data-c="(\d+)" data-l="\d+" data-d="\d+">.*?title="Eklenme tarihi: ([^"]+)".*?'
    r'<div class="cm">(.*?)</div>', re.S)


def parse(page):
    agg, ratings = {}, []
    for blob in re.findall(r'<script type="application/ld\+json">(.*?)</script>', page, re.S):
        try:
            d = json.loads(blob)
        except ValueError:
            continue
        if isinstance(d, dict) and d.get("@type") == "Product":
            agg = d.get("aggregateRating") or {}
            ratings = [(r.get("reviewBody", "").strip(), (r.get("reviewRating") or {}).get("ratingValue"))
                       for r in d.get("review", [])]
    by_body = {body: rating for body, rating in ratings}
    reviews = []
    for cid, added, body in COMMENT_RE.findall(page):
        text = " ".join(html.unescape(re.sub(r"<[^>]+>", " ", body)).split())
        reviews.append({"comment_id": int(cid), "added": added, "text": text,
                        "rating": int(by_body[text]) if by_body.get(text) else None})
    if not reviews:  # fall back to the JSON-LD copy
        reviews = [{"comment_id": None, "added": None, "text": b, "rating": int(r) if r else None}
                   for b, r in ratings]
    return {"rating_avg": float(agg["ratingValue"]) if agg.get("ratingValue") else None,
            "rating_count": int(agg["ratingCount"]) if agg.get("ratingCount") else 0,
            "reviews": reviews}


def main(ids_path, out_path):
    done = set()
    if os.path.exists(out_path):
        done = {json.loads(l)["akakce_id"] for l in open(out_path, encoding="utf-8")}
    for line in open(ids_path, encoding="utf-8"):
        if not line.strip():
            continue
        pid, url = line.rstrip("\n").split("\t")
        if int(pid) in done:
            continue
        rec = {"akakce_id": int(pid), "rating_avg": None, "rating_count": 0, "reviews": []}
        for attempt in (1, 2):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "tr-TR"})
                rec.update(parse(urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")))
                break
            except Exception as e:
                print(f"{pid} attempt {attempt} failed: {e}")
                time.sleep(10)
        with open(out_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print(f"{pid}: {len(rec['reviews'])} reviews, avg {rec['rating_avg']}")
        time.sleep(2)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
