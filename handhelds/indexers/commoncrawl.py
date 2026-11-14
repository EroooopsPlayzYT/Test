"""Common Crawl discovery + history, following https://commoncrawl.org/get-started:

* search the **URL Index** (columnar Parquet) with DuckDB over https://data.commoncrawl.org,
  not the rate-limited CDX server; one thread, a descriptive User-Agent, sleeps between calls;
* fetch page content as **byte ranges** from data.commoncrawl.org (WARC records);
* content is fetched ONLY for domains on OPEN_DOMAINS: sites we can read directly and whose
  robots.txt allows it. Sites that block us (Trendyol, Hepsiburada, sahibinden…) are discovery-only:
  we record that a URL existed and when, never its archived content.

Usage (from handhelds/):
  python3 -m indexers.commoncrawl discover --crawls 3            # all .tr sites + known .com shops
  python3 -m indexers.commoncrawl hosts --limit 60               # which hosts are open to us (+robots)
  python3 -m indexers.commoncrawl history --limit 500            # archived prices, open hosts only
"""
import argparse
import gzip
import json
import re
import sqlite3
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import duckdb

UA = "handheld-price-notebook/1.0 (personal research; polite; contact via repo)"
DATA = "https://data.commoncrawl.org/"
OUT = Path(__file__).resolve().parent.parent / "crawler" / "out"
B = r"(^|[/_-])"   # token must start a URL word ("ayn-" not "kayn-", "el-konsol" not "panel-konsol")
HANDHELD = (r"steam-?deck|rog-?ally|xbox-?ally|legion-?go|msi-?claw|ayaneo|" + B + r"ayn-|odin-?[23]|retroid|anbernic|"
            r"anbernic|" + B + r"rg-?(28xx|34xx|35xx|40xx|405|406|476|477|55g|556|557|cubexx|ds|slide)|miyoo|powkiddy|"
            r"r36s|trimui|nintendo-?switch|switch-?(lite|oled)-|playstation-?portal|ps-?vita|" + B + r"psp-|"
            + B + r"el-?konsol|retro-?oyun-?konsol|tasinabilir-?oyun-?konsol")
# discovery covers every .tr site (contiguous SURT range 'tr,') plus these .com domains
COM_DOMAINS = ["akakce.com", "trendyol.com", "hepsiburada.com", "n11.com", "idefix.com", "teknosa.com",
               "vatanbilgisayar.com", "mediamarkt.com.tr", "pazarama.com", "pttavm.com", "itopya.com",
               "incehesap.com", "sinerji.gen.tr", "donanimhaber.com", "r10.net", "ucuzbudur.com",
               "360teknoloji.com", "pellteknoloji.com", "konsolcum.com", "overgameweb.com", "badem.shop"]
# content may be read only for these (open to us directly + robots allows)
OPEN_DOMAINS = {"gaming.gen.tr", "forum.donanimhaber.com", "www.r10.net", "www.idefix.com", "www.akakce.com"}
NEVER_CONTENT = re.compile(r"trendyol|hepsiburada|sahibinden|n11\.com|pttavm|teknosa|vatanbilgisayar|mediamarkt|"
                           r"itopya|incehesap|cimri|epey")


def surt_ranges():
    rng = [("tr,", "tr-")]                                   # every .tr host
    for d in COM_DOMAINS:
        parts = d.split(".")
        key = ",".join(reversed(parts)) + ")"
        rng.append((key, key + "~"))
    return rng


def latest_crawls(n):
    req = urllib.request.Request("https://index.commoncrawl.org/collinfo.json", headers={"User-Agent": UA})
    try:
        return [c["id"] for c in json.load(urllib.request.urlopen(req, timeout=60))[:n]]
    except Exception:
        # index host is often overloaded; fall back to the paths listing on the data host
        raise SystemExit("could not list crawls (index.commoncrawl.org busy); pass --crawl CC-MAIN-YYYY-WW")


def index_files(crawl):
    req = urllib.request.Request(f"{DATA}crawl-data/{crawl}/cc-index-table.paths.gz", headers={"User-Agent": UA})
    paths = gzip.decompress(urllib.request.urlopen(req, timeout=120).read()).decode().split()
    return [DATA + p for p in paths if "subset=warc" in p]


