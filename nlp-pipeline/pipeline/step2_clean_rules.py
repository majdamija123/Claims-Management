"""
Step 2 - rule-based cleaning.

Cheap, deterministic, and it removes most of the noise. What it cannot do is
normalise wording, which is what step 3 is for.

Two columns are cleaned, for two different reasons:

  DESCRIPTION  the model's input. Cleaned conservatively - anything ambiguous is
               left in rather than risk deleting the sentence that carries the
               request.
  CONCLUSION   not a target here (too sparse in this export), but the column the
               LLM normalisation is demonstrated on, so it is cleaned too.
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
    print(f"Loaded {len(df):,} complaints")

    df["DESCRIPTION_RULES"] = df["DESCRIPTION"].apply(rules.clean_description)
    df["CONCLUSION_RULES"] = df["CONCLUSION_MERGED"].apply(rules.clean_conclusion)

    # A description the cleaner emptied held nothing but contact details.
    emptied = df["DESCRIPTION_RULES"].str.len() < config.MIN_DESCRIPTION_CHARS
    print(f"Descriptions left empty by cleaning, dropped: {int(emptied.sum()):,}")
    df = df[~emptied].reset_index(drop=True)

    stats = {
        "rows_in": int(len(df) + emptied.sum()),
        "emptied_descriptions_dropped": int(emptied.sum()),
        "rows_out": int(len(df)),
        "mean_description_chars_before": round(float(df["DESCRIPTION"].str.len().mean()), 1),
        "mean_description_chars_after": round(
            float(df["DESCRIPTION_RULES"].str.len().mean()), 1
        ),
        "distinct_descriptions_before": int(df["DESCRIPTION"].nunique()),
        "distinct_descriptions_after": int(df["DESCRIPTION_RULES"].nunique()),
        "conclusions_present": int(df["CONCLUSION_RULES"].notna().sum()),
        "distinct_conclusions_after_rules": int(df["CONCLUSION_RULES"].nunique()),
    }

    df.to_parquet(config.RULES_PARQUET, index=False)
    save_json(stats, config.METRICS / "step2_rules.json")

    print(f"\nAverage description: {stats['mean_description_chars_before']:.0f}"
          f" -> {stats['mean_description_chars_after']:.0f} characters")
    print(f"Distinct descriptions: {stats['distinct_descriptions_before']:,}"
          f" -> {stats['distinct_descriptions_after']:,}"
          f"  (cleaning merged near-identical texts)")
    print(f"Written to {config.RULES_PARQUET}")


if __name__ == "__main__":
    main()
