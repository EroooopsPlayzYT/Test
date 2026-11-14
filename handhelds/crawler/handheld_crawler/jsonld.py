"""schema.org Product extraction shared by spiders."""
import json
import re

TL = re.compile(r"(\d{1,3}(?:[.\s]\d{3})+|\d+)(?:,(\d{1,2}))?\s*(?:TL|₺)", re.I)


def tr_price(v):
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v) if v > 0 else None
    s = str(v).strip()
    m = TL.search(s)
    if m:
        return float(re.sub(r"[.\s]", "", m.group(1)) + "." + (m.group(2) or "0"))
    s = re.sub(r"[^\d.,]", "", s)
    if not s:
        return None
    if "," in s:
        s = s.replace(".", "").replace(",", ".")
    try:
        n = float(s)
        return n if n > 0 else None
    except ValueError:
        return None


def _walk(node):
    if isinstance(node, list):
        for n in node:
            yield from _walk(n)
    elif isinstance(node, dict):
        yield node
        for v in node.values():
            if isinstance(v, (dict, list)):
                yield from _walk(v)


def products(response):
    """Yield dicts: name, price, availability, condition, sku from JSON-LD Product blocks."""
    for blob in response.xpath('//script[@type="application/ld+json"]/text()').getall():
        try:
            data = json.loads(blob)
        except ValueError:
            continue
        for d in _walk(data):
            t = d.get("@type")
            if t != "Product" and not (isinstance(t, list) and "Product" in t):
                continue
            offers = d.get("offers") or {}
            if isinstance(offers, list):
                offers = offers[0] if offers else {}
            yield {
                "name": (d.get("name") or "").strip(),
                "sku": d.get("sku"),
                "price": tr_price(offers.get("price") or offers.get("lowPrice")),
                "availability": str(offers.get("availability") or ""),
                "condition": "used" if "Used" in str(offers.get("itemCondition") or "")
                             else "new" if "New" in str(offers.get("itemCondition") or "") else None,
            }
