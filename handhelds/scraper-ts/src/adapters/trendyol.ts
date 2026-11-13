// Port of ProductScrape's trendyol.py.
// Changes: no User-Agent rotation (one honest UA; a block stops the run instead of being
// worked around), reads the search page's embedded JSON / JSON-LD instead of 2021 CSS
// class names, and skips the extra per-product request (the search page has id + price).
// From the cloud server this was built on, Trendyol answers 403 (Cloudflare), so this
// adapter could not be tested end to end; from a home connection it may work.
import * as cheerio from "cheerio";
import { fetchText } from "../http.js";
import { productsFromJsonLd } from "../jsonld.js";
import { Adapter, Product, parsePrice } from "../types.js";

const BASE = "https://www.trendyol.com";
const PER_PAGE = 24;

export function trendyolSearchUrl(query: string, page = 1): string {
  // ProductScrape test_case.py: "kitap" -> https://www.trendyol.com/sr?q=kitap
  const q = encodeURIComponent(query).replace(/%20/g, "+");
  return `${BASE}/sr?q=${q}${page > 1 ? `&pi=${page}` : ""}`;
}

export function parseTrendyolSearch(html: string): Omit<Product, "scrapedAt">[] {
  const rows: Omit<Product, "scrapedAt">[] = [];
  // 1) embedded app state, when present
  const state = html.match(/__SEARCH_APP_INITIAL_STATE__\s*=\s*(\{.*?\});\s*<\/script>/s);
  if (state) {
    try {
      const data = JSON.parse(state[1]);
      for (const p of data.products ?? []) {
        rows.push({ source: "trendyol", id: String(p.id), title: [p.brand?.name, p.name].filter(Boolean).join(" "),
                    priceTry: parsePrice(p.price?.sellingPrice ?? p.price?.discountedPrice),
                    inStock: true, url: new URL(p.url, BASE).href, seller: p.merchantName ?? null, condition: "new" });
      }
    } catch { /* fall through */ }
  }
  // 2) JSON-LD ItemList / Product
  if (!rows.length) {
    for (const p of productsFromJsonLd(html)) {
      rows.push({ source: "trendyol", id: p.sku ?? p.name, title: p.name, priceTry: p.price,
                  inStock: p.inStock, url: p.offers[0]?.url ?? BASE, seller: null, condition: "new" });
    }
  }
  // 3) plain product links: /brand/name-p-123456
  if (!rows.length) {
    const $ = cheerio.load(html);
    $('a[href*="-p-"]').each((_, a) => {
      const href = $(a).attr("href")!;
      const id = href.match(/-p-(\d+)/)?.[1];
      if (!id || rows.some((r) => r.id === id)) return;
      rows.push({ source: "trendyol", id, title: $(a).attr("title") ?? $(a).text().trim(),
                  priceTry: parsePrice($(a).find('[class*="price"]').first().text()),
                  inStock: null, url: new URL(href, BASE).href, seller: null, condition: "new" });
    });
  }
  return rows;
}

export const trendyol: Adapter = {
  name: "trendyol-ts",
  async search(query, limit) {
    const out: Product[] = [];
    const pages = Math.min(Math.ceil(limit / PER_PAGE), 208); // ProductScrape: max 208 pages
    for (let page = 1; page <= pages && out.length < limit; page++) {
      const now = new Date().toISOString();
      const rows = parseTrendyolSearch(await fetchText(trendyolSearchUrl(query, page)));
      if (!rows.length) break;
      for (const r of rows) if (!out.some((o) => o.id === r.id)) out.push({ ...r, scrapedAt: now });
    }
    return out.slice(0, limit);
  },
};
