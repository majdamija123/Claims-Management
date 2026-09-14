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
from src.io_utils import load_export
from src.sampling import stratified_subsample

# The columns to drop, as specified by the department supervisor.
#
# Three kinds, all of them dropped for the same practical reason - none of them
# can help predict a complaint's category from its text:
#
#   personal data     NOM, PRENOM, DATENAISSANCE, SEXE, LOCALITE, PAYS,
#                     APPARTEMENT, IDENTIFIANT_CANAL (which holds the customer's
#                     email address) - never belongs in a training set.
#   identifiers       NUMEROREQUETE, IDT_CLIENT and the object/group code pairs -
#                     a model given these latches onto a request number instead
#                     of reading the text.
#   empty or clerical everything the diagnostic flagged as empty, constant or
#                     engagement-workflow bookkeeping.
#
# Two columns on the supervisor's list are deliberately NOT here: LIBELLE and
# NIVEAU_TRAITEMENT. They are the two prediction targets - dropped as *inputs*
# (renamed below, never fed to the model as features) but kept as *labels*,
# without which there is no classification task left to run.
DROPPED_COLUMNS = [
    # personal data
    "NOM", "PRENOM", "DATENAISSANCE", "SEXE", "LOCALITE", "PAYS",
    "APPARTEMENT", "IDENTIFIANT_CANAL", "NATURECLIENT", "ORGANISMEEMPLOYEUR",
    # identifiers
    "NUMEROREQUETE", "IDT_CLIENT", "IDT_GROUPE_OBJET_REQUETE",
    "ID_GROUPE_OBJET_REQUETE", "IDT_OBJET_REQUETE", "ID_OBJET_REQUETE",
    "IDT_PRODUIT_CDG", "PRODUIT",
    # dates
    "ANNEE", "DATE_CREATION", "DATE_INITIATION", "DATE_REPONSE",
    "DATE_CLOTURE", "DATE_NOTIFICATION",
    # channels
    "IDT_CANAL_ENTREE", "IDT_CANAL_SORTIE",
    # empty, constant, or engagement bookkeeping
    "OBSERVATION", "DEGRE_URGENCE", "COMMENTAIRE", "REPONSE_ENGAGEMENT",
    "CONCLUSION_VALIDATION", "ENG_DATE", "ENG_ETAT", "ENG_ACTEUR",
    "STATUT_ENGAGEMENT", "ETATENGAGEMENT", "MEDIATEUR", "ANALYSER",
    "CHARGE_TRAITEMENT",
]

RENAMES = {
    "LIBELLE": "REQUEST_CATEGORY",
    "LIBELLE_1": "REQUEST_SUBCATEGORY",
    "NIVEAU_TRAITEMENT": "ROUTING_LEVEL_CODE",
}

# The three tiers of the circuit, in the workbook's own numbering.
ROUTING_LEVELS = {1: "FRONT_OFFICE", 2: "MIDDLE_OFFICE", 3: "BACK_OFFICE"}


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
    for column in df.select_dtypes(include=["object", "str"]).columns:
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
    dropped = [column for column in DROPPED_COLUMNS if column in df.columns]
    df = df.drop(columns=dropped)
    df = df.rename(columns=RENAMES)
    print(f"Columns dropped: {len(dropped)} of {stats['columns_raw']}")
    stats["columns_dropped"] = len(dropped)

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

    # --- descriptions with nothing in them ----------------------------------
    too_short = df["DESCRIPTION"].fillna("").str.len() < config.MIN_DESCRIPTION_CHARS
    print(f"Descriptions under {config.MIN_DESCRIPTION_CHARS} characters dropped: "
          f"{int(too_short.sum()):,}")
    df = df[~too_short].reset_index(drop=True)
    stats["short_descriptions_dropped"] = int(too_short.sum())

    # --- rows with no label --------------------------------------------------
    # A complaint whose category or routing tier is blank cannot be used by
    # either task: there is nothing to learn to predict.
    unlabelled = df["REQUEST_CATEGORY"].isna() | df["ROUTING_LEVEL"].isna()
    if unlabelled.any():
        print(f"Rows with no category or routing level dropped: {int(unlabelled.sum()):,}")
    df = df[~unlabelled].reset_index(drop=True)
    stats["unlabelled_rows_dropped"] = int(unlabelled.sum())

    stats["rows_usable"] = len(df)

    # --- the working sample --------------------------------------------------
    # Everything above was measured on the whole export. What follows - the LLM
    # pass above all - runs on a stratified quarter of it, which is what the
    # available compute time allows. See config.CORPUS_SAMPLE_FRACTION.
    if config.CORPUS_SAMPLE_FRACTION < 1.0:
        before = len(df)
        before_classes = df["REQUEST_CATEGORY"].nunique()
        df = stratified_subsample(
            df, "REQUEST_CATEGORY", config.CORPUS_SAMPLE_FRACTION,
            config.RANDOM_SEED, config.MIN_SAMPLES_PER_CLASS,
        )
        print(f"\nWorking sample ({config.CORPUS_SAMPLE_FRACTION:.0%} of the usable corpus, "
              f"floored at {config.MIN_SAMPLES_PER_CLASS} rows/category): "
              f"{before:,} -> {len(df):,} rows, "
              f"{before_classes} -> {df['REQUEST_CATEGORY'].nunique()} categories")
        stats["sample_fraction"] = config.CORPUS_SAMPLE_FRACTION
        stats["rows_before_sampling"] = before

    # --- demo cap ------------------------------------------------------------
    if config.DEMO_ROWS and len(df) > config.DEMO_ROWS:
        df = df.sample(n=config.DEMO_ROWS, random_state=config.RANDOM_SEED)
        df = df.reset_index(drop=True)
        print(f"\n! MODE DÉMO : corpus réduit à {len(df)} lignes "
              f"(config.DEMO_ROWS). Chiffres non exploitables pour le rapport.")
        stats["demo_rows"] = config.DEMO_ROWS

    stats["rows_kept"] = len(df)
    # Counted on what was actually kept, so the figure matches the corpus the
    # rest of the pipeline sees rather than the pre-sampling one.
    stats["rows_with_conclusion"] = int(df["CONCLUSION_MERGED"].notna().sum())
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
