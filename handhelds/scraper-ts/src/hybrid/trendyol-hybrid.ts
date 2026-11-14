// Trendyol hybrid scraper: ProductScrape trendyol.py + OmBayus/Trendyol-Web-Scraping, in TypeScript.
//
// From ProductScrape trendyol.py (muratcansarkalkan/ProductScrape):
//   query -> listing pages (24 cards/page, max 208 pages, remainder slice for odd limits),
//   card title (span.prdct-desc-cntnr-name[title]) + price (div.prc-box-dscntd, else
//   "Not available, please view the product page"), product page opened for the id
//   (last word of h1.pr-new-br), ProductCard {id, title, price, link}, worker threads,
//   1 s sleep per product, results written to resultstrendyol.json as each product finishes.
// From OmBayus/Trendyol-Web-Scraping (MIT, (c) 2021 Omer Bayramcavus):
//   headless browser (Puppeteer there, Playwright here), page.reload() before each check,
//   click ".omc-mr-btn" (other sellers), listed price ".prc-dsc", other sellers
//   ".mrc-new-prc span", cheapest-vs-yours check, scheduled tracking with run-on-init,
//   notification when a cheaper seller exists. Email (nodemailer/.env) is dropped: alerts go
//   to the console, an `alerts` SQLite table and alerts.jsonl.
// Changes: /sr search is replaced by robots-allowed category/brand pages; JSON-LD and
// embedded-state fallbacks when the 2021 selectors no longer match; optional UA rotation (UA_ROTATE=1).
import * as cheerio from "cheerio";
import { createRequire } from "node:module";
import { writeFileSync } from "node:fs";
import { BlockedError, RobotsDisallowedError, fetchText, pool, pickUserAgent, politeWait, robotsAllows } from "../http.js";
import { productsFromJsonLd } from "../jsonld.js";
import { parsePrice } from "../types.js";
import { parseTrendyolSearch } from "../adapters/trendyol.js";
import { TRENDYOL_LISTINGS } from "../adapters/trendyol-browse.js";

export const BASE = "https://www.trendyol.com";
export const PER_PAGE = 24;        // trendyol.py: "Results per page was 24"
export const MAX_PAGES = 208;      // ProductScrape README: "Trendyol can load up to 208 pages"
export const MAX_FILTERED_PAGES = 15;   // a filtered query walks at most this many pages per listing
export const MAX_EMPTY_PAGES = 5;       // ...and gives up after this many pages in a row with no match
export const NOT_AVAILABLE = "Not available, please view the product page";

export const SEL = {
  // ProductScrape trendyol.py
  card: "div.p-card-wrppr",
  cardTitle: "span[class*='prdct-desc-cntnr-name']",
  cardPrice: "div.prc-box-dscntd",
  productIdH1: "h1.pr-new-br",
  // OmBayus app.js
  yourPrice: ".prc-dsc",
  otherSellersButton: ".omc-mr-btn",
  sellerPrices: ".mrc-new-prc span",
};

/** ProductScrape ProductCard */
export interface Card { id: string; title: string; price: string; link: string }

export interface PriceCheck {
  url: string;
  title: string | null;
  yourPrice: number | null;
  sellerPrices: number[];
  cheapest: number | null;
  cheaperSellerExists: boolean;
  checkedAt: string;
}

// ---------- fetchers: HTTP (trendyol.py style) or headless browser (OmBayus style) ----------

export interface Fetcher {
  /** Returns page HTML. `expandSellers` clicks the other-sellers button first (browser only). */
  /** keepTab: leave the tab open for later reloads (tracking). Search closes tabs after reading. */
  get(url: string, opts?: { expandSellers?: boolean; reload?: boolean; keepTab?: boolean }): Promise<string>;
  close(): Promise<void>;
}

export const httpFetcher: Fetcher = {
  get: (url) => fetchText(url, { delayMs: 2500 }),
  close: async () => {},
};

const BLOCK_TITLE = /Just a moment|Attention Required|Access denied|Güvenlik/i;

