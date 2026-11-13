// Trendyol via ProductScrape's original trendyol.py (git submodule, unmodified),
// run through py/run_trendyol.py. Needs python3 + beautifulsoup4.
// Note: Trendyol's robots.txt disallows /sr (search); trendyol.py does not check robots,
// so running this is the operator's choice. From cloud servers Trendyol answers 403.
import { execFile } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { BlockedError } from "../http.js";
import { Adapter, Product, parsePrice } from "../types.js";

const run = promisify(execFile);
const WRAPPER = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "py", "run_trendyol.py");

interface Card { id: string; title: string; price: string; link: string }

export const trendyolPy: Adapter = {
  name: "trendyol",
  async search(query, limit) {
    console.warn("  trendyol: running ProductScrape trendyol.py as-is (note: robots.txt disallows /sr)");
    const workdir = mkdtempSync(join(tmpdir(), "trendyol-"));
    const { stdout } = await run("python3", [WRAPPER, query, String(limit), workdir],
                                 { maxBuffer: 64 * 1024 * 1024, timeout: 30 * 60 * 1000 });
    const out = JSON.parse(stdout.trim().split("\n").pop()!) as { url: string; error: string | null; products: Card[] };
    if (out.error && !out.products.length) {
      const status = Number(out.error.match(/HTTP Error (\d+)/)?.[1] ?? 0);
      if (status === 403 || status === 429) throw new BlockedError(out.url, status, out.error);
      throw new Error(out.error);
    }
    const now = new Date().toISOString();
    return out.products.map((c): Product => ({
      source: "trendyol", id: c.id, title: c.title, priceTry: parsePrice(c.price), inStock: null,
      url: c.link, seller: null, condition: "new", scrapedAt: now,
    }));
  },
};
