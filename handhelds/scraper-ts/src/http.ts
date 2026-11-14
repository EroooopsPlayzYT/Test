// Polite HTTP client. Replaces ProductScrape's random User-Agent rotation and
// disabled SSL checks with: one honest UA, TLS on, robots.txt checks, a delay
// per host, retries only for transient errors, and a hard stop on bot blocks.
import { EnvHttpProxyAgent, setGlobalDispatcher } from "undici";
import { conditionalHeaders, isFresh, readCache, stats, writeCache } from "./cache.js";

if (process.env.HTTPS_PROXY || process.env.HTTP_PROXY) {
  setGlobalDispatcher(new EnvHttpProxyAgent()); // Node's fetch ignores *_PROXY on its own
}

export const USER_AGENT = "Mozilla/5.0 (compatible; handheld-price-notebook/1.0; personal use)";

// ProductScrape's user_agent_list, refreshed to current browser versions. Opt-in with
// UA_ROTATE=1 (or --ua-rotate). Rotation does NOT change the guardrails: robots.txt is
// still honoured, per-host delays still apply, and a block still stops the source
// (no retrying the same URL under another User-Agent).
export const USER_AGENT_LIST = [
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:144.0) Gecko/20100101 Firefox/144.0",
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0",
];

export function pickUserAgent(): string {
  if (process.env.UA_ROTATE !== "1") return USER_AGENT;
  return USER_AGENT_LIST[Math.floor(Math.random() * USER_AGENT_LIST.length)];   // random.choice()
}

/** The site refused us (403, captcha, Cloudflare/Akamai challenge). Never retried. */
export class BlockedError extends Error {
  constructor(public url: string, public status: number, public reason: string) {
    super(`blocked ${status} at ${url}: ${reason}`);
  }
}

export class RobotsDisallowedError extends Error {
  constructor(public url: string) {
    super(`robots.txt disallows ${url}`);
  }
}

type Rule = { allow: boolean; path: string };
type Robots = { rules: Rule[]; crawlDelayMs: number };
const robotsCache = new Map<string, Promise<Robots>>();
const lastHit = new Map<string, number>();
/** Circuit breaker: once a host blocks us, skip it for the rest of the run. */
const blockedHosts = new Map<string, BlockedError>();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
/** Tunables (tests shrink the backoff so they run fast). */
export const httpConfig = { backoffMs: 5000 };

export interface FetchOptions {
  delayMs?: number;      // minimum gap between requests to the same host
  retries?: number;      // for timeouts / 5xx only
  timeoutMs?: number;
  skipRobots?: boolean;  // only for fetching robots.txt itself
  robotsProbe?: boolean; // internal: a 4xx on robots.txt means "no file"
  maxAgeMs?: number;     // incremental mode: serve from cache without any request if checked this recently
}

/** robots.txt verdict for a URL (used by the headless-browser fetcher). */
export async function robotsAllows(url: string): Promise<boolean> {
  const u = new URL(url);
  return isAllowed((await robotsFor(u.origin)).rules, u.pathname + u.search);
}

/** Waits for a polite slot for `url`'s host (used by the headless-browser fetcher too). */
export async function politeWait(url: string, delayMs = 1500): Promise<void> {
  const u = new URL(url);
  const robots = await robotsFor(u.origin);
  await waitForSlot(u.host, Math.max(delayMs, robots.crawlDelayMs));
}

function blockReason(status: number, headers: Headers, body: string): string | null {
  if (headers.get("cf-mitigated")) return "Cloudflare challenge";
  if (headers.get("x-amzn-waf-action")) return "AWS WAF challenge";
  const proxyReason = headers.get("proxy-reason");
  if (proxyReason) return proxyReason;
  if (status === 403 || status === 429) return `HTTP ${status}`;
  if (/Just a moment\.\.\.|Attention Required! \| Cloudflare|captcha|Olağandışı bir durum|Güvenlik doğrulaması/i
        .test(body.slice(0, 5000))) return "challenge page";
  return null;
}

