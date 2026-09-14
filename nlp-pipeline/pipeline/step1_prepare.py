"""
Step 1 - from the Excel export to a workable corpus.

Converts the workbook to parquet, strips markup, removes the rows that are not
complaints, drops the columns that carry nothing, and assembles the two targets.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config
from src import cleaning_rules as rules
from src.evaluation import save_json

# Identifiers would let the model latch onto a request number instead of the text.
ID_COLUMNS = [
    "NUMEROREQUETE", "IDT_CLIENT", "IDT_GROUPE_OBJET_REQUETE",
    "ID_GROUPE_OBJET_REQUETE", "IDT_OBJET_REQUETE", "ID_OBJET_REQUETE",
]

# Personal data, which has no business in a training set, plus the columns this
# export leaves empty or constant.
UNUSABLE_COLUMNS = [
    "OBSERVATION", "DEGRE_URGENCE", "ORGANISMEEMPLOYEUR", "COMMENTAIRE",
    "REPONSE_ENGAGEMENT", "CONCLUSION_VALIDATION", "DATE_NOTIFICATION", "ENG_DATE",
    "STATUT_ENGAGEMENT", "ENG_ETAT", "ENG_ACTEUR", "ANNEE", "APPARTEMENT",
    "DATE_CREATION", "DATE_INITIATION", "DATE_REPONSE", "DATE_CLOTURE",
    "DATENAISSANCE", "NOM", "PRENOM", "LOCALITE", "PAYS", "SEXE",
    "MEDIATEUR", "ANALYSER", "CHARGE_TRAITEMENT", "ETATENGAGEMENT",
    "IDENTIFIANT_CANAL",
]

RENAMES = {
    "LIBELLE": "REQUEST_CATEGORY",
    "LIBELLE_1": "REQUEST_SUBCATEGORY",
    "NIVEAU_TRAITEMENT": "ROUTING_LEVEL_CODE",
    "IDT_CANAL_ENTREE": "CHANNEL_IN",
}

# The three tiers of the circuit, in the workbook's own numbering.
ROUTING_LEVELS = {1: "FRONT_OFFICE", 2: "MIDDLE_OFFICE", 3: "BACK_OFFICE"}


def load_export() -> pd.DataFrame:
    """Read from the parquet copy, converting the .xls first if needed."""
    if not config.RAW_PARQUET.exists():
        if not config.RAW_EXCEL.exists():
            raise SystemExit(
                f"Neither {config.RAW_PARQUET.name} nor {config.RAW_EXCEL.name} found in "
                f"{config.DATA}.\nPut the export there, or generate a synthetic stand-in "
                f"with\n  python tools/make_demo_data.py"
            )
        print(f"{config.RAW_PARQUET.name} not found — converting the .xls first.\n"
              f"(On the full export this can take several minutes; see\n"
              f" tools/convert_xls_to_parquet.py if you want to run that step alone.)\n")
        import subprocess
        subprocess.run(
            [sys.executable, str(Path(__file__).parent.parent / "tools" / "convert_xls_to_parquet.py")],
            check=True,
        )

    print(f"Reading {config.RAW_PARQUET.name}")
    return pd.read_parquet(config.RAW_PARQUET)


def main() -> None:
    df = load_export()
    stats: dict = {"rows_raw": len(df), "columns_raw": df.shape[1]}
    print(f"\nExport: {len(df):,} rows x {df.shape[1]} columns")

    # --- duplicate rows -------------------------------------------------------
    #
    # Two kinds of duplication, and only one of them is a defect:
    #
    # - Identical rows across every column are the same record exported twice -
    #   a genuine export artefact, removed here.
    # - Identical NUMEROREQUETE with different content would mean the export
    #   assigned one request number to two different records - a data integrity
    #   problem worth surfacing, not silently dropping.
    #
    # What is deliberately NOT treated as a duplicate: two different complaints
    # that happen to share the same wording (e.g. two customers both writing
    # "Attestation livrée depuis WEB", or two independently asking about a late
    # pension). Removing those would delete real, distinct records and quietly
    # shrink whichever categories are common - exactly the classes a classifier
    # most needs examples of.
    exact_duplicates = df.duplicated()
    print(f"Exact duplicate rows (every column identical): {int(exact_duplicates.sum()):,}")
    df = df[~exact_duplicates].copy()
    stats["exact_duplicate_rows_dropped"] = int(exact_duplicates.sum())

    # Any NUMEROREQUETE still duplicated at this point is, by construction, a row
    # that differs from its sibling somewhere else - the two are describing the
    # same request number but not the same content. Kept, and reported: this is
    # an export quality issue worth naming in the report rather than one to hide.
    conflicting_ids = df["NUMEROREQUETE"].duplicated(keep=False)
    if conflicting_ids.any():
        print(f"! {int(conflicting_ids.sum()):,} rows share a NUMEROREQUETE with "
              f"different content — kept as separate records.")
    stats["rows_with_conflicting_request_number"] = int(conflicting_ids.sum())

    # --- markup -------------------------------------------------------------
    # Descriptions arrive as HTML fragments: <br>, <hr>, and the literal string
    # "null" where the source system had nothing to write.
    for column in df.select_dtypes(include="object").columns:
        df[column] = df[column].apply(rules.strip_markup)

    # --- rows that are not complaints ---------------------------------------
    artefacts = df["DESCRIPTION"].apply(rules.is_office_artefact)
    df = df[~artefacts].copy()
    stats["office_artefacts_dropped"] = int(artefacts.sum())

    automated = df["DESCRIPTION"].str.contains(
        "|".join(config.AUTOMATED_ACT_PATTERNS), case=False, na=False, regex=True
    )
    print(f"Self-service acts dropped: {int(automated.sum()):,} "
          f"({100 * automated.mean():.1f}% of the export)")
    df = df[~automated].copy()
    stats["automated_acts_dropped"] = int(automated.sum())

    # --- columns ------------------------------------------------------------
    df = df.drop(columns=ID_COLUMNS + UNUSABLE_COLUMNS, errors="ignore")
    df = df.rename(columns=RENAMES)

    # --- targets ------------------------------------------------------------
    df["REQUEST_LABEL"] = (
        df["REQUEST_CATEGORY"].fillna("") + " : " + df["REQUEST_SUBCATEGORY"].fillna("")
    )
    df["REQUEST_LABEL"] = (
        df["REQUEST_LABEL"].str.replace(r"\s+:\s*$", "", regex=True).str.strip()
    )

    df["ROUTING_LEVEL"] = (
        pd.to_numeric(df["ROUTING_LEVEL_CODE"], errors="coerce")
        .map(ROUTING_LEVELS)
    )

    # --- the conclusion, kept for the normalisation demonstration ------------
    #
    # Too sparse in this export to be a classification target, but the LLM
    # normalisation of it is a result in its own right, so the column is assembled
    # and carried forward rather than dropped.
    df["CONCLUSION_MERGED"] = df.apply(rules.merge_conclusions, axis=1)
    df = df.drop(columns=rules.CONCLUSION_COLUMNS, errors="ignore")
    stats["rows_with_conclusion"] = int(df["CONCLUSION_MERGED"].notna().sum())

    # --- descriptions with nothing in them ----------------------------------
    too_short = df["DESCRIPTION"].fillna("").str.len() < config.MIN_DESCRIPTION_CHARS
    print(f"Descriptions under {config.MIN_DESCRIPTION_CHARS} characters dropped: "
          f"{int(too_short.sum()):,}")
    df = df[~too_short].reset_index(drop=True)
    stats["short_descriptions_dropped"] = int(too_short.sum())

    stats["rows_kept"] = len(df)
    stats["distinct_descriptions"] = int(df["DESCRIPTION"].nunique())
    stats["duplicate_description_pct"] = round(
        100 * (1 - df["DESCRIPTION"].nunique() / len(df)), 1
    )
    stats["classes_request_category"] = int(df["REQUEST_CATEGORY"].nunique())
    stats["classes_routing_level"] = int(df["ROUTING_LEVEL"].nunique())

    df.to_parquet(config.PREPARED_PARQUET, index=False)
    save_json(stats, config.METRICS / "step1_prepare.json")

    print(f"\nComplaints kept: {len(df):,} of {stats['rows_raw']:,} "
          f"({100 * len(df) / stats['rows_raw']:.1f}% of the export)")
    print(f"Distinct descriptions: {stats['distinct_descriptions']:,} "
          f"({stats['duplicate_description_pct']}% duplicates)")
    print(f"Categories: {stats['classes_request_category']}  |  "
          f"Routing levels: {stats['classes_routing_level']}")
    print(f"Rows carrying a conclusion: {stats['rows_with_conclusion']:,}")
    print(f"\nWritten to {config.PREPARED_PARQUET}")


if __name__ == "__main__":
    main()