def discover(crawls):
    con = duckdb.connect(config={"custom_user_agent": UA})
    con.execute("INSTALL httpfs; LOAD httpfs; SET http_retries=3;")
    rng = surt_ranges()
    cond = " OR ".join(f"(url_surtkey >= '{a}' AND url_surtkey < '{b}')" for a, b in rng)
    stat = " OR ".join(f"(stats_min_value <= '{b}' AND stats_max_value >= '{a}')" for a, b in rng)
    OUT.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(OUT / "commoncrawl.db")
    db.execute("""CREATE TABLE IF NOT EXISTS cc_urls (crawl TEXT, host TEXT, url TEXT, fetch_time TEXT,
                  status INTEGER, mime TEXT, warc_filename TEXT, warc_offset INTEGER, warc_length INTEGER,
                  PRIMARY KEY (crawl, url))""")
    for crawl in crawls:
        t = time.time()
        db.execute("DELETE FROM cc_urls WHERE crawl = ?", (crawl,))   # re-running a crawl replaces it
        files = index_files(crawl)
        # row-group statistics only: find the few files that hold our SURT ranges
        hit = [r[0] for r in con.execute(
            f"SELECT DISTINCT file_name FROM parquet_metadata(?) WHERE path_in_schema='url_surtkey' AND ({stat})",
            [files]).fetchall()]
        rows = con.execute(f"""
            SELECT url_host_name, url, CAST(fetch_time AS VARCHAR), fetch_status, content_mime_type,
                   warc_filename, warc_record_offset, warc_record_length
            FROM read_parquet(?) WHERE ({cond}) AND fetch_status = 200
              AND regexp_matches(lower(url_path), '{HANDHELD}')""", [hit]).fetchall()   # path only, not domain
        db.executemany("INSERT OR IGNORE INTO cc_urls VALUES (?,?,?,?,?,?,?,?,?)", [(crawl, *r) for r in rows])
        db.commit()
        print(f"{crawl}: {len(hit)}/{len(files)} index files scanned, {len(rows)} handheld URLs "
              f"({time.time() - t:.0f}s)")
        time.sleep(5)   # be nice between crawls
    for host, n in db.execute("SELECT host, count(*) FROM cc_urls GROUP BY 1 ORDER BY 2 DESC LIMIT 25"):
        print(f"  {n:5d}  {host}")


def check_hosts(limit=60):
    """Which discovered hosts are open to us? Reachable without a block page AND robots.txt allows a
    sample handheld URL for our User-Agent. Only those get archived content read in `history`."""
    import urllib.robotparser
    db = sqlite3.connect(OUT / "commoncrawl.db")
    db.execute("""CREATE TABLE IF NOT EXISTS cc_hosts (host TEXT PRIMARY KEY, open INTEGER, reason TEXT,
                  checked_at TEXT)""")
    known = {h for (h,) in db.execute("SELECT host FROM cc_hosts")}
    hosts = [r for r in db.execute("SELECT host, min(url), count(*) n FROM cc_urls GROUP BY host ORDER BY n DESC")
             if r[0] not in known][:limit]
    for host, sample, n in hosts:
        reason, ok = "", False
        if NEVER_CONTENT.search(host):
            reason = "blocks bots (content never read)"
        else:
            try:
                req = urllib.request.Request(f"https://{host}/", headers={"User-Agent": UA})
                body = urllib.request.urlopen(req, timeout=20).read(20000).decode("utf-8", "ignore")
                if re.search(r"Just a moment|Attention Required|captcha", body, re.I):
                    reason = "challenge page"
                else:
                    rp = urllib.robotparser.RobotFileParser(f"https://{host}/robots.txt")
                    rp.read()
                    ok = rp.can_fetch(UA, sample) and rp.can_fetch("*", sample)
                    reason = "ok" if ok else "robots.txt disallows"
            except Exception as e:
                reason = f"unreachable: {type(e).__name__}"
        db.execute("INSERT OR REPLACE INTO cc_hosts VALUES (?,?,?,?)",
                   (host, int(ok), reason, datetime.now(timezone.utc).isoformat()))
        db.commit()
        print(f"  {'OPEN ' if ok else 'skip '} {host:<32} {n:4d} urls  {reason}")
        time.sleep(2)