/** Thrown when robots.txt is a plain 4xx (no file): treated as "no rules", like Google does. */
class NoRobotsFile extends Error {}

/**
 * robots.txt for an origin. 4xx (no file) -> allow everything. 5xx, timeouts, network errors
 * -> disallow everything for this run ("fail closed"), so a flaky robots.txt never turns into
 * "crawl it all".
 */
async function robotsFor(origin: string): Promise<Robots> {
  if (!robotsCache.has(origin)) {
    robotsCache.set(origin, (async () => {
      try {
        const txt = await fetchText(`${origin}/robots.txt`, { skipRobots: true, retries: 1, robotsProbe: true });
        return { rules: parseRobots(txt), crawlDelayMs: parseCrawlDelay(txt) };
      } catch (e) {
        if (e instanceof NoRobotsFile) return { rules: [], crawlDelayMs: 0 };
        if (e instanceof BlockedError) throw e;
        return { rules: [{ allow: false, path: "/" }], crawlDelayMs: 0 };
      }
    })());
  }
  return robotsCache.get(origin)!;
}

/** Crawl-delay for User-agent: * in milliseconds (0 if none). */
export function parseCrawlDelay(txt: string): number {
  let applies = false;
  let lastWasAgent = false;
  for (const raw of txt.split(/\r?\n/)) {
    const m = raw.replace(/#.*/, "").trim().match(/^([A-Za-z-]+)\s*:\s*(.*)$/);
    if (!m) continue;
    const k = m[1].toLowerCase();
    if (k === "user-agent") {
      if (!lastWasAgent) applies = false;
      if (m[2].trim() === "*") applies = true;
      lastWasAgent = true;
      continue;
    }
    lastWasAgent = false;
    if (applies && k === "crawl-delay") {
      const s = Number(m[2]);
      if (Number.isFinite(s) && s > 0) return Math.min(s, 60) * 1000;
    }
  }
  return 0;
}

/** Rules that apply to User-agent: * (longest match wins, Allow wins ties). */
export function parseRobots(txt: string): Rule[] {
  const rules: Rule[] = [];
  let applies = false;
  let lastWasAgent = false;
  for (const raw of txt.split(/\r?\n/)) {
    const line = raw.replace(/#.*/, "").trim();
    const m = line.match(/^([A-Za-z-]+)\s*:\s*(.*)$/);
    if (!m) continue;
    const [, key, value] = m;
    const k = key.toLowerCase();
    if (k === "user-agent") {
      if (!lastWasAgent) applies = false;
      if (value.trim() === "*") applies = true;
      lastWasAgent = true;
      continue;
    }
    lastWasAgent = false;
    if (!applies) continue;
    if ((k === "allow" || k === "disallow") && value) rules.push({ allow: k === "allow", path: value.trim() });
  }
  return rules;
}

function ruleMatches(rulePath: string, path: string): boolean {
  const anchored = rulePath.endsWith("$");
  const pattern = rulePath.replace(/\$$/, "")
    .replace(/[.+?^{}()|[\]\\]/g, "\\$&")
    .replace(/\*/g, ".*");
  return new RegExp("^" + pattern + (anchored ? "$" : "")).test(path);
}

export function isAllowed(rules: Rule[], pathAndQuery: string): boolean {
  let best: Rule | null = null;
  for (const r of rules) {
    if (!ruleMatches(r.path, pathAndQuery)) continue;
    if (!best || r.path.length > best.path.length || (r.path.length === best.path.length && r.allow)) best = r;
  }
  return best ? best.allow : true;
}

/** A non-retryable HTTP status (404, 410, 400…): retrying cannot help. */
export class HttpStatusError extends Error {
  constructor(public url: string, public status: number) {
    super(`HTTP ${status} at ${url}`);
  }
}

/**
 * Reserve the next request slot for a host BEFORE sleeping, so concurrent callers queue up
 * one delay apart instead of all reading the same "last hit" and firing together.
 */
async function waitForSlot(host: string, delayMs: number): Promise<void> {
  const at = Math.max(Date.now(), (lastHit.get(host) ?? 0) + delayMs);
  lastHit.set(host, at);
  const wait = at - Date.now();
  if (wait > 0) await sleep(wait);
}

const MAX_REDIRECTS = 5;

export async function fetchText(url: string, opts: FetchOptions = {}): Promise<string> {
  const { delayMs = 1500, retries = 2, timeoutMs = 30000, skipRobots = false, robotsProbe = false, maxAgeMs = 0 } = opts;
  const cached = robotsProbe ? null : readCache(url);
  if (cached && isFresh(cached.entry, maxAgeMs)) {
    stats.servedFresh++;
    return cached.body;                       // checked recently: no request at all
  }
  let current = url;
  for (let hop = 0; ; hop++) {
    const u = new URL(current);
    const tripped = blockedHosts.get(u.host);
    if (tripped) throw new BlockedError(current, tripped.status, `host already blocked this run (${tripped.reason})`);
    let delay = delayMs;
    if (!skipRobots) {
      const robots = await robotsFor(u.origin);
      if (!isAllowed(robots.rules, u.pathname + u.search)) throw new RobotsDisallowedError(current);
      delay = Math.max(delayMs, robots.crawlDelayMs);   // honour Crawl-delay if it is longer
    }
    const res = await fetchOnce(current, { delay, retries, timeoutMs, robotsProbe,
                                          validators: current === url ? cached?.entry : undefined });
    if (res.redirectTo) {
      // follow redirects ourselves so every hop is checked against ITS host's robots.txt
      if (hop >= MAX_REDIRECTS) throw new Error(`too many redirects from ${url}`);
      current = new URL(res.redirectTo, current).href;
      continue;
    }
    if (res.notModified && cached) {
      writeCache(url, cached.body, null, cached.entry);   // still valid: bump checkedAt, keep body
      return cached.body;
    }
    if (!robotsProbe) writeCache(url, res.body!, res.headers ?? null);
    return res.body!;
  }
}

async function fetchOnce(url: string, o: { delay: number; retries: number; timeoutMs: number; robotsProbe: boolean;
                                          validators?: import("./cache.js").CacheEntry }):
    Promise<{ body?: string; redirectTo?: string; notModified?: boolean; headers?: Headers }> {
  const u = new URL(url);
  for (let attempt = 0; ; attempt++) {
    await waitForSlot(u.host, o.delay);
    try {
      const res = await fetch(url, {
        headers: { "User-Agent": pickUserAgent(), "Accept-Language": "tr-TR,tr;q=0.9",
                   ...conditionalHeaders(o.validators) },
        signal: AbortSignal.timeout(o.timeoutMs),
        redirect: "manual",
      });
      stats.requests++;
      if (res.status === 304) {
        stats.notModified++;
        await res.body?.cancel();
        return { notModified: true };
      }
      if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
        await res.body?.cancel();
        return { redirectTo: res.headers.get("location")! };
      }
      const body = await res.text();
      const reason = blockReason(res.status, res.headers, body);
      if (reason) {
        const err = new BlockedError(url, res.status, reason);
        blockedHosts.set(u.host, err);
        throw err;
      }
      if (res.ok) { stats.downloaded++; return { body, headers: res.headers }; }
      if (o.robotsProbe && res.status >= 400 && res.status < 500) throw new NoRobotsFile();
      if (res.status >= 500) throw new Error(`HTTP ${res.status} at ${url}`);   // transient: retried below
      throw new HttpStatusError(url, res.status);                                 // 404/410/…: never retried
    } catch (e) {
      if (e instanceof BlockedError || e instanceof HttpStatusError || e instanceof NoRobotsFile) throw e;
      if (attempt >= o.retries) throw e;
      await sleep(httpConfig.backoffMs * (attempt + 1));   // 5xx, timeout or network error
    }
  }
}

/** Run tasks with at most `limit` in flight (ProductScrape started one thread per product). */
export async function pool<T, R>(items: T[], limit: number, fn: (item: T, i: number) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return out;
}
