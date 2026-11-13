// ProductScrape's ProductCard (id, title, price, link), extended for price history / ML.
export interface Product {
  source: string;           // adapter name: akakce, idefix, trendyol, amazontr, woocommerce:<domain>, ...
  id: string;               // site product id (ASIN, Trendyol content id, Akakce id, ...)
  title: string;
  priceTry: number | null;  // numeric TRY, null if not shown (ProductScrape kept a string)
  inStock: boolean | null;
  url: string;
  seller?: string | null;   // shop / merchant name for marketplaces; never a private person
  condition?: "new" | "used" | null;
  scrapedAt: string;        // ISO time, so repeated runs build a price history
}

export interface Adapter {
  name: string;
  /** Search the site and return up to `limit` products. */
  search(query: string, limit: number): Promise<Product[]>;
}

const ACCESSORY = /kılıf|kilif|çanta|canta|koruyucu|temperli|\bcam\b|stand|şarj|sarj|kablo|adaptör|adaptor|joy-?con|kontrolc|oyun kolu|\bgrip\b|sticker|kaplama|\bdock\b|amiibo|hediye kart|\bcase\b|\bskin\b|silikon|thumb ?stick|analog (koruyucu|başlık)/i;

/** True for consoles, false for cases, films, chargers, grips and other accessories. */
export function isDevice(title: string): boolean {
  return !ACCESSORY.test(title);
}

/** "51.457,00 TL" | "51457.00" | 51457 -> 51457 */
export function parsePrice(v: unknown): number | null {
  if (v == null) return null;
  if (typeof v === "number") return Number.isFinite(v) && v > 0 ? v : null;
  let s = String(v).replace(/[^\d.,]/g, "");
  if (!s) return null;
  if (s.includes(",")) s = s.replace(/\./g, "").replace(",", ".");          // Turkish format
  else if ((s.match(/\./g) ?? []).length > 1 || /\.\d{3}$/.test(s)) s = s.replace(/\./g, ""); // 51.457
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : null;
}
