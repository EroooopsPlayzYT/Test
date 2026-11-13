// Usage:
//   npm run scrape -- --query "steam deck" --limit 30
//   npm run scrape -- --query "retroid pocket" --sources akakce,idefix --db out/prices.db
//   npm run scrape -- --queries queries.txt --sources akakce,trendyol,amazontr
//   npm run scrape            (no flags: asks "Search by product..." like ProductScrape main.py)
// Flags let it run on a schedule. Writes SQLite, JSON Lines and ProductScrape-style
// results<site>.json / results.json, and logs blocked or disallowed sources.
import { mkdirSync, appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { parseArgs } from "node:util";
import { createInterface } from "node:readline";
import { BlockedError, RobotsDisallowedError } from "./http.js";
import { openDb, saveProducts, logRun } from "./db.js";
import type { Adapter } from "./types.js";
import { akakce } from "./adapters/akakce.js";
import { idefix } from "./adapters/idefix.js";
import { trendyol } from "./adapters/trendyol.js";
import { amazontr } from "./adapters/amazontr.js";
import { woocommerce, shopify } from "./adapters/shops.js";
import { donanimhaber } from "./adapters/donanimhaber.js";
import { trendyolPy } from "./adapters/trendyol-py.js";
import { trendyolBrowse } from "./adapters/trendyol-browse.js";
import { searchHybrid } from "./hybrid/trendyol-hybrid.js";
import { isDevice } from "./types.js";

const ADAPTERS: Record<string, Adapter> = {
  akakce, idefix, amazontr,
  trendyol: trendyolPy,          // ProductScrape trendyol.py, unmodified
  "trendyol-ts": trendyol,       // TypeScript re-implementation (search; stops on robots /sr)
  "trendyol-browse": trendyolBrowse(), // category/brand pages, no /sr
  "trendyol-hybrid": {                 // ProductScrape + OmBayus hybrid (HTTP mode)
    name: "trendyol-hybrid",
    async search(query, limit) {
      const now = new Date().toISOString();
      const rows = await searchHybrid(query, limit, { resultsFile: null, onLog: () => {} });
      return rows.map((r) => ({ source: "trendyol-hybrid", id: r.id, title: r.title, priceTry: r.priceTry,
        inStock: null, url: r.link, seller: null, condition: "new" as const, scrapedAt: now }));
    },
  },
  "gaming.gen.tr": woocommerce("www.gaming.gen.tr"),
  donanimhaber: donanimhaber(Number(process.env.DH_PAGES ?? 10)),
};

function resolve(name: string): Adapter {
  if (ADAPTERS[name]) return ADAPTERS[name];
  const [kind, domain] = name.split(":");
  if (kind === "woocommerce" && domain) return woocommerce(domain);
  if (kind === "shopify" && domain) return shopify(domain);
  throw new Error(`unknown source "${name}". Known: ${Object.keys(ADAPTERS).join(", ")}, woocommerce:<domain>, shopify:<domain>`);
}

const { values } = parseArgs({
  options: {
    query: { type: "string", short: "q" },
    queries: { type: "string" },
    limit: { type: "string", short: "n", default: "20" },
    sources: { type: "string", short: "s", default: "akakce,idefix,gaming.gen.tr,donanimhaber,amazontr,trendyol" },
    "include-accessories": { type: "boolean", default: false },
    "ua-rotate": { type: "boolean", default: false },
    db: { type: "string", default: "out/prices.db" },
    jsonl: { type: "string", default: "out/results.jsonl" },
  },
});

let queries = values.queries
  ? readFileSync(values.queries, "utf8").split("\n").map((s) => s.trim()).filter(Boolean)
  : values.query ? [values.query] : [];
let limit = Number(values.limit);

// ProductScrape main.py behaviour: with no query flag, ask interactively.
if (!queries.length) {
  const rl = createInterface({ input: process.stdin });
  const lines = rl[Symbol.asyncIterator]();          // works for typed and piped input
  const ask = async (prompt: string) => {
    process.stdout.write(prompt);
    const { value, done } = await lines.next();
    return done ? "" : String(value);
  };
  queries = [await ask("Search by product...")];
  const answer = await ask("How many results do you want?");
  rl.close();
  limit = Number(answer);
  if (!answer.trim() || !Number.isInteger(limit) || limit < 1) {
    console.log("No, please give a number!");
    console.log("As you didn't give a number, I will quit.");
    process.exit(1);
  }
}
if (!Number.isInteger(limit) || limit < 1) {
  console.error("--limit must be a positive whole number.");
  process.exit(1);
}

mkdirSync(dirname(values.db!), { recursive: true });
mkdirSync(dirname(values.jsonl!), { recursive: true });
if (values["ua-rotate"]) process.env.UA_ROTATE = "1";
const db = openDb(values.db!);
const sources = values.sources!.split(",").map((s) => resolve(s.trim()));

// ProductScrape wrote results<site>.json files with ProductCard objects (id, title, price, link).
// Kept, as valid JSON arrays, next to the JSON Lines file.
const cards = new Map<string, { id: string; title: string; price: string; link: string; query: string }[]>();
const cardFile = (src: string) =>
  join(dirname(values.jsonl!), `results${src.replace(/[^a-z0-9.]+/gi, "_")}.json`);

console.log("Looking up...");
for (const query of queries) {
  // Sources run in parallel (different hosts), like main.py's threads; each host is rate-limited in http.ts.
  await Promise.all(sources.map(async (src) => {
    const started = new Date().toISOString();
    try {
      const all = await src.search(query, limit);
      const rows = values["include-accessories"] ? all : all.filter((r) => isDevice(r.title));
      saveProducts(db, rows, query);
      for (const r of rows) appendFileSync(values.jsonl!, JSON.stringify({ ...r, query }) + "\n");
      const list = cards.get(src.name) ?? [];
      list.push(...rows.map((r) => ({
        id: r.id, title: r.title, link: r.url, query,
        price: r.priceTry != null ? `${r.priceTry.toLocaleString("tr-TR")} TL`
                                  : "Not available, please view the product page",
      })));
      cards.set(src.name, list);
      writeFileSync(cardFile(src.name), JSON.stringify(list, null, 1));
      logRun(db, src.name, query, "ok", null, rows.length, started);
      console.log(`✓ ${src.name.padEnd(28)} "${query}": ${rows.length} rows`);
    } catch (e) {
      const status = e instanceof BlockedError ? "blocked" : e instanceof RobotsDisallowedError ? "robots" : "error";
      logRun(db, src.name, query, status, (e as Error).message, 0, started);
      console.log(`✗ ${src.name.padEnd(28)} "${query}": ${status} (${(e as Error).message})`);
      console.log(`  Sorry, we could not find any products regarding your search from ${src.name}.`);
    }
  }));
}
writeFileSync(join(dirname(values.jsonl!), "results.json"),
              JSON.stringify([...cards.values()].flat(), null, 1));
db.close();
