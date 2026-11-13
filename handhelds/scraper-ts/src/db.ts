// Append-only price observations, so repeated runs build a history you can train on
// without re-hitting the sites. ProductScrape wrote concatenated (invalid) JSON instead.
import { DatabaseSync } from "node:sqlite";
import type { Product } from "./types.js";

export function openDb(path: string): DatabaseSync {
  const db = new DatabaseSync(path);
  db.exec(`
    CREATE TABLE IF NOT EXISTS observations (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      source      TEXT NOT NULL,
      product_id  TEXT NOT NULL,
      title       TEXT NOT NULL,
      price_try   REAL,
      in_stock    INTEGER,
      seller      TEXT,
      condition   TEXT,
      url         TEXT NOT NULL,
      query       TEXT,
      scraped_at  TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_obs_product ON observations(source, product_id, scraped_at);
    CREATE TABLE IF NOT EXISTS runs (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      source     TEXT NOT NULL,
      query      TEXT NOT NULL,
      status     TEXT NOT NULL,   -- ok | blocked | robots | error
      detail     TEXT,
      rows       INTEGER NOT NULL,
      started_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS alerts (          -- replaces OmBayus' email notification
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      url          TEXT NOT NULL,
      title        TEXT,
      your_price   REAL,
      cheapest     REAL,
      seller_prices TEXT,                          -- JSON array
      checked_at   TEXT NOT NULL
    );
    CREATE VIEW IF NOT EXISTS latest_prices AS
      SELECT o.* FROM observations o
      JOIN (SELECT source, product_id, seller, MAX(scraped_at) AS t
            FROM observations GROUP BY source, product_id, seller) l
        ON o.source = l.source AND o.product_id = l.product_id
       AND o.seller IS l.seller AND o.scraped_at = l.t;
  `);
  return db;
}

export function saveProducts(db: DatabaseSync, rows: Product[], query: string): void {
  const ins = db.prepare(`INSERT INTO observations
    (source, product_id, title, price_try, in_stock, seller, condition, url, query, scraped_at)
    VALUES (?,?,?,?,?,?,?,?,?,?)`);
  db.exec("BEGIN");
  for (const r of rows) {
    ins.run(r.source, r.id, r.title, r.priceTry, r.inStock == null ? null : Number(r.inStock),
            r.seller ?? null, r.condition ?? null, r.url, query, r.scrapedAt);
  }
  db.exec("COMMIT");
}

export function logRun(db: DatabaseSync, source: string, query: string, status: string,
                       detail: string | null, rows: number, startedAt: string): void {
  db.prepare("INSERT INTO runs (source, query, status, detail, rows, started_at) VALUES (?,?,?,?,?,?)")
    .run(source, query, status, detail, rows, startedAt);
}

export function saveAlert(db: DatabaseSync, a: { url: string; title: string | null; yourPrice: number | null;
                          cheapest: number | null; sellerPrices: number[]; checkedAt: string }): void {
  db.prepare("INSERT INTO alerts (url, title, your_price, cheapest, seller_prices, checked_at) VALUES (?,?,?,?,?,?)")
    .run(a.url, a.title, a.yourPrice, a.cheapest, JSON.stringify(a.sellerPrices), a.checkedAt);
}
