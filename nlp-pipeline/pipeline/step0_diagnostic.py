"""
Step 0 - diagnose the export before deciding how to clean it.

Every choice in step 1 (which columns to drop, which rows are not complaints,
which columns to merge) was made by looking at the data first. This script is
that look, made repeatable: run it on the real 400 MB export and the same
questions get answered on the real corpus instead of on a smaller extract.

It reads the file and writes reports. It changes nothing.

Usage:
    python pipeline/step0_diagnostic.py

Then read the console output (or outputs/diagnostic/column_report.csv, which
opens directly in Excel) and send it back - the decisions in step 1
(cleaning_rules.py, the column lists in step1_prepare.py) get tuned to what
this actually finds, rather than to what the smaller extract happened to
contain.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config
from src.evaluation import save_json
from src.io_utils import load_export

# Columns worth a closer look than a one-line stat: how many distinct texts
# they hold, and what the most repeated ones are. Only columns actually
# present are inspected, so this list can stay broader than one particular
# export.
TEXT_COLUMNS_OF_INTEREST = ["DESCRIPTION", "CONCLUSION", "CONCLUSION_FO",
                            "CONCLUSION_MO", "CONCLUSION_BO"]

# Column groups that look like natural candidates for merging or for deriving
# a new column from - each reported together so their combined coverage is
# visible, not just each column's coverage alone.
MERGE_CANDIDATES = {
    "Conclusion (FO/MO/BO/finale)": ["CONCLUSION_FO", "CONCLUSION_MO", "CONCLUSION_BO", "CONCLUSION"],
    "Catégorie (libellé + sous-libellé)": ["LIBELLE", "LIBELLE_1"],
}

# A column whose distinct-value count is this close to its row count is
# functioning as an identifier, whatever its name - flagged so it does not
# quietly leak into a feature.
ID_LIKE_UNIQUE_RATIO = 0.95


def column_report(df: pd.DataFrame) -> pd.DataFrame:
    rows = []
    n = len(df)

    for column in df.columns:
        series = df[column]
        non_null = int(series.notna().sum())
        unique = int(series.nunique(dropna=True))

        flags = []
        if non_null == 0:
            flags.append("VIDE_TOTALEMENT")
        elif non_null / n < 0.05:
            flags.append("QUASI_VIDE")
        if non_null > 0 and unique == 1:
            flags.append("CONSTANTE")
        if non_null > 100 and unique / non_null > ID_LIKE_UNIQUE_RATIO:
            flags.append("RESSEMBLE_A_UN_ID")

        top_value, top_count = None, 0
        if non_null > 0:
            counts = series.value_counts()
            top_value, top_count = str(counts.index[0])[:60], int(counts.iloc[0])

        rows.append({
            "colonne": column,
            "type": str(series.dtype),
            "non_vides": non_null,
            "non_vides_pct": round(100 * non_null / n, 1),
            "valeurs_distinctes": unique,
            "valeur_la_plus_frequente": top_value,
            "frequence_top": top_count,
            "frequence_top_pct": round(100 * top_count / n, 1) if n else 0,
            "signalements": ", ".join(flags) if flags else "",
        })

    return pd.DataFrame(rows)


def duplicate_report(df: pd.DataFrame) -> dict:
    exact = int(df.duplicated().sum())

    id_column = "NUMEROREQUETE" if "NUMEROREQUETE" in df.columns else None
    conflicting_ids = 0
    if id_column:
        after_exact = df[~df.duplicated()]
        conflicting_ids = int(after_exact[id_column].duplicated(keep=False).sum())

    return {
        "exact_duplicate_rows": exact,
        "id_column_checked": id_column,
        "rows_sharing_an_id_with_different_content": conflicting_ids,
    }


def text_column_report(df: pd.DataFrame) -> dict:
    report = {}

    for column in TEXT_COLUMNS_OF_INTEREST:
        if column not in df.columns:
            continue

        series = df[column].dropna().astype(str)
        if series.empty:
            continue

        lengths = series.str.len()
        top = series.value_counts().head(10)

        report[column] = {
            "non_null": int(len(series)),
            "distinct": int(series.nunique()),
            "duplication_pct": round(100 * (1 - series.nunique() / len(series)), 1),
            "length_mean": round(float(lengths.mean()), 1),
            "length_median": float(lengths.median()),
            "length_min": int(lengths.min()),
            "length_max": int(lengths.max()),
            "top_10_most_frequent_values": [
                {"value": str(value)[:150], "count": int(count),
                 "pct_of_column": round(100 * count / len(series), 1)}
                for value, count in top.items()
            ],
        }

    return report


def merge_candidate_report(df: pd.DataFrame) -> dict:
    report = {}

    for label, columns in MERGE_CANDIDATES.items():
        present = [c for c in columns if c in df.columns]
        if not present:
            continue

        individual = {c: int(df[c].notna().sum()) for c in present}
        combined = int(df[present].notna().any(axis=1).sum())

        report[label] = {
            "columns": present,
            "non_null_individually": individual,
            "non_null_combined": combined,
            "combined_coverage_pct": round(100 * combined / len(df), 1),
        }

    return report


def main() -> None:
    df = load_export()
    print(f"Export: {len(df):,} rows x {df.shape[1]} columns\n")

    # ------------------------------------------------------------------ columns
    columns = column_report(df)
    columns.to_csv(config.DIAGNOSTIC / "column_report.csv", index=False, encoding="utf-8-sig")

    print("=" * 100)
    print("COLONNES")
    print("=" * 100)
    with pd.option_context("display.max_rows", None, "display.width", 160):
        print(columns.to_string(index=False))

    useless = columns[columns["signalements"] != ""]
    print(f"\n{len(useless)} colonne(s) signalée(s) — voir la colonne 'signalements' ci-dessus.")

    # ----------------------------------------------------------------- duplicates
    duplicates = duplicate_report(df)
    print("\n" + "=" * 100)
    print("DOUBLONS")
    print("=" * 100)
    print(f"  Lignes strictement identiques (toutes colonnes) : {duplicates['exact_duplicate_rows']:,}")
    if duplicates["id_column_checked"]:
        print(f"  Lignes partageant un {duplicates['id_column_checked']} "
              f"avec un contenu différent : "
              f"{duplicates['rows_sharing_an_id_with_different_content']:,}")

    # -------------------------------------------------------------- text columns
    text_stats = text_column_report(df)
    print("\n" + "=" * 100)
    print("COLONNES TEXTE — répétition et gabarits automatiques")
    print("=" * 100)
    for column, stats in text_stats.items():
        print(f"\n  {column}")
        print(f"    Renseignée : {stats['non_null']:,}   Distinctes : {stats['distinct']:,}"
              f"   Duplication : {stats['duplication_pct']}%")
        print(f"    Longueur (caractères) : moyenne={stats['length_mean']}  "
              f"médiane={stats['length_median']}  min={stats['length_min']}  max={stats['length_max']}")
        print(f"    Valeurs les plus fréquentes :")
        for entry in stats["top_10_most_frequent_values"]:
            print(f"      {entry['count']:>7,} ({entry['pct_of_column']:>5.1f}%)  "
                  f"{entry['value']}")

    # ------------------------------------------------------------- merge candidates
    merges = merge_candidate_report(df)
    print("\n" + "=" * 100)
    print("COLONNES CANDIDATES À LA FUSION")
    print("=" * 100)
    for label, info in merges.items():
        print(f"\n  {label}")
        for column, count in info["non_null_individually"].items():
            print(f"    {column:<20} renseignée seule : {count:>7,} "
                  f"({100 * count / len(df):.1f}%)")
        print(f"    -> combinée : {info['non_null_combined']:,} "
              f"({info['combined_coverage_pct']}% des lignes)")

    # --------------------------------------------------------------------- save
    save_json(
        {
            "rows": len(df),
            "columns": df.shape[1],
            "duplicates": duplicates,
            "text_columns": text_stats,
            "merge_candidates": merges,
            "flagged_columns": useless["colonne"].tolist(),
        },
        config.DIAGNOSTIC / "diagnostic_summary.json",
    )

    print(f"\n{'=' * 100}")
    print(f"Détail complet : {config.DIAGNOSTIC / 'column_report.csv'} (s'ouvre dans Excel)")
    print(f"Résumé JSON    : {config.DIAGNOSTIC / 'diagnostic_summary.json'}")
    print("\nEnvoyez ces deux fichiers (ou collez la sortie ci-dessus) pour la suite du nettoyage.")


if __name__ == "__main__":
    main()
