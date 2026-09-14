"""
Step 3 - semantic cleaning with the local model.

Two passes, for two different reasons:

- The conclusion is the *target*. Normalising it merges classes that were only
  distinct because two agents phrased the same outcome differently. This is the
  pass that decides whether the classification problem is solvable at all.
- The description is the *input*. Normalising it strips the politeness and the
  boilerplate that survive the regular expressions, leaving the request itself.

The conclusion pass is the one that pays: the column repeats itself heavily, so
deduplication cuts the number of model calls several-fold.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config
from src.cleaning_llm import (
    CONCLUSION_PROMPT,
    DESCRIPTION_PROMPT,
    LlmCleaner,
    consolidation_ratio,
)
from src.evaluation import save_json


def main() -> None:
    df = pd.read_parquet(config.RULES_PARQUET)
    print(f"Loaded {len(df):,} rows\n")

    cleaner = LlmCleaner(
        model=config.LLM_MODEL,
        temperature=config.LLM_TEMPERATURE,
        cache_path=config.CACHE / "llm_cache.json",
        disable_thinking=config.LLM_DISABLE_THINKING,
    )

    # ---------------------------------------------------------------- target
    df["CONCLUSION_LLM"], conclusion_stats = cleaner.clean_series(
        df["CONCLUSION_RULES"], CONCLUSION_PROMPT, "CONCLUSION",
        max_calls=config.LLM_CONCLUSION_LIMIT,
    )

    consolidation = consolidation_ratio(df["CONCLUSION_RULES"], df["CONCLUSION_LLM"])
    print(
        f"\nConclusions: {consolidation['distinct_before']:,} distinct"
        f" -> {consolidation['distinct_after']:,}"
        f" ({consolidation['reduction_pct']}% merged)"
    )

    # ---------------------------------------------------------------- input
    #
    # Descriptions are nearly all unique, so there is no deduplication windfall
    # here - the cost is one call per row. The cap keeps a demonstration run to
    # minutes; rows beyond it keep their rule-cleaned text, which is still usable.
    # Capped by distinct values rather than by rows: the cost is one call per
    # distinct text, so a row cap does not bound the runtime, and cleaning the
    # first N *values* reaches every row that repeats them instead of only the
    # top of the table.
    cleaned_descriptions, description_stats = cleaner.clean_series(
        df["DESCRIPTION_RULES"], DESCRIPTION_PROMPT, "DESCRIPTION",
        max_calls=config.LLM_DESCRIPTION_LIMIT,
    )

    df["DESCRIPTION_LLM"] = cleaned_descriptions

    # A model that answered with an empty string would silently delete a complaint.
    fallback = df["DESCRIPTION_LLM"].fillna("").str.strip() == ""
    df.loc[fallback, "DESCRIPTION_LLM"] = df.loc[fallback, "DESCRIPTION_RULES"]

    stats = {
        "conclusion_pass": conclusion_stats,
        "description_pass": description_stats,
        "consolidation": consolidation,
        "descriptions_rewritten_by_llm": int(
            (df["DESCRIPTION_LLM"] != df["DESCRIPTION_RULES"]).sum()
        ),
        "descriptions_kept_rule_cleaned": int(
            (df["DESCRIPTION_LLM"] == df["DESCRIPTION_RULES"]).sum()
        ),
        "empty_llm_answers_fallen_back": int(fallback.sum()),
    }

    df.to_parquet(config.LLM_PARQUET, index=False)
    save_json(stats, config.METRICS / "step3_llm.json")

    saved = conclusion_stats["calls_saved_by_dedup"]
    print(f"\nCalls avoided by deduplicating the conclusions: {saved:,}")
    print(f"Written to {config.LLM_PARQUET}")


if __name__ == "__main__":
    main()
