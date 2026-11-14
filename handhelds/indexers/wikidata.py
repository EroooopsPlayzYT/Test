"""Non-scraping catalogue source: handheld specs from Wikidata (CC0), via the public SPARQL endpoint.

Class-based queries are unreliable for modern consoles (they are typed 'product model'), so we
look models up by label. One request, descriptive User-Agent, results cached in data/wikidata.json.
Usage (from handhelds/): python3 -m indexers.wikidata
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

UA = "handheld-price-notebook/1.0 (personal research)"
OUT = Path(__file__).resolve().parent.parent / "data" / "wikidata.json"
NAMES = ["Steam Deck", "Nintendo Switch", "Nintendo Switch OLED Model", "Nintendo Switch Lite", "Nintendo Switch 2",
         "ROG Ally", "ROG Ally X", "Lenovo Legion Go", "Legion Go S", "MSI Claw", "PlayStation Portal",
         "PlayStation Vita", "PlayStation Portable", "Nintendo 3DS", "New Nintendo 3DS XL", "Nintendo 2DS",
         "Retroid Pocket", "Anbernic", "Ayaneo", "Miyoo Mini", "AYN Odin", "Game Boy Advance SP"]

QUERY = """
SELECT ?item ?itemLabel ?date ?mfrLabel ?cpuLabel ?ram ?storage ?price WHERE {
  VALUES ?name { %s }
  ?item rdfs:label ?name .
  OPTIONAL { ?item wdt:P577 ?date }
  OPTIONAL { ?item wdt:P176 ?mfr }
  OPTIONAL { ?item wdt:P880 ?cpu }
  OPTIONAL { ?item wdt:P2928 ?ram }
  OPTIONAL { ?item wdt:P4388 ?storage }
  OPTIONAL { ?item wdt:P2284 ?price }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
} LIMIT 200
"""


def main():
    values = " ".join(f'"{n}"@en' for n in NAMES)
    url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": QUERY % values, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    for attempt in range(3):
        try:
            rows = json.load(urllib.request.urlopen(req, timeout=90))["results"]["bindings"]
            break
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 2:
                raise
            wait = int(e.headers.get("Retry-After") or 70)   # WDQS asks for 1 req/min during outages
            print(f"rate limited by Wikidata, waiting {wait}s")
            time.sleep(wait)
    out = {}
    for r in rows:
        get = lambda k: r.get(k, {}).get("value")
        e = out.setdefault(get("item"), {"name": get("itemLabel"), "release": None, "manufacturer": None,
                                         "cpu": None, "ram": None, "launch_price": None, "wikidata": get("item")})
        e["release"] = e["release"] or (get("date") or "")[:10] or None
        e["manufacturer"] = e["manufacturer"] or get("mfrLabel")
        e["cpu"] = e["cpu"] or get("cpuLabel")
        e["ram"] = e["ram"] or get("ram")
        e["launch_price"] = e["launch_price"] or get("price")
    OUT.write_text(json.dumps(list(out.values()), ensure_ascii=False, indent=1), encoding="utf-8")
    for e in out.values():
        print(f"{e['name']:<32} {e['release'] or '':<11} {e['manufacturer'] or '':<22} {e['cpu'] or ''}")
    print(f"{len(out)} models -> {OUT}")


if __name__ == "__main__":
    main()
