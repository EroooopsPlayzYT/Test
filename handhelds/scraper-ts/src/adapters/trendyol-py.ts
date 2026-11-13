// Trendyol via ProductScrape's original trendyol.py (git submodule, unmodified),
// run through py/run_trendyol.py. Needs python3 + beautifulsoup4.
// Trendyol's robots.txt disallows /sr, so instead of Trendyol.link() the wrapper hands
// parser() the allowed category/brand pages. From cloud servers Trendyol answers 403.
import { execFile } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { BlockedError } from "../http.js";
import { Adapter, Product, parsePrice } from "../types.js";
import { TRENDYOL_LISTINGS } from "./trendyol-browse.js";

const run = promisify(execFile);
const WRAPPER = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "py", "run_trendyol.py");

interface Card { id: string; title: string; price: string; link: string }

export const trendyolPy: Adapter = {
  name: "trendyol",
  async search(query, limit) {
    // ProductScrape trendyol.py, unmodified, pointed at robots-allowed listing pages instead of /sr.
    const words = query.toLowerCase().split(/\s+/).filter((w) => w && w !== "*");
    const cards: Card[] = [];
    let lastErr: { url: string; error: string } | null = null;
    for (const path of TRENDYOL_LISTINGS) {
      const workdir = mkdtempSync(join(tmpdir(), "trendyol-"));
      const { stdout } = await run("python3", [WRAPPER, query, String(limit), workdir, `https://www.trendyol.com${path}`],
                                   { maxBuffer: 64 * 1024 * 1024, timeout: 30 * 60 * 1000 });
      const out = JSON.parse(stdout.trim().split("\n").pop()!) as { url: string; error: string | null; products: Card[] };
      if (out.error && !out.products.length) lastErr = { url: out.url, error: out.error };
      for (const c of out.products) {
        if (cards.some((x) => x.link === c.link)) continue;
        if (words.length && !words.every((w) => c.title.toLowerCase().includes(w))) continue;
        cards.push(c);
      }
      if (cards.length >= limit) break;
    }
    if (!cards.length && lastErr) {
      const status = Number(lastErr.error.match(/HTTP Error (\d+)/)?.[1] ?? 0);
      if (status === 403 || status === 429) throw new BlockedError(lastErr.url, status, lastErr.error);
      throw new Error(lastErr.error);
    }
    const now = new Date().toISOString();
    return cards.slice(0, limit).map((c): Product => ({
      source: "trendyol", id: c.id, title: c.title, priceTry: parsePrice(c.price), inStock: null,
      url: c.link, seller: null, condition: "new", scrapedAt: now,
    }));
  },
};
