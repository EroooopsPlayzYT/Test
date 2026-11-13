"""Machine learning on the local database (no network).

1. Fair-price regression: predicts log(price) from model, category, brand, RAM, storage,
   condition, bundle flag and source type. Two candidates (Ridge on one-hot features and a
   gradient-boosted tree model) are compared with 5-fold cross-validation; the better one is
   refit on all clean rows and used to price every listing. deal_pct = (fair - price) / fair.
2. Title categoriser: TF-IDF character n-grams + logistic regression, trained on titles the
   regex catalogue already labelled, then used for titles the catalogue could not place.
"""
import math

import numpy as np
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.model_selection import KFold, cross_val_predict
from sklearn.pipeline import Pipeline, make_pipeline
from sklearn.preprocessing import OneHotEncoder

CAT_FEATURES = ["model", "category", "brand", "condition", "source_kind", "bundle"]
NUM_FEATURES = ["ram_gb", "storage_gb"]


def _frame(rows):
    cats = [[str(r.get(c)) for c in CAT_FEATURES] for r in rows]
    # log size, plus "is it known" flags: an unknown RAM must not look like a tiny RAM
    nums = [[math.log1p(float(r.get(c) or 0)) for c in NUM_FEATURES]
            + [1.0 if r.get(c) else 0.0 for c in NUM_FEATURES] for r in rows]
    return np.array(cats, dtype=object), np.array(nums, dtype=float)


class _Stack:
    """Glue so one pipeline sees categorical + numeric columns."""

    def __init__(self, estimator):
        self.enc = OneHotEncoder(handle_unknown="ignore", sparse_output=False)
        self.est = estimator

    def fit(self, rows, y):
        c, n = _frame(rows)
        oh = self.enc.fit_transform(c)
        if isinstance(self.est, HistGradientBoostingRegressor):
            # More RAM / storage can never lower the price (fixes RP6 12GB < 8GB).
            self.est.set_params(monotonic_cst=[0] * oh.shape[1] + [1] * len(NUM_FEATURES) + [0] * len(NUM_FEATURES))
        self.est.fit(np.hstack([oh, n]), y)
        return self

    def predict(self, rows):
        c, n = _frame(rows)
        return self.est.predict(np.hstack([self.enc.transform(c), n]))

    def get_params(self, deep=False):
        return {"estimator": self.est}

    def set_params(self, **p):
        return self


def _cv_mape(make, rows, y, folds=5):
    kf = KFold(n_splits=min(folds, len(rows)), shuffle=True, random_state=7)
    errs = []
    for tr, te in kf.split(rows):
        m = make().fit([rows[i] for i in tr], y[tr])
        pred = m.predict([rows[i] for i in te])
        errs.extend(abs(math.exp(p) - math.exp(t)) / math.exp(t) for p, t in zip(pred, y[te]))
    return float(np.median(errs))


CANDIDATES = {
    "ridge_onehot": lambda: _Stack(Ridge(alpha=1.0)),
    # small leaves so rare models (RP3+, RP4 Pro, RP5…) get their own price instead of one shared value
    "gradient_boosting": lambda: _Stack(HistGradientBoostingRegressor(max_iter=400, learning_rate=0.06,
                                                                      min_samples_leaf=3, l2_regularization=0.5,
                                                                      random_state=7)),
}


def train_price_model(rows):
    """rows: dicts with CAT_FEATURES + NUM_FEATURES + price_try. Returns (model, metrics)."""
    y = np.log(np.array([r["price_try"] for r in rows], dtype=float))
    scores = {name: _cv_mape(make, rows, y) for name, make in CANDIDATES.items()}
    best = min(scores, key=scores.get)
    model = CANDIDATES[best]().fit(rows, y)
    return model, {"chosen": best, "cv_median_abs_pct_error": scores, "n_train": len(rows)}


def out_of_fold(name, rows, folds=5):
    """Prediction for every training row from a model that never saw that row (no in-sample leakage)."""
    y = np.log(np.array([r["price_try"] for r in rows], dtype=float))
    pred = np.zeros(len(rows))
    for tr, te in KFold(n_splits=min(folds, len(rows)), shuffle=True, random_state=11).split(rows):
        m = CANDIDATES[name]().fit([rows[i] for i in tr], y[tr])
        pred[te] = m.predict([rows[i] for i in te])
    return [float(math.exp(p)) for p in pred]


def predict_fair(model, rows):
    return [float(math.exp(p)) for p in model.predict(rows)]


def train_title_classifier(titles, labels):
    """Returns (pipeline, metrics) with overall and per-class cross-validated accuracy."""
    pipe = make_pipeline(TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), min_df=1),
                         LogisticRegression(max_iter=2000, C=4.0))
    n_classes = len(set(labels))
    if n_classes < 2:
        return None, None
    folds = min(5, min(labels.count(l) for l in set(labels)))
    metrics = None
    if folds >= 2:
        pred = cross_val_predict(pipe, titles, labels, cv=folds)
        per_class = {}
        for c in sorted(set(labels)):
            idx = [i for i, t in enumerate(labels) if t == c]
            per_class[c] = {"n": len(idx), "recall": round(float(np.mean([pred[i] == c for i in idx])), 3)}
        overall = float(np.mean([p == t for p, t in zip(pred, labels)]))
        metrics = {"accuracy": round(overall, 4),
                   "balanced_accuracy": round(float(np.mean([v["recall"] for v in per_class.values()])), 4),
                   "per_class": per_class}
    pipe.fit(titles, labels)
    return pipe, metrics


def classify_titles(pipe, titles, min_confidence=0.6):
    """-> list of (label or None, confidence)."""
    if pipe is None or not titles:
        return [(None, 0.0)] * len(titles)
    proba = pipe.predict_proba(titles)
    labels = pipe.classes_
    out = []
    for row in proba:
        i = int(np.argmax(row))
        out.append((labels[i] if row[i] >= min_confidence else None, float(row[i])))
    return out
