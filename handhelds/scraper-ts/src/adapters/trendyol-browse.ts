// Trendyol without /sr (search is disallowed in robots.txt). Walks the category, brand and
// landing pages that robots.txt allows (-x-c<id>, -x-b<id>, -y-s<id>, ?pi= paging) and keeps
// products whose title matches the query. Page format is the same as search, so the
// trendyol-ts parser is reused. IDs found via web search on 2026-11-13.
import { fetchText } from "../http.js";
import { Adapter, Product } from "../types.js";
import { parseTrendyolSearch } from "./trendyol.js";

const BASE = "https://www.trendyol.com";
export const TRENDYOL_LISTINGS = [
  "/oyun-konsollari-x-c108889",                        // Oyun Konsolları (handhelds, retro)
  "/tasinabilir-oyun-konsolu-y-s56517",                // Taşınabilir Oyun Konsolu landing
  "/steam-oyun-konsollari-x-b109778-c108889",          // Steam (Valve) consoles
  "/steam-deck-y-s42117",                              // Steam Deck landing
  "/anbernic-oyun-ve-oyun-konsollari-x-b157692-c104044", // Anbernic
  "/oyun-ve-oyun-konsollari-x-c104044",                // Oyun & Konsollar (Switch, Portal, ROG Ally…)
];

export function listingUrl(path: string, page: number): string {
  return `${BASE}${path}${page > 1 ? `?pi=${page}` : ""}`;
}

export function trendyolBrowse(maxPagesPerListing = 10): Adapter {
  return {
    name: "trendyol-browse",
    async search(query, limit) {
      const words = query.toLowerCase().split(/\s+/).filter((w) => w && w !== "*");
      const out: Product[] = [];
      for (const path of TRENDYOL_LISTINGS) {
        for (let page = 1; page <= maxPagesPerListing && out.length < limit; page++) {
          const rows = parseTrendyolSearch(await fetchText(listingUrl(path, page), { delayMs: 2500 }));
          if (!rows.length) break;
          const now = new Date().toISOString();
          for (const r of rows) {
            if (out.some((o) => o.id === r.id)) continue;
            if (words.length && !words.every((w) => r.title.toLowerCase().includes(w))) continue;
            out.push({ ...r, source: "trendyol-browse", scrapedAt: now });
          }
        }
      }
      return out.slice(0, limit);
    },
  };
}
