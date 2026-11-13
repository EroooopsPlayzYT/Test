"""Run ProductScrape's trendyol.py UNMODIFIED and print its results as JSON.

Usage: python3 run_trendyol.py <query> <limit> <workdir> [listing_url]
With listing_url (a category/brand page, allowed by robots.txt) the unmodified
Trendyol.parser() is pointed at it instead of the /sr search URL from Trendyol.link().
trendyol.py (git submodule at handhelds/vendor/ProductScrape) is imported as-is.
This wrapper only adds what sits around it:
  * runs inside <workdir>, because trendyol.py writes resultstrendyol.json to the cwd on import;
  * waits for every worker thread, since Trendyol.parser() joins only the last one it started;
  * reports errors (e.g. HTTP 403) as JSON instead of a traceback.
"""
import json
import os
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.normpath(os.path.join(HERE, "..", "..", "vendor", "ProductScrape"))


def main(query, limit, workdir, listing_url=None):
    os.makedirs(workdir, exist_ok=True)
    os.chdir(workdir)
    sys.dont_write_bytecode = True  # keep the submodule checkout pristine
    sys.path.insert(0, VENDOR)
    import trendyol  # noqa: E402  (the original module, untouched)

    # parser() appends "&pi=N" for later pages, so a listing URL is given a "?pi=1" to append to.
    url = (listing_url if "?" in listing_url else listing_url + "?pi=1") if listing_url \
        else trendyol.Trendyol(query).link()
    error = None
    try:
        trendyol.Trendyol.parser(url, limit)
    except UnboundLocalError as e:
        # trendyol.py calls twrv.join() even when no product card was found (blocked page,
        # challenge page or changed markup), which raises here.
        error = f"no product cards found on the page ({e})"
    except Exception as e:  # e.g. urllib.error.HTTPError 403 from Cloudflare
        error = f"{type(e).__name__}: {e}"
    for t in threading.enumerate():
        if t is not threading.current_thread():
            t.join(timeout=60)
    trendyol.outfile.flush()
    cards = [c.__dict__ for c in trendyol.trendyollist]
    print(json.dumps({"url": url, "error": error, "products": cards}, ensure_ascii=False))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
