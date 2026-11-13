"""Search and statistics algorithms used for deal finding.

Binary search (lower_bound / upper_bound) on sorted price lists answers:
  * how many comparable listings cost at most my budget, and which ones   -> under_budget()
  * what share of comparable listings are cheaper than this one           -> percentile_rank()
  * which listings fall in a price band                                   -> price_band()
  * the cheapest price that still clears a deal threshold                 -> max_price_for_deal()
All are O(log n) per query after one O(n log n) sort.
"""
from statistics import median


def lower_bound(a, x, key=lambda v: v):
    """First index i with key(a[i]) >= x (a sorted ascending by key). Classic binary search."""
    lo, hi = 0, len(a)
    while lo < hi:
        mid = (lo + hi) // 2
        if key(a[mid]) < x:
            lo = mid + 1
        else:
            hi = mid
    return lo


def upper_bound(a, x, key=lambda v: v):
    """First index i with key(a[i]) > x."""
    lo, hi = 0, len(a)
    while lo < hi:
        mid = (lo + hi) // 2
        if key(a[mid]) <= x:
            lo = mid + 1
        else:
            hi = mid
    return lo


def under_budget(sorted_rows, budget, key=lambda r: r["price_try"]):
    """Rows priced <= budget (sorted_rows ascending by price)."""
    return sorted_rows[:upper_bound(sorted_rows, budget, key)]


def price_band(sorted_rows, lo_price, hi_price, key=lambda r: r["price_try"]):
    """Rows with lo_price <= price <= hi_price."""
    return sorted_rows[lower_bound(sorted_rows, lo_price, key):upper_bound(sorted_rows, hi_price, key)]


def percentile_rank(sorted_prices, price):
    """Share of prices strictly below `price` (0.0 = cheapest, 1.0 = dearest)."""
    if not sorted_prices:
        return None
    return lower_bound(sorted_prices, price) / len(sorted_prices)


def max_price_for_deal(fair_price, min_discount, lo=0.0, hi=None, tol=1.0):
    """Binary search on price: the highest price whose discount vs fair_price is still >= min_discount.
    (Closed form exists; kept as a numeric search so any monotone scoring function can be swapped in.)"""
    hi = fair_price if hi is None else hi
    score = lambda p: (fair_price - p) / fair_price
    while hi - lo > tol:
        mid = (lo + hi) / 2
        if score(mid) >= min_discount:
            lo = mid
        else:
            hi = mid
    return round(lo, 2)


def iqr_bounds(prices, k=1.5):
    """Tukey fences; prices outside are outliers (mispriced, accessory, placeholder)."""
    s = sorted(prices)
    if len(s) < 4:
        return None, None
    q1, q3 = s[len(s) // 4], s[(3 * len(s)) // 4]
    iqr = q3 - q1
    return q1 - k * iqr, q3 + k * iqr


def group_stats(prices):
    s = sorted(p for p in prices if p is not None)
    if not s:
        return {"n": 0, "median": None, "min": None, "max": None}
    return {"n": len(s), "median": median(s), "min": s[0], "max": s[-1]}


def dedupe_key(row):
    """Same product, same seller/source, same price -> one row."""
    return (row.get("model"), row.get("variant"), row.get("source"), round(row.get("price_try") or 0),
            row.get("condition"))
