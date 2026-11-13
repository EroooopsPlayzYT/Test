const { chromium } = require('playwright');
const fs = require('fs');
const stores = fs.readFileSync('stores.txt', 'utf8').trim().split('\n').map(l => l.split('|'));
(async () => {
  const b = await chromium.launch();
  for (const [name, home, search] of stores) {
    for (const [kind, url] of [['home', home], ['search', search]]) {
      const p = await b.newPage();
      let status = 'ERR', title = '', hits = 0;
      try {
        const r = await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
        await p.waitForTimeout(6000);            // let any JS challenge finish on its own
        status = r ? r.status() : 'none';
        title = (await p.title()).slice(0, 45);
        hits = ((await p.content()).match(/steam ?deck/gi) || []).length;
      } catch (e) { title = String(e.message).slice(0, 45); }
      console.log(`${name.padEnd(11)} ${kind.padEnd(6)} ${String(status).padEnd(4)} steamdeck=${String(hits).padEnd(4)} ${title}`);
      await p.close();
    }
  }
  await b.close();
})();
