// Usage:
//   npm run scrape -- --query "steam deck" --limit 30
//   npm run scrape -- --query "retroid pocket" --sources akakce,idefix --db out/prices.db
//   npm run scrape -- --queries queries.txt --sources akakce,trendyol,amazontr
// ProductScrape asked for the query and limit with input(); this takes flags so it can run
// on a schedule, writes JSON Lines (valid line by line) and SQLite, and logs blocked sources.
import { mkdirSync, appendFileSync, readFileSync } from "node:fs";
import { dirname } from "node:path";
import { parseArgs } from "node:util";
import { BlockedError, RobotsDisallowedError } from "./http.js";
import { openDb, saveProducts, logRun } from "./db.js";
import type { Adapter } from "./types.js";
import { akakce } from "./adapters/akakce.js";
import { idefix } from "./adapters/idefix.js";
import { trendyol } from "./adapters/trendyol.js";
import { amazontr } from "./adapters/amazontr.js";
import { woocommerce, shopify } from "./adapters/shops.js";
import { donanimhaber } from "./adapters/donanimhaber.js";
import { isDevice } from "./types.js";

const ADAPTERS: Record<string, Adapter> = {
  akakce, idefix, trendyol, amazontr,
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
    db: { type: "string", default: "out/prices.db" },
    jsonl: { type: "string", default: "out/results.jsonl" },
  },
});

const queries = values.queries
  ? readFileSync(values.queries, "utf8").split("\n").map((s) => s.trim()).filter(Boolean)
  : values.query ? [values.query] : [];
const limit = Number(values.limit);
if (!queries.length || !Number.isInteger(limit) || limit < 1) {
  console.error('Give --query "steam deck" (or --queries file.txt) and a numeric --limit.');
  process.exit(1);
}

mkdirSync(dirname(values.db!), { recursive: true });
mkdirSync(dirname(values.jsonl!), { recursive: true });
const db = openDb(values.db!);
const sources = values.sources!.split(",").map((s) => resolve(s.trim()));

for (const query of queries) {
  // Sources run in parallel (different hosts); each host is rate-limited inside http.ts.
  await Promise.all(sources.map(async (src) => {
    const started = new Date().toISOString();
    try {
      const all = await src.search(query, limit);
      const rows = values["include-accessories"] ? all : all.filter((r) => isDevice(r.title));
      saveProducts(db, rows, query);
      for (const r of rows) appendFileSync(values.jsonl!, JSON.stringify({ ...r, query }) + "\n");
      logRun(db, src.name, query, "ok", null, rows.length, started);
      console.log(`✓ ${src.name.padEnd(28)} "${query}": ${rows.length} rows`);
    } catch (e) {
      const status = e instanceof BlockedError ? "blocked" : e instanceof RobotsDisallowedError ? "robots" : "error";
      logRun(db, src.name, query, status, (e as Error).message, 0, started);
      console.log(`✗ ${src.name.padEnd(28)} "${query}": ${status} (${(e as Error).message})`);
    }
  }));
}
db.close();
