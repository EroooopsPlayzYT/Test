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
    nums = [[float(r.get(c) or 0) for c in NUM_FEATURES] for r in rows]
    return np.array(cats, dtype=object), np.array(nums, dtype=float)


class _Stack:
    """Glue so one pipeline sees categorical + numeric columns."""

    def __init__(self, estimator):
        self.enc = OneHotEncoder(handle_unknown="ignore", sparse_output=False)
        self.est = estimator

    def fit(self, rows, y):
        c, n = _frame(rows)
        X = np.hstack([self.enc.fit_transform(c), np.log1p(n)])
        self.est.fit(X, y)
        return self

    def predict(self, rows):
        c, n = _frame(rows)
        return self.est.predict(np.hstack([self.enc.transform(c), np.log1p(n)]))

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


def train_price_model(rows):
    """rows: dicts with CAT_FEATURES + NUM_FEATURES + price_try. Returns (model, metrics)."""
    y = np.log(np.array([r["price_try"] for r in rows], dtype=float))
    candidates = {
        "ridge_onehot": lambda: _Stack(Ridge(alpha=1.0)),
        "gradient_boosting": lambda: _Stack(HistGradientBoostingRegressor(max_iter=300, learning_rate=0.08,
                                                                          random_state=7)),
    }
    scores = {name: _cv_mape(make, rows, y) for name, make in candidates.items()}
    best = min(scores, key=scores.get)
    model = candidates[best]().fit(rows, y)
    return model, {"chosen": best, "cv_median_abs_pct_error": scores, "n_train": len(rows)}


def predict_fair(model, rows):
    return [float(math.exp(p)) for p in model.predict(rows)]


def train_title_classifier(titles, labels):
    """Returns (pipeline, cv_accuracy)."""
    pipe = make_pipeline(TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), min_df=1),
                         LogisticRegression(max_iter=2000, C=4.0))
    n_classes = len(set(labels))
    if n_classes < 2:
        return None, None
    folds = min(5, min(labels.count(l) for l in set(labels)))
    acc = None
    if folds >= 2:
        pred = cross_val_predict(pipe, titles, labels, cv=folds)
        acc = float(np.mean([p == t for p, t in zip(pred, labels)]))
    pipe.fit(titles, labels)
    return pipe, acc


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
