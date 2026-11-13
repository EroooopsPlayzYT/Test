import { test } from "node:test";
import assert from "node:assert/strict";
import { pagePlan, parseListing, parseProductPage, NOT_AVAILABLE } from "../src/hybrid/trendyol-hybrid.js";
import { pickUserAgent, USER_AGENT, USER_AGENT_LIST } from "../src/http.js";

test("trendyol.py page plan: limit // 24 full pages, then a remainder page", () => {
  assert.deepEqual(pagePlan(5), [{ page: 1, take: 5 }]);
  assert.deepEqual(pagePlan(50), [{ page: 1, take: null }, { page: 2, take: null }, { page: 3, take: 2 }]);
  assert.equal(pagePlan(100000).length, 208);
});

test("ProductScrape listing selectors", () => {
  const html = `<div class="p-card-wrppr"><a href="/retroid/pocket-6-12gb-p-123456"><span class="prdct-desc-cntnr-name hasRatings" title="Retroid Pocket 6 12GB"></span>
    <div class="prc-box-dscntd">24.999 TL</div></a></div>
    <div class="p-card-wrppr"><a href="/ayn/odin-2-p-777"><span class="prdct-desc-cntnr-name" title="AYN Odin 2"></span><div class="prc-box-dscntd"></div></a></div>`;
  const cards = parseListing(html);
  assert.equal(cards.length, 2);
  assert.equal(cards[0].priceTry, 24999);
  assert.equal(cards[0].link, "https://www.trendyol.com/retroid/pocket-6-12gb-p-123456");
  assert.equal(cards[1].price, NOT_AVAILABLE);
});

test("OmBayus product page: your price vs other sellers, cheapest", () => {
  const html = `<h1 class="pr-new-br">Retroid Pocket 6 8GB 128GB RP6</h1><span class="prc-dsc">26.499,00 TL</span>
    <div class="mrc-new-prc"><span>25.900 TL</span></div><div class="mrc-new-prc"><span>27.100 TL</span></div>`;
  const p = parseProductPage(html, "https://www.trendyol.com/x/retroid-p-555");
  assert.equal(p.id, "RP6");               // trendyol.py: last word of h1.pr-new-br
  assert.equal(p.yourPrice, 26499);
  assert.deepEqual(p.sellerPrices, [25900, 27100]);
  assert.equal(p.cheapest, 25900);
  assert.equal(p.cheaperSellerExists, true);
});

test("UA rotation is opt-in", () => {
  delete process.env.UA_ROTATE;
  assert.equal(pickUserAgent(), USER_AGENT);
  process.env.UA_ROTATE = "1";
  assert.ok(USER_AGENT_LIST.includes(pickUserAgent()));
  delete process.env.UA_ROTATE;
});
