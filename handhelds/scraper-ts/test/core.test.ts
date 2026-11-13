// Ports ProductScrape's test_case.py (query -> link) and adds parser/robots tests.
import { test } from "node:test";
import assert from "node:assert/strict";
import { trendyolSearchUrl } from "../src/adapters/trendyol.js";
import { amazonSearchUrl, parseAmazonSearch } from "../src/adapters/amazontr.js";
import { parsePrice } from "../src/types.js";
import { parseRobots, isAllowed } from "../src/http.js";
import { productsFromJsonLd } from "../src/jsonld.js";

test("trendyol search link (from ProductScrape test_query)", () => {
  assert.equal(trendyolSearchUrl("kitap"), "https://www.trendyol.com/sr?q=kitap");
  assert.equal(trendyolSearchUrl("steam deck", 2), "https://www.trendyol.com/sr?q=steam+deck&pi=2");
});

test("amazon search link", () => {
  assert.equal(amazonSearchUrl("steam deck"), "https://www.amazon.com.tr/s?k=steam+deck");
});

test("turkish prices", () => {
  assert.equal(parsePrice("51.457,00 TL"), 51457);
  assert.equal(parsePrice("12.500 TL"), 12500);
  assert.equal(parsePrice("51457.00"), 51457);
  assert.equal(parsePrice(0), null);
  assert.equal(parsePrice("Fiyat yok"), null);
});

test("robots.txt rules", () => {
  const rules = parseRobots("User-agent: Googlebot\nDisallow: /\n\nUser-agent: *\nDisallow: /*-sc-\nAllow: /*-sc-*-p-\nDisallow: /arama\n");
  assert.equal(isAllowed(rules, "/oyun-konsolu-sc-123"), false);
  assert.equal(isAllowed(rules, "/oyun-konsolu-sc-123-p-456"), true);
  assert.equal(isAllowed(rules, "/arama?q=x"), false);
  assert.equal(isAllowed(rules, "/valve-steam-deck-p-1"), true);
});

test("json-ld offers", () => {
  const html = `<script type="application/ld+json">{"@type":"Product","name":"Steam Deck","offers":{"@type":"AggregateOffer","lowPrice":"51457.00","availability":"https://schema.org/InStock","offers":[{"price":"51457.00","seller":{"name":"idefix/Kafkasda"},"url":"https://x/1"}]}}</script>`;
  const [p] = productsFromJsonLd(html);
  assert.equal(p.price, 51457);
  assert.equal(p.inStock, true);
  assert.equal(p.offers[0].seller, "idefix/Kafkasda");
});

test("amazon cards", () => {
  const html = `<div data-asin="B0TEST1"><a href="/Steam-Deck/dp/B0TEST1"><h2>Valve Steam Deck</h2></a><span class="a-price"><span class="a-offscreen">39.999,00 TL</span></span></div>
                <div data-asin="B0AD"><a href="/gp/slredirect/dp/B0AD"><h2>Sponsored</h2></a></div>`;
  const rows = parseAmazonSearch(html);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].priceTry, 39999);
});
