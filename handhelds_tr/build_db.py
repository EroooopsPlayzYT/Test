#!/usr/bin/env python3
"""Load raw handheld listing JSON dumps (one per scraper subagent) into SQLite.

Usage: python3 build_db.py <raw_json_dir> <out.db>
"""
import json, sqlite3, sys, pathlib, re

SCHEMA = """
DROP VIEW IF EXISTS cheapest_per_model;
DROP VIEW IF EXISTS model_summary;
DROP TABLE IF EXISTS listings;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS retailers;

CREATE TABLE retailers (
  id    INTEGER PRIMARY KEY,
  name  TEXT NOT NULL UNIQUE
);
CREATE TABLE products (
  id        INTEGER PRIMARY KEY,
  brand     TEXT NOT NULL,
  model     TEXT NOT NULL,
  category  TEXT NOT NULL CHECK (category IN ('console','handheld_pc','remote_player','retro_handheld')),
  UNIQUE (brand, model)
);
CREATE TABLE listings (
  id                 INTEGER PRIMARY KEY,
  product_id         INTEGER NOT NULL REFERENCES products(id),
  retailer_id        INTEGER NOT NULL REFERENCES retailers(id),
  seller             TEXT,
  product_name       TEXT NOT NULL,
  variant            TEXT,
  price_try          REAL NOT NULL CHECK (price_try > 0),
  original_price_try REAL,
  in_stock           INTEGER,           -- 1/0/NULL
  condition          TEXT NOT NULL DEFAULT 'new',
  warranty           TEXT,
  url                TEXT,
  source_method      TEXT,
  source_file        TEXT,
  scraped_at         TEXT NOT NULL
);
CREATE INDEX idx_listings_product ON listings(product_id);
CREATE INDEX idx_listings_price   ON listings(price_try);

CREATE VIEW model_summary AS
SELECT p.brand, p.model, p.category,
       COUNT(*)                     AS offers,
       COUNT(DISTINCT l.retailer_id) AS retailers,
       MIN(l.price_try)             AS min_price_try,
       ROUND(AVG(l.price_try))      AS avg_price_try,
       MAX(l.price_try)             AS max_price_try
FROM listings l JOIN products p ON p.id = l.product_id
WHERE l.condition = 'new' AND COALESCE(l.in_stock,1) = 1
GROUP BY p.id ORDER BY p.category, min_price_try;

CREATE VIEW cheapest_per_model AS
SELECT p.brand, p.model, r.name AS retailer, l.seller, l.variant, l.price_try, l.url
FROM listings l
JOIN products p  ON p.id = l.product_id
JOIN retailers r ON r.id = l.retailer_id
WHERE l.condition = 'new' AND COALESCE(l.in_stock,1) = 1
  AND l.price_try = (SELECT MIN(l2.price_try) FROM listings l2
                     WHERE l2.product_id = l.product_id AND l2.condition='new'
                       AND COALESCE(l2.in_stock,1)=1)
ORDER BY l.price_try;
"""

CATS = {'console','handheld_pc','remote_player','retro_handheld'}

def num(v):
    if v is None or v == '': return None
    if isinstance(v, (int, float)): return float(v)
    s = re.sub(r'[^\d,\.]', '', str(v))
    if ',' in s and '.' in s: s = s.replace('.', '').replace(',', '.')   # 12.345,67
    elif ',' in s: s = s.replace(',', '.') if len(s.split(',')[-1]) == 2 else s.replace(',', '')
    elif s.count('.') >= 1 and len(s.split('.')[-1]) == 3: s = s.replace('.', '')  # 12.345
    try: return float(s)
    except ValueError: return None

def norm(s): return re.sub(r'\s+', ' ', str(s)).strip() if s else s

def main(raw_dir, out_db):
    con = sqlite3.connect(out_db)
    con.executescript(SCHEMA)
    seen, stats = set(), {}
    for f in sorted(pathlib.Path(raw_dir).glob('*.json')):
        try: rows = json.loads(f.read_text())
        except Exception as e: print(f'skip {f.name}: {e}'); continue
        ok = 0
        for r in rows if isinstance(rows, list) else []:
            price = num(r.get('price_try'))
            brand, model = norm(r.get('brand')), norm(r.get('model'))
            retailer = norm(r.get('retailer'))
            if not (price and brand and model and retailer and r.get('product_name')): continue
            cat = r.get('category') if r.get('category') in CATS else 'console'
            key = (retailer.lower(), (r.get('url') or '').split('?')[0].rstrip('/').lower(),
                   model.lower(), (r.get('variant') or '').lower(), round(price))
            if key in seen: continue
            seen.add(key)
            con.execute('INSERT OR IGNORE INTO retailers(name) VALUES (?)', (retailer,))
            rid = con.execute('SELECT id FROM retailers WHERE name=?', (retailer,)).fetchone()[0]
            con.execute('INSERT OR IGNORE INTO products(brand,model,category) VALUES (?,?,?)', (brand, model, cat))
            pid = con.execute('SELECT id FROM products WHERE brand=? AND model=?', (brand, model)).fetchone()[0]
            st = r.get('in_stock')
            con.execute('''INSERT INTO listings(product_id,retailer_id,seller,product_name,variant,price_try,
                original_price_try,in_stock,condition,warranty,url,source_method,source_file,scraped_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                (pid, rid, norm(r.get('seller')), norm(r['product_name']), norm(r.get('variant')), price,
                 num(r.get('original_price_try')), None if st is None else int(bool(st)),
                 r.get('condition') or 'new', norm(r.get('warranty')), r.get('url'),
                 r.get('source_method'), f.name, r.get('scraped_at') or '2026-11-13'))
            ok += 1
        stats[f.name] = ok
    con.commit()
    for k, v in stats.items(): print(f'{k:20} {v} rows')
    print('TOTAL', con.execute('SELECT COUNT(*) FROM listings').fetchone()[0], 'listings,',
          con.execute('SELECT COUNT(*) FROM products').fetchone()[0], 'models,',
          con.execute('SELECT COUNT(*) FROM retailers').fetchone()[0], 'retailers')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
