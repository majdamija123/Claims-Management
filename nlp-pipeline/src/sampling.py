"""
Drawing a working sample without losing the rare classes.
"""

from __future__ import annotations

import pandas as pd


def stratified_subsample(
    df: pd.DataFrame, target: str, fraction: float, seed: int, min_per_class: int
) -> pd.DataFrame:
    """
    Take a fraction of the corpus, keeping each class's share intact - with a
    floor, so a class that only just cleared `min_per_class` is not sampled
    straight back under it.

    A plain per-class `frac=fraction` draw is what "a quarter of the corpus"
    means, but taken literally it reintroduces the problem the class filter
    just solved: a class with exactly 15 rows keeps 4 at 25%, well under the
    count that made it viable, and drops out of the task entirely. Measured on
    the pilot extract, a flat draw took REQUEST_CATEGORY from 20 classes to 9.

    Flooring the draw at `min_per_class` - never at more rows than the class
    actually has - shrinks every large class to its share while leaving the
    small-but-viable ones usable.
    """
    parts = []

    for _, group in df.groupby(target, dropna=False):
        keep = max(round(len(group) * fraction), min_per_class)
        parts.append(group.sample(n=min(keep, len(group)), random_state=seed))

    return pd.concat(parts).sample(frac=1.0, random_state=seed).reset_index(drop=True)
