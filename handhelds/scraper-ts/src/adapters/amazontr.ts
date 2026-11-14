// Port of ProductScrape's amazontr.py, keeping its behaviour:
//  * search URL https://www.amazon.com.tr/s?k=<query>&page=N
//  * keep only organic result links (href contains "keywords"), skip "slredirect" ads and /gp/ links
//  * ASIN taken from the link ("dp/<ASIN>/"), deduplicated
//  * every product page is opened for #productTitle and the first .a-offscreen price
//    ("Not available, please view the product page" when no TL price -> priceTry null)
// Improvements: bounded worker pool instead of one unjoined thread per product, card
// price/title as fallback when a product page fails, numeric prices, no UA rotation.
import * as cheerio from "cheerio";
import { cacheConfig } from "../cache.js";
import { fetchText, pool } from "../http.js";
import { Adapter, Product, parsePrice } from "../types.js";

const BASE = "https://www.amazon.com.tr";

export function amazonSearchUrl(query: string, page = 1): string {
  const q = encodeURIComponent(query).replace(/%20/g, "+");
  return `${BASE}/s?k=${q}${page > 1 ? `&page=${page}` : ""}`;
}

/** amazontr.py asincode(): unquote, split on "dp/", take the next path segment. */
export function asinCode(link: string): string | null {
  const parts = decodeURIComponent(link).split("dp/");
  return parts[1] ? parts[1].split(/[/?]/)[0] : null;
}

export function parseAmazonSearch(html: string): Omit<Product, "scrapedAt">[] {
  const $ = cheerio.load(html);
  const rows: Omit<Product, "scrapedAt">[] = [];
  const push = (asin: string, title: string, priceText: string) => {
    if (!asin || !title || rows.some((r) => r.id === asin)) return;
    rows.push({ source: "amazontr", id: asin, title, priceTry: parsePrice(priceText),
                inStock: null, url: `${BASE}/dp/${asin}`, seller: null, condition: "new" });
  };
  // 1) amazontr.py's rule: organic links carry "keywords" in the href
  $("a[href]").each((_, a) => {
    const href = $(a).attr("href")!;
    if (!href.includes("keywords") || href.includes("slredirect") || href.includes(".tr/gp/") ||
        href.startsWith("/gp/") || !href.includes("dp/")) return;
    const card = $(a).closest("[data-asin]");
    push(asinCode(href) ?? "", (card.find("h2").text() || $(a).text()).trim(),
         card.find(".a-price .a-offscreen").first().text());
  });
  // 2) fallback: result cards by data-asin
  if (!rows.length) {
    $("div[data-asin]").each((_, el) => {
      const link = $(el).find("a[href*='/dp/']").first().attr("href");
      if (!link || link.includes("slredirect")) return;
      push($(el).attr("data-asin") ?? "", $(el).find("h2").text().trim(),
           $(el).find(".a-price .a-offscreen").first().text());
    });
  }
  return rows;
}

/** amazontr.py detailedparser(): title from #productTitle, price from first .a-offscreen. */
export function parseAmazonProduct(html: string): { title: string | null; price: number | null } {
  const $ = cheerio.load(html);
  const priceText = $("span.a-offscreen").first().text();
  return {
    title: $("#productTitle").text().trim() || null,
    price: priceText.includes("TL") || priceText.includes("₺") ? parsePrice(priceText) : null,
  };
}

export function amazonTr(opts: { details?: boolean; threads?: number } = {}): Adapter {
  const { details = true, threads = 3 } = opts;
  return {
    name: "amazontr",
    async search(query, limit) {
      const found: Omit<Product, "scrapedAt">[] = [];
      for (let page = 1; page <= 20 && found.length < limit; page++) {
        const rows = parseAmazonSearch(await fetchText(amazonSearchUrl(query, page)));
        if (!rows.length) break;
        for (const r of rows) if (!found.some((o) => o.id === r.id)) found.push(r);
      }
      const picked = found.slice(0, limit);
      return pool(picked, details ? threads : picked.length, async (r): Promise<Product> => {
        const now = new Date().toISOString();
        if (!details) return { ...r, scrapedAt: now };
        try {
          const d = parseAmazonProduct(await fetchText(r.url, { maxAgeMs: cacheConfig.itemMaxAgeMs }));
          return { ...r, title: d.title ?? r.title, priceTry: d.price ?? r.priceTry, scrapedAt: now };
        } catch (e) {
          console.warn(`  amazontr ${r.id}: product page failed (${(e as Error).message}), using search card`);
          return { ...r, scrapedAt: now };
        }
      });
    },
  };
}

export const amazontr = amazonTr();
