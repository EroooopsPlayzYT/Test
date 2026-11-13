// Polite HTTP client. Replaces ProductScrape's random User-Agent rotation and
// disabled SSL checks with: one honest UA, TLS on, robots.txt checks, a delay
// per host, retries only for transient errors, and a hard stop on bot blocks.
import { EnvHttpProxyAgent, setGlobalDispatcher } from "undici";

if (process.env.HTTPS_PROXY || process.env.HTTP_PROXY) {
  setGlobalDispatcher(new EnvHttpProxyAgent()); // Node's fetch ignores *_PROXY on its own
}

export const USER_AGENT = "Mozilla/5.0 (compatible; handheld-price-notebook/1.0; personal use)";

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
const robotsCache = new Map<string, Promise<Rule[]>>();
const lastHit = new Map<string, number>();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export interface FetchOptions {
  delayMs?: number;      // minimum gap between requests to the same host
  retries?: number;      // for timeouts / 5xx only
  timeoutMs?: number;
  skipRobots?: boolean;  // only for fetching robots.txt itself
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

async function robotsRules(origin: string): Promise<Rule[]> {
  if (!robotsCache.has(origin)) {
    robotsCache.set(origin, (async () => {
      try {
        const txt = await fetchText(`${origin}/robots.txt`, { skipRobots: true, retries: 0 });
        return parseRobots(txt);
      } catch {
        return []; // no readable robots.txt -> no rules
      }
    })());
  }
  return robotsCache.get(origin)!;
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

export async function fetchText(url: string, opts: FetchOptions = {}): Promise<string> {
  const { delayMs = 1500, retries = 2, timeoutMs = 30000, skipRobots = false } = opts;
  const u = new URL(url);
  if (!skipRobots && !isAllowed(await robotsRules(u.origin), u.pathname + u.search)) {
    throw new RobotsDisallowedError(url);
  }
  for (let attempt = 0; ; attempt++) {
    const wait = (lastHit.get(u.host) ?? 0) + delayMs - Date.now();
    if (wait > 0) await sleep(wait);
    lastHit.set(u.host, Date.now());
    try {
      const res = await fetch(url, {
        headers: { "User-Agent": USER_AGENT, "Accept-Language": "tr-TR,tr;q=0.9" },
        signal: AbortSignal.timeout(timeoutMs),
        redirect: "follow",
      });
      const body = await res.text();
      const reason = blockReason(res.status, res.headers, body);
      if (reason) throw new BlockedError(url, res.status, reason);
      if (res.status >= 500 && attempt < retries) { await sleep(5000 * (attempt + 1)); continue; }
      if (!res.ok) throw new Error(`HTTP ${res.status} at ${url}`);
      return body;
    } catch (e) {
      if (e instanceof BlockedError || attempt >= retries) throw e;
      await sleep(5000 * (attempt + 1)); // timeout / network error
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
