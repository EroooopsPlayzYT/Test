// Read schema.org Product data. More stable than ProductScrape's CSS class names,
// which change whenever a site redesigns.
import * as cheerio from "cheerio";
import { parsePrice } from "./types.js";

export interface LdProduct {
  name: string;
  sku?: string;
  brand?: string;
  price: number | null;
  inStock: boolean | null;
  offers: { seller: string | null; price: number | null; url: string | null }[];
  ratingValue?: number | null;
  ratingCount?: number | null;
}

function* walk(node: unknown): Generator<Record<string, unknown>> {
  if (Array.isArray(node)) for (const n of node) yield* walk(n);
  else if (node && typeof node === "object") {
    const o = node as Record<string, unknown>;
    yield o;
    for (const v of Object.values(o)) if (v && typeof v === "object") yield* walk(v);
  }
}

export function productsFromJsonLd(html: string): LdProduct[] {
  const $ = cheerio.load(html);
  const out: LdProduct[] = [];
  $('script[type="application/ld+json"]').each((_, el) => {
    let data: unknown;
    try { data = JSON.parse($(el).text()); } catch { return; }
    for (const o of walk(data)) {
      const t = o["@type"];
      if (t !== "Product" && !(Array.isArray(t) && t.includes("Product"))) continue;
      const offersRaw = o.offers as Record<string, unknown> | Record<string, unknown>[] | undefined;
      const top = Array.isArray(offersRaw) ? offersRaw[0] : offersRaw;
      const inner = (top?.offers as Record<string, unknown>[] | undefined) ?? (Array.isArray(offersRaw) ? offersRaw : []);
      const brand = o.brand as { name?: string } | string | undefined;
      const rating = o.aggregateRating as Record<string, unknown> | undefined;
      out.push({
        name: String(o.name ?? "").trim(),
        sku: o.sku ? String(o.sku) : undefined,
        brand: typeof brand === "string" ? brand : brand?.name,
        price: parsePrice(top?.price ?? top?.lowPrice),
        inStock: top?.availability ? /InStock/i.test(String(top.availability)) : null,
        offers: inner.map((x) => ({
          seller: (x.seller as { name?: string } | undefined)?.name ?? null,
          price: parsePrice(x.price),
          url: x.url ? String(x.url) : null,
        })),
        ratingValue: rating ? Number(rating.ratingValue) : null,
        ratingCount: rating ? Number(rating.ratingCount) : null,
      });
    }
  });
  return out;
}
