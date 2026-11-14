"""Heavy web searching through free-tier search APIs (no scraping of search result pages).

* Brave Search API  (free tier ~2,000 queries/month, 1 query/s)   host: api.search.brave.com
* Google Programmable Search JSON API (100 queries/day free)      host: customsearch.googleapis.com
  needs a search engine ID: export GOOGLE_CSE_CX=<your cx> (not secret)

API keys are NOT read from this repo or env files: in this cloud session they are stored as
network secrets and injected by the proxy, so the headers below carry a placeholder.
At home, set BRAVE_API_KEY / GOOGLE_API_KEY instead.

Usage (from handhelds/):
  python3 -m indexers.websearch --engine brave --max 150
  python3 -m indexers.websearch --engine google --max 90
Results -> data/search_api.jsonl (picked up by build_db.py as search_index_listings).
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from analysis.catalog import classify   # noqa: E402

OUT = ROOT / "data" / "search_api.jsonl"
QUOTA = ROOT / "data" / "search_api_quota.json"
LIMITS = {"brave": ("month", 2000), "google": ("day", 100)}
SKIP_DOMAINS = re.compile(r"instagram\.com|facebook\.com|sahibinden\.com|youtube\.com|tiktok\.com")
TL = re.compile(r"(\d{1,3}(?:\.\d{3})+|\d{3,6})(?:,(\d{1,2}))?\s*(?:TL|₺)", re.I)

MODELS = ["Retroid Pocket 6", "Retroid Pocket 6 12GB", "Retroid Pocket 5", "Retroid Pocket Mini", "Retroid Pocket Flip 2",
          "AYN Odin 2", "AYN Odin 2 Portal", "AYN Odin 3", "AYN Thor", "Anbernic RG557", "Anbernic RG556",
          "Anbernic RG406H", "Anbernic RG35XX", "Miyoo Mini Plus", "Steam Deck OLED", "ROG Ally X", "ROG Xbox Ally",
          "Legion Go S", "MSI Claw 8 AI+", "Nintendo Switch 2", "Switch OLED", "Switch Lite", "PlayStation Portal",
          "Ayaneo", "TrimUI Smart Pro", "R36S", "PS Vita", "PSP"]
PATTERNS = ["{m} fiyat", "{m} satılık", "{m} ikinci el"]


def _quota_ok(engine, n_more):
    period, limit = LIMITS[engine]
    key = date.today().strftime("%Y-%m" if period == "month" else "%Y-%m-%d")
    q = json.loads(QUOTA.read_text()) if QUOTA.exists() else {}
    used = q.get(engine, {}).get(key, 0)
    return used, limit, key, q, used + n_more <= limit


def _bump(engine, key, q):
    q.setdefault(engine, {})[key] = q.get(engine, {}).get(key, 0) + 1
    QUOTA.write_text(json.dumps(q, indent=1))


def brave(query):
    url = "https://api.search.brave.com/res/v1/web/search?" + urllib.parse.urlencode(
        {"q": query, "country": "TR", "search_lang": "tr", "count": 20})
    key = os.environ.get("BRAVE_API_KEY", "injected-by-proxy")
    req = urllib.request.Request(url, headers={"Accept": "application/json", "X-Subscription-Token": key})
    data = json.load(urllib.request.urlopen(req, timeout=30))
    for r in data.get("web", {}).get("results", []):
        yield r.get("title", ""), r.get("url", ""), " ".join([r.get("description", "")] + r.get("extra_snippets", []))


def google(query):
    cx = os.environ.get("GOOGLE_CSE_CX")
    if not cx:
        raise SystemExit("set GOOGLE_CSE_CX to your Programmable Search Engine ID")
    url = "https://customsearch.googleapis.com/customsearch/v1?" + urllib.parse.urlencode(
        {"q": query, "cx": cx, "gl": "tr", "lr": "lang_tr", "num": 10})
    key = os.environ.get("GOOGLE_API_KEY", "injected-by-proxy")
    req = urllib.request.Request(url, headers={"X-goog-api-key": key})
    data = json.load(urllib.request.urlopen(req, timeout=30))
    for r in data.get("items", []):
        yield r.get("title", ""), r.get("link", ""), r.get("snippet", "")


def first_price(text):
    m = TL.search(text or "")
    if not m:
        return None
    v = float(m.group(1).replace(".", "") + "." + (m.group(2) or "0"))
    return v if v >= 100 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", choices=["brave", "google"], default="brave")
    ap.add_argument("--max", type=int, default=100, help="max queries this run (quota-checked)")
    a = ap.parse_args()
    queries = [p.format(m=m) for m in MODELS for p in PATTERNS][:a.max]
    used, limit, key, q, ok = _quota_ok(a.engine, len(queries))
    if not ok:
        queries = queries[:max(0, limit - used)]
        print(f"quota: {used}/{limit} used this {LIMITS[a.engine][0]}; running only {len(queries)} queries")
    fn = brave if a.engine == "brave" else google
    seen = set()
    if OUT.exists():
        seen = {json.loads(l)["url"] for l in OUT.open(encoding="utf-8")}
    n_rows = 0
    with OUT.open("a", encoding="utf-8") as f:
        for query in queries:
            try:
                results = list(fn(query))
            except urllib.error.HTTPError as e:
                print(f"{a.engine} HTTP {e.code} on '{query}' -> stopping (check key / quota)")
                break
            _bump(a.engine, key, q)
            for title, url, snippet in results:
                host = urllib.parse.urlparse(url).netloc.removeprefix("www.")
                if not url or url in seen or SKIP_DOMAINS.search(host):
                    continue
                model = classify(f"{title} {snippet}")[0]
                price = first_price(f"{title} {snippet}")
                if not model or not price:
                    continue
                seen.add(url)
                f.write(json.dumps({"source": host, "engine": a.engine, "model": model, "title": title,
                                    "price_try": price, "url": url, "query": query,
                                    "seen_date": datetime.now(timezone.utc).date().isoformat()},
                                   ensure_ascii=False) + "\n")
                n_rows += 1
            time.sleep(1.1 if a.engine == "brave" else 0.5)   # Brave free tier: 1 query/s
    print(f"{len(queries)} queries, {n_rows} new priced rows -> {OUT}")


if __name__ == "__main__":
    main()