export async function browserFetcher(): Promise<Fetcher> {
  // Playwright from the project, or a global install (createRequire handles both).
  const req = createRequire(import.meta.url);
  let pw: any;
  const mod = "playwright";               // optional dependency: `npm i playwright` at home
  try { pw = await import(mod); } catch { pw = req(mod); }
  const browser = await pw.chromium.launch();
  const context = await browser.newContext({ userAgent: pickUserAgent(), locale: "tr-TR" });
  const pages = new Map<string, any>();   // tracked URLs keep their tab, like OmBayus' configureBrowser()
  return {
    async get(url, opts = {}) {
      if (!(await robotsAllows(url))) throw new RobotsDisallowedError(url);
      await politeWait(url, 2500);          // same per-host slots as plain HTTP requests
      let page = pages.get(url);
      let res;
      if (!page) {
        page = await context.newPage();
        if (opts.keepTab) pages.set(url, page);
        res = await page.goto(url, { waitUntil: "domcontentloaded", timeout: 45000 });
      } else if (opts.reload !== false) {
        res = await page.reload({ waitUntil: "domcontentloaded", timeout: 45000 });   // OmBayus: page.reload()
      }
      try {
        await page.waitForTimeout(2000);
        const status = res?.status() ?? 200;
        const title = await page.title();
        if (status === 403 || status === 429 || BLOCK_TITLE.test(title)) {
          throw new BlockedError(url, status, `browser: ${title || "HTTP " + status}`);
        }
        if (opts.expandSellers) {
          // OmBayus: document.querySelector('.omc-mr-btn').click()
          await page.evaluate((sel: string) => (document.querySelector(sel) as HTMLElement | null)?.click(),
                              SEL.otherSellersButton);
          await page.waitForTimeout(1500);
        }
        return await page.evaluate(() => document.documentElement.outerHTML);
      } finally {
        if (!opts.keepTab) await page.close();   // search mode: don't leave hundreds of tabs open
      }
    },
    async close() { await browser.close(); },
  };
}

// ---------- parsing ----------

/** OmBayus: Number(text.replace(" TL","").replace(".","").replace(",",".")), made robust. */
export function tlNumber(text: string): number | null {
  return parsePrice(text);
}

/** Listing page -> cards. ProductScrape selectors first, then trendyol-ts fallbacks. */
export function parseListing(html: string): (Card & { priceTry: number | null })[] {
  const $ = cheerio.load(html);
  const cards: (Card & { priceTry: number | null })[] = [];
  $(SEL.card).each((_, el) => {
    const title = $(el).find(SEL.cardTitle).attr("title") ?? $(el).find(SEL.cardTitle).text().trim();
    const href = $(el).find("a").first().attr("href");
    if (!title || !href) return;
    const priceText = $(el).find(SEL.cardPrice).text().trim();
    const price = priceText.includes("TL") ? priceText : NOT_AVAILABLE;   // trendyol.py rule
    cards.push({ id: href.match(/-p-(\d+)/)?.[1] ?? "", title, price,
                 link: BASE + href.replace(/^https?:\/\/[^/]+/, ""), priceTry: tlNumber(priceText) });
  });
  if (cards.length) return cards;
  return parseTrendyolSearch(html).map((r) => ({
    id: r.id, title: r.title, link: r.url, priceTry: r.priceTry,
    price: r.priceTry != null ? `${r.priceTry.toLocaleString("tr-TR")} TL` : NOT_AVAILABLE,
  }));
}

/** Product page -> id (trendyol.py) + your price / other sellers / cheapest (OmBayus). */
export function parseProductPage(html: string, url: string): Omit<PriceCheck, "checkedAt"> & { id: string } {
  const $ = cheerio.load(html);
  const h1 = $(SEL.productIdH1).text().trim();
  // trendyol.py: id = h1.pr-new-br text, last word. Fall back to the -p-<id> in the URL.
  const id = (h1 ? h1.split(/\s+/).pop() : null) || url.match(/-p-(\d+)/)?.[1] || url;
  const ld = productsFromJsonLd(html)[0];
  const yourPrice = tlNumber($(SEL.yourPrice).first().text()) ?? ld?.price ?? null;
  const sellerPrices: number[] = [];
  $(SEL.sellerPrices).each((_, el) => { const p = tlNumber($(el).text()); if (p) sellerPrices.push(p); });
  for (const o of ld?.offers ?? []) if (o.price && !sellerPrices.includes(o.price)) sellerPrices.push(o.price);
  // OmBayus: cheapest starts at your price, lowered by any cheaper seller
  let cheapest = yourPrice;
  for (const p of sellerPrices) if (cheapest == null || p < cheapest) cheapest = p;
  return {
    id, url, title: h1 || ld?.name || null, yourPrice, sellerPrices, cheapest,
    cheaperSellerExists: yourPrice != null && cheapest != null && cheapest < yourPrice,
  };
}

// ---------- search (ProductScrape flow) ----------

/** trendyol.py page plan: pages = limit // 24, then one more page sliced to limit % 24. */
export function pagePlan(limit: number): { page: number; take: number | null }[] {
  const pages = Math.floor(limit / PER_PAGE);
  const plan: { page: number; take: number | null }[] = [];
  for (let i = 1; i <= Math.min(pages + 1, MAX_PAGES); i++) {
    plan.push({ page: i, take: i === pages + 1 ? limit % PER_PAGE : null });
  }
  return plan;
}

export function listingPageUrl(path: string, page: number): string {
  return `${BASE}${path}${page > 1 ? `?pi=${page}` : ""}`;
}

