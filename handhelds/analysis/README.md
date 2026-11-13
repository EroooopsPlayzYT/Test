# analysis

Offline analysis of `handhelds.db` (no network requests).

```bash
cd handhelds
pip install scikit-learn numpy
python3 build_db.py          # load scraped data
python3 -m analysis.run      # parse, categorise, binary-search, ML fair price, deals
python3 -m analysis.run --budget 25000 --model "Retroid Pocket 6"
python3 -m unittest discover -s analysis/tests -t .
```

| Module | What |
|---|---|
| `parse.py` | RAM/storage (`8/128`, `8GB RAM 256GB`, `12GB+256GB`, `16/1TB`), condition (new/used), sold, bundle, accessory |
| `catalog.py` | ~70 models, brand, category (PC x86, Android, Linux retro, Nintendo, streaming, Sony, clone, phone controller), price tier, priority, Turkish-letter folding |
| `algorithms.py` | binary search (`lower_bound`/`upper_bound`, `under_budget`, `price_band`, `percentile_rank`, `max_price_for_deal`), IQR outliers, group stats, dedupe |
| `ml.py` | fair-price regression (Ridge vs gradient boosting, 5-fold CV, best kept) and a TF-IDF + logistic-regression title categoriser for titles the catalogue misses |
| `run.py` | writes `listings_unified`, `ml_metrics`, views `priority_deals` and `best_deals` |

Priority: 1 = Retroid Pocket 6 8GB/12GB, 2 = other RP6, 3 = AYN, 4 = other Retroid, 5 = Anbernic RG556/RG557, 9 = everything else.
