// Port of ProductScrape's amazontr.py.
// Changes: no User-Agent rotation, reads price/title straight from the search result
// cards (div[data-asin]) instead of opening every product page, dedupes by ASIN, and
// skips sponsored "slredirect" links like the original. From the cloud server this was
// built on, Amazon TR search returns 503, so it is untested end to end.
import * as cheerio from "cheerio";
import { fetchText } from "../http.js";
import { Adapter, Product, parsePrice } from "../types.js";

const BASE = "https://www.amazon.com.tr";

export function amazonSearchUrl(query: string, page = 1): string {
  const q = encodeURIComponent(query).replace(/%20/g, "+");
  return `${BASE}/s?k=${q}${page > 1 ? `&page=${page}` : ""}`;
}

export function parseAmazonSearch(html: string): Omit<Product, "scrapedAt">[] {
  const $ = cheerio.load(html);
  const rows: Omit<Product, "scrapedAt">[] = [];
  $("div[data-asin]").each((_, el) => {
    const asin = $(el).attr("data-asin");
    if (!asin || rows.some((r) => r.id === asin)) return;
    const link = $(el).find("a[href*='/dp/']").first().attr("href");
    if (!link || link.includes("slredirect")) return;
    const title = $(el).find("h2").text().trim();
    if (!title) return;
    rows.push({ source: "amazontr", id: asin, title,
                priceTry: parsePrice($(el).find(".a-price .a-offscreen").first().text()),
                inStock: null, url: `${BASE}/dp/${asin}`, seller: null, condition: "new" });
  });
  return rows;
}

export const amazontr: Adapter = {
  name: "amazontr",
  async search(query, limit) {
    const out: Product[] = [];
    for (let page = 1; page <= 20 && out.length < limit; page++) {
      const now = new Date().toISOString();
      const rows = parseAmazonSearch(await fetchText(amazonSearchUrl(query, page)));
      if (!rows.length) break;
      for (const r of rows) if (!out.some((o) => o.id === r.id)) out.push({ ...r, scrapedAt: now });
    }
    return out.slice(0, limit);
  },
};