def open_domains(db):
    try:
        return OPEN_DOMAINS | {h for (h,) in db.execute("SELECT host FROM cc_hosts WHERE open = 1")}
    except sqlite3.Error:
        return set(OPEN_DOMAINS)


def _iso(ts):
    """'2026-08-09 15:52:31+00' (DuckDB) -> '2026-08-09T15:52:31+00:00' (same format as the scrapers)."""
    try:
        return datetime.fromisoformat(ts.replace("+00", "+00:00") if ts.endswith("+00") else ts).isoformat()
    except ValueError:
        return ts


def _record(fn, off, ln):
    req = urllib.request.Request(DATA + fn, headers={"User-Agent": UA, "Range": f"bytes={off}-{off + ln - 1}"})
    raw = gzip.decompress(urllib.request.urlopen(req, timeout=60).read())
    return raw.split(b"\r\n\r\n", 2)[-1].decode("utf-8", "replace")


def history(limit):
    """Archived product/thread pages from OPEN_DOMAINS -> observations dated by crawl fetch_time."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from crawler.handheld_crawler.jsonld import tr_price
    db = sqlite3.connect(OUT / "commoncrawl.db")
    db.execute("""CREATE TABLE IF NOT EXISTS observations (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL,
                  product_id TEXT NOT NULL, title TEXT NOT NULL, price_try REAL, in_stock INTEGER, seller TEXT,
                  condition TEXT, url TEXT NOT NULL, query TEXT, scraped_at TEXT NOT NULL)""")
    # fix rows written before timestamps were normalised
    for rid, ts in db.execute("SELECT id, scraped_at FROM observations WHERE scraped_at NOT LIKE '%T%'").fetchall():
        db.execute("UPDATE observations SET scraped_at = ? WHERE id = ?", (_iso(ts), rid))
    db.commit()
    done = {(u, _iso(t)) for u, t in db.execute("SELECT url, scraped_at FROM observations")}
    allowed = open_domains(db)
    todo = [r for r in db.execute("SELECT host, url, fetch_time, warc_filename, warc_offset, warc_length "
                                  "FROM cc_urls ORDER BY fetch_time")
            if r[0] in allowed and not NEVER_CONTENT.search(r[0]) and (r[1], _iso(r[2])) not in done][:limit]
    print(f"{len(todo)} archived pages to read (open domains only)")
    for host, url, ft, fn, off, ln in todo:
        try:
            html = _record(fn, off, ln)
        except Exception as e:
            print("  skip", url, e)
            time.sleep(2)
            continue
        name, price, avail = None, None, ""
        for blob in re.findall(r'<script[^>]*ld\+json[^>]*>(.*?)</script>', html, re.S):
            try:
                d = json.loads(blob)
            except ValueError:
                continue
            for node in (d if isinstance(d, list) else [d]):
                if isinstance(node, dict) and node.get("@type") == "Product":
                    o = node.get("offers") or {}
                    o = o[0] if isinstance(o, list) and o else o
                    name, price, avail = node.get("name"), tr_price(o.get("price") or o.get("lowPrice")), \
                        str(o.get("availability", ""))
        if name:
            db.execute("INSERT INTO observations (source, product_id, title, price_try, in_stock, seller, condition, "
                       "url, query, scraped_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                       (f"commoncrawl:{host}", url, name.strip(), price,
                        None if not avail else int("InStock" in avail), host,
                        "used" if "donanimhaber" in host or "r10" in host else "new", url, "cc-history", _iso(ft)))
            db.commit()
        time.sleep(1.5)   # polite to data.commoncrawl.org
    print("observations:", db.execute("SELECT count(*) FROM observations").fetchone()[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["discover", "hosts", "history"])
    ap.add_argument("--crawls", type=int, default=3)
    ap.add_argument("--crawl", action="append", help="explicit crawl id(s), e.g. CC-MAIN-2026-43")
    ap.add_argument("--limit", type=int, default=500)
    a = ap.parse_args()
    if a.cmd == "discover":
        discover(a.crawl or latest_crawls(a.crawls))
    elif a.cmd == "hosts":
        check_hosts(a.limit)
    else:
        history(a.limit)


if __name__ == "__main__":
    main()
