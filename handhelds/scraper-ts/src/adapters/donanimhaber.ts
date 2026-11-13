// DonanımHaber forum, "İkinci El Konsollar ve Aksesuarları" (f275): public, robots.txt
// allows section + thread pages (not /search.asp). Each sale thread carries schema.org
// Product JSON-LD (title, TRY price, used/new). We store only that: no usernames, avatars,
// phone numbers or post bodies.
import { fetchText, pool } from "../http.js";
import { productsFromJsonLd } from "../jsonld.js";
import { Adapter, Product } from "../types.js";

const BASE = "https://forum.donanimhaber.com";
const SECTION = "/ikinci-el-konsollar-ve-aksesuarlari--f275";
const HANDHELD = /deck|ally|legion-go|claw|ayaneo|ayn|odin|retroid|anbernic|miyoo|powkiddy|r36s|switch|portal|psp|vita|3ds|2ds|game-?boy|el-konsol/i;

export function threadLinks(html: string): string[] {
  const out = new Set<string>();
  for (const m of html.matchAll(/href="(\/[a-z0-9-]+--\d{6,})"/g)) out.add(m[1]);
  return [...out];
}

export function donanimhaber(maxPages = 10): Adapter {
  return {
    name: "donanimhaber",
    async search(query, limit) {
      const words = query.toLowerCase().split(/\s+/).filter((w) => w && w !== "*").map((w) => w.replace(/\s/g, "-"));
      const links: string[] = [];
      for (let page = 1; page <= maxPages && links.length < limit; page++) {
        const html = await fetchText(`${BASE}${SECTION}${page > 1 ? `?sayfa=${page}` : ""}`, { delayMs: 2500 });
        for (const l of threadLinks(html)) {
          if (links.includes(l) || !HANDHELD.test(l)) continue;
          if (words.length && !words.every((w) => l.includes(w))) continue;
          links.push(l);
        }
      }
      const now = new Date().toISOString();
      const rows = await pool(links.slice(0, limit), 1, async (path): Promise<Product | null> => {
        try {
          const html = await fetchText(BASE + path, { delayMs: 2500 });
          const p = productsFromJsonLd(html)[0];
          if (!p) return null;
          const sold = /\bSATILDI\b/i.test(p.name);
          const used = /UsedCondition/.test(html) || /ikinci el/i.test(p.name);
          return {
            source: "donanimhaber", id: path.match(/--(\d+)$/)![1],
            title: p.name.replace(/^\s*\[?(SATILIK|SATILDI)\]?\s*/i, "").trim(),
            priceTry: p.price, inStock: sold ? false : p.inStock, url: BASE + path,
            seller: null, condition: used ? "used" : "new", scrapedAt: now,
          };
        } catch (e) {
          console.warn(`  donanimhaber ${path}: ${(e as Error).message}`);
          return null;
        }
      });
      return rows.filter((r): r is Product => r !== null);
    },
  };
}
