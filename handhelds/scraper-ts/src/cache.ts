// Incremental-mode page cache (ADR-0001): bodies + validators on disk, one file pair per URL.
//   * fresh (checked within maxAgeMs)  -> served from disk, no request at all
//   * stale                            -> conditional request (If-None-Match / If-Modified-Since); 304 = reuse body
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { gunzipSync, gzipSync } from "node:zlib";
import { join } from "node:path";

export interface CacheEntry {
  url: string;
  etag: string | null;
  lastModified: string | null;
  checkedAt: number;   // ms epoch of the last successful check (200 or 304)
}

export const cacheConfig = {
  dir: process.env.SCRAPER_CACHE_DIR ?? "out/httpcache",
  enabled: process.env.SCRAPER_CACHE !== "0",
  itemMaxAgeMs: 24 * 3600 * 1000,   // product/thread pages: skip entirely if checked within this window
  refresh: false,                   // --refresh: ignore freshness (still sends conditional requests)
};

export const stats = { requests: 0, notModified: 0, servedFresh: 0, downloaded: 0 };

function paths(url: string) {
  const h = createHash("sha256").update(url).digest("hex");
  const dir = join(cacheConfig.dir, h.slice(0, 2));
  return { dir, meta: join(dir, `${h}.json`), body: join(dir, `${h}.html.gz`) };
}

export function readCache(url: string): { entry: CacheEntry; body: string } | null {
  if (!cacheConfig.enabled) return null;
  const p = paths(url);
  if (!existsSync(p.meta) || !existsSync(p.body)) return null;
  try {
    return { entry: JSON.parse(readFileSync(p.meta, "utf8")), body: gunzipSync(readFileSync(p.body)).toString("utf8") };
  } catch {
    return null;
  }
}

export function writeCache(url: string, body: string, headers: Headers | null, prev?: CacheEntry): void {
  if (!cacheConfig.enabled) return;
  const p = paths(url);
  mkdirSync(p.dir, { recursive: true });
  const entry: CacheEntry = {
    url,
    etag: headers?.get("etag") ?? prev?.etag ?? null,
    lastModified: headers?.get("last-modified") ?? prev?.lastModified ?? null,
    checkedAt: Date.now(),
  };
  writeFileSync(p.meta, JSON.stringify(entry));
  if (headers) writeFileSync(p.body, gzipSync(body));   // 304 refresh: keep the stored body
}

export function isFresh(entry: CacheEntry, maxAgeMs: number): boolean {
  return !cacheConfig.refresh && maxAgeMs > 0 && Date.now() - entry.checkedAt < maxAgeMs;
}

export function conditionalHeaders(entry: CacheEntry | undefined): Record<string, string> {
  const h: Record<string, string> = {};
  if (entry?.etag) h["If-None-Match"] = entry.etag;
  if (entry?.lastModified) h["If-Modified-Since"] = entry.lastModified;
  return h;
}
