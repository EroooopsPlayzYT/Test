// Trendyol hybrid CLI.
//   search (ProductScrape flow):  npm run trendyol -- search -q "steam deck" -n 30 [--browser] [--threads 4]
//   track  (OmBayus flow):        npm run trendyol -- track "<trendyol product url>" [...more urls]
//                                    [--cron "0 */30 * * * *"] [--http] [--once]
// Both write to SQLite (observations / alerts) and JSON files under out/.
import { appendFileSync, mkdirSync } from "node:fs";
import { parseArgs } from "node:util";
import { openDb, saveAlert, saveProducts, logRun } from "../db.js";
import { BlockedError, RobotsDisallowedError } from "../http.js";
import { browserFetcher, httpFetcher, searchHybrid, startTracking, type Fetcher } from "./trendyol-hybrid.js";

const [cmd, ...rest] = process.argv.slice(2);
const { values, positionals } = parseArgs({
  args: rest, allowPositionals: true,
  options: {
    query: { type: "string", short: "q" },
    limit: { type: "string", short: "n" },
    threads: { type: "string", default: "4" },
    browser: { type: "boolean", default: false },
    http: { type: "boolean", default: false },
    cron: { type: "string", default: "0 */30 * * * *" },
    once: { type: "boolean", default: false },
    "no-details": { type: "boolean", default: false },
    "ua-rotate": { type: "boolean", default: false },
    db: { type: "string", default: "out/prices.db" },
    out: { type: "string", default: "out" },
  },
});
mkdirSync(values.out!, { recursive: true });
if (values["ua-rotate"]) process.env.UA_ROTATE = "1";
const db = openDb(values.db!);

if (cmd === "search") {
  const limit = Number(values.limit);
  if (!values.query || !Number.isInteger(limit) || limit < 1) {
    console.log("No, please give a number!");     // ProductScrape main.py wording
    console.log('Usage: search -q "steam deck" -n 30');
    process.exit(1);
  }
  const fetcher: Fetcher = values.browser ? await browserFetcher() : httpFetcher;
  const started = new Date().toISOString();
  console.log("Looking up...");
  try {
    const rows = await searchHybrid(values.query, limit, {
      fetcher, threads: Number(values.threads), details: !values["no-details"],
      resultsFile: `${values.out}/resultstrendyol.json`,
    });
    const now = new Date().toISOString();
    saveProducts(db, rows.map((r) => ({ source: "trendyol-hybrid", id: r.id, title: r.title, priceTry: r.priceTry,
      inStock: null, url: r.link, seller: null, condition: "new" as const, scrapedAt: now })), values.query);
    logRun(db, "trendyol-hybrid", values.query, "ok", null, rows.length, started);
    console.log(`✓ ${rows.length} products -> ${values.out}/resultstrendyol.json`);
  } catch (e) {
    const status = e instanceof BlockedError ? "blocked" : e instanceof RobotsDisallowedError ? "robots" : "error";
    logRun(db, "trendyol-hybrid", values.query, status, (e as Error).message, 0, started);
    console.log("Sorry, we could not find any products regarding your search from Trendyol.", (e as Error).message);
  } finally {
    await fetcher.close();
  }
} else if (cmd === "track") {
  if (!positionals.length) {
    console.log('Please provide the link as an argument: track "trendyol-urun-link"');   // OmBayus wording
    process.exit(1);
  }
  // OmBayus used a browser; --http uses plain requests instead (no seller-button click).
  const fetcher: Fetcher = values.http ? httpFetcher : await browserFetcher();
  await startTracking(positionals, {
    fetcher, cronTime: values.cron, once: values.once,
    onCheck: (c) => {
      appendFileSync(`${values.out}/checks.jsonl`, JSON.stringify(c) + "\n");
      saveProducts(db, [{ source: "trendyol-track", id: c.url.match(/-p-(\d+)/)?.[1] ?? c.url, title: c.title ?? c.url,
        priceTry: c.yourPrice, inStock: null, url: c.url, seller: null, condition: "new", scrapedAt: c.checkedAt }], "track");
      console.log(`• ${c.checkedAt} ${c.title ?? c.url}: yours ${c.yourPrice} TL, cheapest ${c.cheapest} TL (${c.sellerPrices.length} sellers)`);
    },
    onAlert: (c) => {                                    // was sendNotification() email
      saveAlert(db, c);
      appendFileSync(`${values.out}/alerts.jsonl`, JSON.stringify(c) + "\n");
      console.log(`🔔 Price dropped to ${c.cheapest} TL (listed ${c.yourPrice} TL): ${c.url}`);
    },
  });
  if (values.once) { await fetcher.close(); db.close(); }
} else {
  console.log("Commands: search | track   (see the top of src/hybrid/cli.ts)");
  process.exit(1);
}
