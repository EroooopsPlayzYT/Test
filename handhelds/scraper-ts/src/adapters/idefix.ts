// idefix: the one big store that answers plain requests. Its search is rendered by
// JavaScript and its category pages are disallowed in robots.txt, so we read brand
// pages (server-rendered, ~24 products each) and the allowed product pages.
import { fetchText, pool } from "../http.js";
import { productsFromJsonLd } from "../jsonld.js";
import { Adapter, Product } from "../types.js";

const BASE = "https://www.idefix.com";
export const IDEFIX_BRANDS: Record<string, string> = {
  valve: "valve-20047255", steam: "steam-20038434", nintendo: "nintendo-1001386",
  anbernic: "anbernic-20081785", ayaneo: "ayaneo-20083672", ayn: "ayn-20004603",
  playstation: "playstation-20122520", sony: "sony-1001804", gpd: "gpd-20018863",
  "data-frog": "data-frog-20070228", asus: "asus-1000150", lenovo: "lenovo-1001108", msi: "msi-1001318",
};
// Brand pages for big brands are mostly laptops/TVs: keep only links that look like handhelds.
const HANDHELD_SLUG = /deck|ally|legion-go|claw|ayaneo|odin|thor|retroid|anbernic|rg-?\d|miyoo|powkiddy|switch|portal|psp|vita|gpd|win-?\d|el-konsol|oyun-konsol|retro/;

export const idefix: Adapter = {
  name: "idefix",
  async search(query, limit) {
    const words = query.toLowerCase().split(/\s+/).filter((w) => w && w !== "*");
    const brands = Object.entries(IDEFIX_BRANDS)
      .filter(([k]) => words.includes(k) || words.some((w) => k.startsWith(w)));
    const slugs = brands.length ? brands.map(([, v]) => v) : Object.values(IDEFIX_BRANDS);
    const links = new Set<string>();
    for (const slug of slugs) {
      const html = await fetchText(`${BASE}/marka/${slug}`);
      for (const m of html.matchAll(/href="(\/[^"#?]*-p-\d+)/g)) {
        const text = m[1].replace(/-/g, " ").toLowerCase();
        if (!HANDHELD_SLUG.test(m[1].toLowerCase())) continue;
        if (words.every((w) => text.includes(w)) || brands.length) links.add(BASE + m[1]);
      }
    }
    const now = new Date().toISOString();
    const rows = await pool([...links].slice(0, limit), 1, async (url): Promise<Product | null> => {
      try {
        const p = productsFromJsonLd(await fetchText(url))[0];
        if (!p) return null;
        return { source: "idefix", id: url.match(/-p-(\d+)/)![1], title: p.name, priceTry: p.price,
                 inStock: p.inStock, url, seller: p.offers[0]?.seller ?? null, condition: "new", scrapedAt: now };
      } catch (e) {
        console.warn(`  idefix ${url}: ${(e as Error).message}`);
        return null;
      }
    });
    return rows.filter((r): r is Product => r !== null);
  },
};
