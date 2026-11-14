// Fake-server tests for the polite HTTP client (review findings #1, #2 and suggestions #4, #5).
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { AddressInfo } from "node:net";
import { fetchText, httpConfig, parseCrawlDelay, HttpStatusError, RobotsDisallowedError } from "../src/http.js";

httpConfig.backoffMs = 20;
const hits: Record<string, number[]> = {};
let server: Server;
let base = "";

before(async () => {
  server = createServer((req, res) => {
    const [path] = (req.url ?? "/").split("?");
    (hits[path] ??= []).push(Date.now());
    if (path === "/robots.txt") {
      res.writeHead(200, { "content-type": "text/plain" });
      return res.end("User-agent: *\nDisallow: /private\nCrawl-delay: 0.3\n");
    }
    if (path === "/gone") { res.writeHead(410); return res.end("gone"); }
    if (path === "/flaky") {
      const n = hits[path].length;
      if (n < 2) { res.writeHead(503); return res.end("busy"); }
      res.writeHead(200); return res.end("ok after retry");
    }
    if (path === "/to-private") { res.writeHead(302, { location: "/private/secret" }); return res.end(); }
    res.writeHead(200, { "content-type": "text/html" });
    res.end(`<html>page ${path}</html>`);
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(() => server.close());

test("parallel requests to one host are spaced out (no burst)", async () => {
  await Promise.all([1, 2, 3, 4].map((i) => fetchText(`${base}/p${i}`, { delayMs: 50 })));
  const times = Object.entries(hits).filter(([p]) => /^\/p\d$/.test(p)).map(([, t]) => t[0]).sort((a, b) => a - b);
  for (let i = 1; i < times.length; i++) {
    // Crawl-delay 0.3 s beats delayMs 50 ms; allow a little timer slack
    assert.ok(times[i] - times[i - 1] >= 280, `gap ${times[i] - times[i - 1]} ms`);
  }
});

test("404/410 is not retried", async () => {
  await assert.rejects(fetchText(`${base}/gone`, { delayMs: 0 }), HttpStatusError);
  assert.equal(hits["/gone"].length, 1);
});

test("5xx is retried, then succeeds", async () => {
  assert.equal(await fetchText(`${base}/flaky`, { delayMs: 0 }), "ok after retry");
  assert.equal(hits["/flaky"].length, 2);
});

test("robots.txt is enforced, also after a redirect", async () => {
  await assert.rejects(fetchText(`${base}/private/x`), RobotsDisallowedError);
  await assert.rejects(fetchText(`${base}/to-private`, { delayMs: 0 }), RobotsDisallowedError);
  assert.equal(hits["/private/secret"], undefined);   // never requested
});

test("unreachable robots.txt fails closed", async () => {
  // nothing listens on this port: robots.txt fetch fails -> everything disallowed
  await assert.rejects(fetchText("http://127.0.0.1:9/page", { delayMs: 0, timeoutMs: 500 }), RobotsDisallowedError);
});

test("crawl-delay parsing", () => {
  assert.equal(parseCrawlDelay("User-agent: *\nCrawl-delay: 2\n"), 2000);
  assert.equal(parseCrawlDelay("User-agent: Googlebot\nCrawl-delay: 9\n\nUser-agent: *\nDisallow: /x\n"), 0);
});
