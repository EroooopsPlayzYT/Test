// Small shops on common platforms, which expose public catalogue JSON.
import { fetchText } from "../http.js";
import { Adapter, Product, parsePrice } from "../types.js";

/** WooCommerce Store API: /wp-json/wc/store/v1/products?search= */
export function woocommerce(domain: string): Adapter {
  return {
    name: `woocommerce:${domain}`,
    async search(query, limit) {
      const url = `https://${domain}/wp-json/wc/store/v1/products?per_page=${Math.min(limit, 100)}` +
        `&search=${encodeURIComponent(query)}`;
      const items = JSON.parse(await fetchText(url)) as any[];
      const now = new Date().toISOString();
      return items.slice(0, limit).map((p): Product => {
        const minor = Number(p.prices?.currency_minor_unit ?? 2);
        const raw = p.prices?.price ? Number(p.prices.price) / 10 ** minor : null;
        return { source: `woocommerce:${domain}`, id: String(p.id), title: p.name, priceTry: raw || null,
                 inStock: p.is_in_stock ?? null, url: p.permalink, seller: domain, condition: "new", scrapedAt: now };
      });
    },
  };
}

/** Shopify: /products.json is public on most stores; filter by query locally. */
export function shopify(domain: string): Adapter {
  return {
    name: `shopify:${domain}`,
    async search(query, limit) {
      const words = query.toLowerCase().split(/\s+/);
      const out: Product[] = [];
      const now = new Date().toISOString();
      for (let page = 1; page <= 20 && out.length < limit; page++) {
        const data = JSON.parse(await fetchText(`https://${domain}/products.json?limit=250&page=${page}`));
        if (!data.products?.length) break;
        for (const p of data.products) {
          if (!words.every((w) => p.title.toLowerCase().includes(w))) continue;
          const v = p.variants?.[0] ?? {};
          out.push({ source: `shopify:${domain}`, id: String(p.id), title: p.title, priceTry: parsePrice(v.price),
                     inStock: p.variants?.some((x: any) => x.available) ?? null,
                     url: `https://${domain}/products/${p.handle}`, seller: domain, condition: "new", scrapedAt: now });
        }
      }
      return out.slice(0, limit);
    },
  };
}
