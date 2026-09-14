"""
Step 2 - rule-based cleaning.

Cheap, deterministic, and it removes most of the noise. Everything it leaves behind
is a wording problem, which is what step 3 is for.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config
from src import cleaning_rules as rules
from src.evaluation import save_json


def main() -> None:
    df = pd.read_parquet(config.PREPARED_PARQUET)
    print(f"Loaded {len(df):,} rows")

    df["DESCRIPTION_RULES"] = df["DESCRIPTION"].apply(rules.clean_description)
    df["CONCLUSION_RULES"] = df["CONCLUSION_MERGED"].apply(rules.clean_conclusion)

    # Conclusions that are administrative notes rather than outcomes.
    bad = df["CONCLUSION_RULES"].apply(rules.is_bad_target)
    print(f"Non-outcome conclusions dropped: {int(bad.sum()):,}")
    df = df[~bad].copy()

    # A complaint the cleaner emptied has nothing left to learn from.
    empty = df["DESCRIPTION_RULES"].str.strip() == ""
    print(f"Descriptions emptied by cleaning, dropped: {int(empty.sum()):,}")
    df = df[~empty].copy()

    df = df[df["CONCLUSION_RULES"].notna()].reset_index(drop=True)

    stats = {
        "rows_in": int(len(df) + bad.sum() + empty.sum()),
        "non_outcome_dropped": int(bad.sum()),
        "emptied_descriptions_dropped": int(empty.sum()),
        "rows_out": int(len(df)),
        "distinct_conclusions_raw": int(df["CONCLUSION_MERGED"].nunique()),
        "distinct_conclusions_after_rules": int(df["CONCLUSION_RULES"].nunique()),
        "mean_description_chars_before": round(float(df["DESCRIPTION"].str.len().mean()), 1),
        "mean_description_chars_after": round(
            float(df["DESCRIPTION_RULES"].str.len().mean()), 1
        ),
    }

    df.to_parquet(config.RULES_PARQUET, index=False)
    save_json(stats, config.METRICS / "step2_rules.json")

    print(f"\nDistinct conclusions: {stats['distinct_conclusions_raw']:,}"
          f" -> {stats['distinct_conclusions_after_rules']:,} after rules")
    print(f"Average description length: {stats['mean_description_chars_before']:.0f}"
          f" -> {stats['mean_description_chars_after']:.0f} characters")
    print(f"Written to {config.RULES_PARQUET}")


if __name__ == "__main__":
    main()
