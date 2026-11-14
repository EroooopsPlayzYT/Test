"""Other web archives, used the same way as Common Crawl: discovery for any host, archived
content only for hosts marked open in crawler/out/commoncrawl.db (cc_hosts.open = 1).

* Arquivo.pt CDX   https://arquivo.pt/wayback/cdx?url=<host>/*&output=json   (free, no key)
* Wayback "available" API   https://archive.org/wayback/available?url=<url>   (closest snapshot)
  (the Wayback CDX endpoint was resetting connections from the cloud session; it may work from home)

Usage (from handhelds/):  python3 -m indexers.archives --limit 30
Writes archive_snapshots (host, url, timestamp, archive, replay_url) into crawler/out/commoncrawl.db.
"""
import argparse
import json
import re
import sqlite3
import time
import urllib.parse
import urllib.request
from pathlib import Path

from .commoncrawl import HANDHELD, NEVER_CONTENT, OUT, UA


def get_json(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    raw = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "replace").strip()
    if not raw:
        return []
    try:
        return json.loads(raw)
    except ValueError:   # Arquivo CDX returns JSON lines
        return [json.loads(l) for l in raw.splitlines() if l.strip()]


def arquivo(host, limit=200):
    url = "https://arquivo.pt/wayback/cdx?" + urllib.parse.urlencode(
        {"url": f"{host}/*", "output": "json", "limit": limit, "filter": "status:200"})
    for r in get_json(url):
        if isinstance(r, dict) and re.search(HANDHELD, urllib.parse.urlparse(r.get("url", "")).path.lower()):
            ts = r["timestamp"]
            yield r["url"], ts, "arquivo.pt", f"https://arquivo.pt/wayback/{ts}/{r['url']}"


def wayback_available(url):
    data = get_json("https://archive.org/wayback/available?" + urllib.parse.urlencode({"url": url}), timeout=30)
    snap = (data or {}).get("archived_snapshots", {}).get("closest") if isinstance(data, dict) else None
    if snap and snap.get("available"):
        yield url, snap["timestamp"], "wayback", snap["url"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=30, help="hosts to look up")
    a = ap.parse_args()
    db = sqlite3.connect(Path(OUT) / "commoncrawl.db")
    db.execute("""CREATE TABLE IF NOT EXISTS archive_snapshots (host TEXT, url TEXT, timestamp TEXT, archive TEXT,
                  replay_url TEXT, PRIMARY KEY (archive, url, timestamp))""")
    hosts = [h for (h,) in db.execute("SELECT host FROM cc_hosts WHERE open = 1 ORDER BY host")][:a.limit]
    for host in hosts:
        if NEVER_CONTENT.search(host):
            continue
        rows = []
        try:
            rows += list(arquivo(host))
        except Exception as e:
            print(f"  arquivo {host}: {type(e).__name__}")
        time.sleep(3)
        # one Wayback lookup per host for its most-seen handheld URL in Common Crawl
        top = db.execute("SELECT url FROM cc_urls WHERE host = ? GROUP BY url ORDER BY count(*) DESC LIMIT 1",
                         (host,)).fetchone()
        if top:
            try:
                rows += list(wayback_available(top[0]))
            except Exception as e:
                print(f"  wayback {host}: {type(e).__name__}")
            time.sleep(3)
        db.executemany("INSERT OR IGNORE INTO archive_snapshots VALUES (?,?,?,?,?)",
                       [(host, u, t, arc, rep) for u, t, arc, rep in rows])
        db.commit()
        print(f"  {host:<32} {len(rows)} snapshots")
    print("total:", db.execute("SELECT archive, count(*) FROM archive_snapshots GROUP BY 1").fetchall())


if __name__ == "__main__":
    main()