export interface SearchOptions {
  fetcher?: Fetcher;
  threads?: number;                 // trendyol.py started one thread per card; bounded here
  listings?: string[];
  resultsFile?: string | null;      // trendyol.py: resultstrendyol.json
  details?: boolean;                // open each product page (trendyol.py did)
  onLog?: (msg: string) => void;
}

export async function searchHybrid(query: string, limit: number, opts: SearchOptions = {}):
    Promise<(Card & { priceTry: number | null; sellerPrices?: number[]; cheapest?: number | null })[]> {
  const { fetcher = httpFetcher, threads = 4, listings = TRENDYOL_LISTINGS,
          resultsFile = "resultstrendyol.json", details = true, onLog = console.log } = opts;
  const words = query.toLowerCase().split(/\s+/).filter((w) => w && w !== "*");
  const cards: (Card & { priceTry: number | null })[] = [];
  for (const path of listings) {
    // Walk pages until enough matching cards; for an unfiltered query, use trendyol.py's exact plan.
    // (was up to 208 pages per listing, ~1,250 requests for a rare model; now capped)
    const plan = words.length ? Array.from({ length: MAX_FILTERED_PAGES }, (_, i) => ({ page: i + 1, take: null }))
                              : pagePlan(limit - cards.length);
    let emptyStreak = 0;
    for (const { page, take } of plan) {
      if (cards.length >= limit || emptyStreak >= MAX_EMPTY_PAGES) break;
      let got = parseListing(await fetcher.get(listingPageUrl(path, page)));
      if (!got.length) break;
      if (take != null) got = got.slice(0, take);
      const before = cards.length;
      for (const c of got) {
        if (cards.some((x) => x.link === c.link)) continue;
        if (words.length && !words.every((w) => c.title.toLowerCase().includes(w))) continue;
        cards.push(c);
      }
      emptyStreak = cards.length === before ? emptyStreak + 1 : 0;
    }
    if (cards.length >= limit) break;
  }
  const picked = cards.slice(0, limit);
  const done: (Card & { priceTry: number | null; sellerPrices?: number[]; cheapest?: number | null })[] = [];
  const flush = () => resultsFile && writeFileSync(resultsFile, JSON.stringify(done, null, 1));
  await pool(picked, details ? threads : picked.length, async (c, i) => {
    onLog(`Trying to parse ${i + 1} of ${limit} results from Trendyol...`);
    if (!details) { done.push(c); flush(); return; }
    try {
      const p = parseProductPage(await fetcher.get(c.link, { expandSellers: fetcher !== httpFetcher }), c.link);
      done.push({ ...c, id: p.id, sellerPrices: p.sellerPrices, cheapest: p.cheapest,
                  priceTry: c.priceTry ?? p.yourPrice });
      onLog(`Parsing successful for product ${i + 1}.`);
    } catch (e) {
      if (e instanceof BlockedError) throw e;
      onLog("Sorry, product could not be parsed");
      done.push(c);
    }
    flush();
    await new Promise((r) => setTimeout(r, 1000));   // trendyol.py: time.sleep(1)
  });
  return done;
}

// ---------- tracking (OmBayus flow) ----------

export interface TrackOptions {
  fetcher: Fetcher;
  cronTime?: string;                 // OmBayus meant every 30 min; '0 */30 * * * *' actually is
  once?: boolean;
  onCheck?: (c: PriceCheck) => void;
  onAlert?: (c: PriceCheck) => void; // replaces sendNotification() email
}

export async function checkPrice(fetcher: Fetcher, url: string): Promise<PriceCheck> {
  const html = await fetcher.get(url, { expandSellers: true, reload: true, keepTab: true });
  const p = parseProductPage(html, url);
  return { url, title: p.title, yourPrice: p.yourPrice, sellerPrices: p.sellerPrices, cheapest: p.cheapest,
           cheaperSellerExists: p.cheaperSellerExists, checkedAt: new Date().toISOString() };
}

export async function startTracking(urls: string[], opts: TrackOptions): Promise<void> {
  const { fetcher, cronTime = "0 */30 * * * *", once = false,
          onCheck = () => {}, onAlert = () => {} } = opts;
  const runAll = async () => {
    for (const url of urls) {
      try {
        const c = await checkPrice(fetcher, url);
        onCheck(c);
        if (c.cheaperSellerExists) onAlert(c);       // OmBayus: if (cheapest < yourPrice) sendNotification()
      } catch (e) {
        console.log(`✗ ${url}: ${(e as Error).message}`);
      }
    }
  };
  if (once) { await runAll(); return; }
  const { CronJob } = await import("cron");
  // runOnInit = true, as in OmBayus' CronJob(..., true) call
  const job = new CronJob(cronTime, () => { void runAll(); }, null, true, undefined, undefined, true);
  job.start();
}
