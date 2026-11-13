// Akakce: search results -> product pages -> one Product per seller offer (all shops, big and small).
import * as cheerio from "cheerio";
import { fetchText, pool } from "../http.js";
import { productsFromJsonLd } from "../jsonld.js";
import { Adapter, Product, parsePrice } from "../types.js";

const BASE = "https://www.akakce.com";

export const akakce: Adapter = {
  name: "akakce",
  async search(query, limit) {
    const cards: { id: string; title: string; url: string; price: number | null }[] = [];
    for (let page = 1; cards.length < limit && page <= 20; page++) {
      const url = `${BASE}/arama/?q=${encodeURIComponent(query)}${page > 1 ? `&p=${page}` : ""}`;
      const $ = cheerio.load(await fetchText(url));
      const before = cards.length;
      $("li[data-pr]").each((_, li) => {
        const a = $(li).find("a[href]").first();
        const href = a.attr("href");
        if (!href || cards.some((c) => c.id === $(li).attr("data-pr"))) return;
        cards.push({
          id: $(li).attr("data-pr")!,
          title: ($(li).find("h3").text() || a.attr("title") || "").trim(),
          url: new URL(href, BASE).href,
          price: parsePrice($(li).find(".pt_v9").first().text()),
        });
      });
      if (cards.length === before) break; // no new results: last page
    }
    const now = new Date().toISOString();
    const perProduct = await pool(cards.slice(0, limit), 1, async (c) => {
      try {
        const ld = productsFromJsonLd(await fetchText(c.url))[0];
        if (ld?.offers.length) {
          return ld.offers.map((o): Product => ({
            source: "akakce", id: c.id, title: c.title, priceTry: o.price, inStock: true,
            url: o.url ?? c.url, seller: o.seller, condition: "new", scrapedAt: now,
          }));
        }
      } catch (e) {
        console.warn(`  akakce ${c.id}: ${(e as Error).message}`);
      }
      return [{ source: "akakce", id: c.id, title: c.title, priceTry: c.price, inStock: null,
                url: c.url, seller: null, condition: "new", scrapedAt: now } as Product];
    });
    return perProduct.flat();
  },
};
