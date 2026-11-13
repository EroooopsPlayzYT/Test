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

const HANDHELD_CONTEXT = /konsol|console|handheld|oyun|game|retro|emülat|emulat|steam\s*deck|\bally\b|legion\s*go|\bclaw\b|ayaneo|\bodin\b|\bthor\b|\bayn\b.*(odin|thor|loki)|retroid|anbernic|\brg\s*-?\d|miyoo|powkiddy|trim\s*ui|r3[56]s|switch|portal|\bpsp\b|\bvita\b|\b[23]ds\b/i;
const NOT_HANDHELD = /\bkitap|\bbook\b|novel|roman\b|rand\b|lavabo|banyo|musluk|batarya|bardak|mont\b|gimbal|\brtx\s*\d/i;

/** True for consoles; false for accessories and for things that only share a word (books, faucets…). */
export function isDevice(title: string): boolean {
  return !ACCESSORY.test(title) && HANDHELD_CONTEXT.test(title) && !NOT_HANDHELD.test(title);
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
