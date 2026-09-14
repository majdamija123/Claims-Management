"""
Step 1 - from the raw export to a workable sample.

Loads the parquet, throws away what cannot be used, assembles the target column,
and draws the quarter of the corpus the rest of the pipeline works on.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config
from src import cleaning_rules as rules
from src.evaluation import save_json

# Identifiers carry no signal about the outcome and would let the model cheat if
# they correlate with anything; the rest are columns that are empty or constant in
# this export, or personal data that has no business in a training set.
ID_COLUMNS = [
    "NUMEROREQUETE",
    "IDT_CLIENT",
    "IDT_GROUPE_OBJET_REQUETE",
    "ID_GROUPE_OBJET_REQUETE",
    "IDT_OBJET_REQUETE",
    "ID_OBJET_REQUETE",
]

UNUSABLE_COLUMNS = [
    "OBSERVATION", "DEGRE_URGENCE", "ORGANISMEEMPLOYEUR", "COMMENTAIRE",
    "REPONSE_ENGAGEMENT", "CONCLUSION_VALIDATION", "DATE_NOTIFICATION", "ENG_DATE",
    "STATUT_ENGAGEMENT", "ENG_ETAT", "ENG_ACTEUR", "ANNEE", "APPARTEMENT",
    "DATE_CREATION", "DATE_INITIATION", "DATE_REPONSE", "DATE_CLOTURE",
    "DATENAISSANCE", "CUSTOMER_EMAIL", "NOM", "PRENOM", "LOCALITE", "PAYS", "SEXE",
    "PRODUIT", "MEDIATEUR", "ANALYSER", "CHARGE_TRAITEMENT", "NIVEAU_TRAITEMENT",
    "ETATENGAGEMENT",
]

RENAMES = {
    "IDENTIFIANT_CANAL": "CUSTOMER_CHANNEL_ID",
    "LIBELLE": "REQUEST_CATEGORY",
    "LIBELLE_1": "REQUEST_SUBCATEGORY",
}


def main() -> None:
    if not config.RAW_PARQUET.exists():
        raise SystemExit(
            f"Missing {config.RAW_PARQUET}.\n"
            "Put the export there, or generate a synthetic stand-in with\n"
            "  python tools/make_demo_data.py"
        )

    df = pd.read_parquet(config.RAW_PARQUET)
    stats: dict = {"rows_raw": len(df), "columns_raw": df.shape[1]}
    print(f"Raw export: {len(df):,} rows x {df.shape[1]} columns")

    # --- markup ------------------------------------------------------------
    for column in df.select_dtypes(include="object").columns:
        df[column] = df[column].apply(rules.strip_markup)

    # --- rows that are not complaints --------------------------------------
    artefacts = df["DESCRIPTION"].apply(rules.is_office_artefact)
    print(f"Office artefacts dropped: {int(artefacts.sum()):,}")
    df = df[~artefacts].copy()
    stats["office_artefacts_dropped"] = int(artefacts.sum())

    web_delivered = df["DESCRIPTION"].str.contains(
        "Attestation livrée depuis WEB", case=False, na=False
    )
    df = df[~web_delivered].copy()
    stats["web_autoresponses_dropped"] = int(web_delivered.sum())

    # --- columns -----------------------------------------------------------
    df = df.drop(columns=ID_COLUMNS + UNUSABLE_COLUMNS, errors="ignore")
    df = df.rename(columns=RENAMES)

    # --- the two labels ----------------------------------------------------
    if {"REQUEST_CATEGORY", "REQUEST_SUBCATEGORY"} <= set(df.columns):
        df["REQUEST_LABEL"] = (
            df["REQUEST_CATEGORY"].fillna("") + " : " + df["REQUEST_SUBCATEGORY"].fillna("")
        )
        df["REQUEST_LABEL"] = (
            df["REQUEST_LABEL"].str.replace(r"\s+:\s*$", "", regex=True).str.strip()
        )

    df["CONCLUSION_MERGED"] = df.apply(rules.merge_conclusions, axis=1)
    df = df.drop(columns=rules.CONCLUSION_COLUMNS, errors="ignore")

    before = len(df)
    df = df[df["CONCLUSION_MERGED"].notna()]
    df = df[df["CONCLUSION_MERGED"].astype(str).str.strip() != ""].reset_index(drop=True)
    print(f"Rows without any conclusion dropped: {before - len(df):,}")
    stats["rows_without_conclusion_dropped"] = before - len(df)

    # --- the quarter -------------------------------------------------------
    #
    # Stratified on the raw conclusion so the sample keeps the shape of the full
    # corpus: taking the first 25% of an export ordered by date would sample one
    # season of the year, and the seasons do not receive the same complaints.
    if config.SAMPLE_FRACTION < 1.0:
        groups = [
            group.sample(
                n=max(1, round(len(group) * config.SAMPLE_FRACTION)),
                random_state=config.RANDOM_SEED,
            )
            for _, group in df.groupby("CONCLUSION_MERGED")
        ]
        df = pd.concat(groups).reset_index(drop=True)

    stats["rows_sampled"] = len(df)
    stats["sample_fraction"] = config.SAMPLE_FRACTION
    stats["distinct_conclusions_raw"] = int(df["CONCLUSION_MERGED"].nunique())

    df.to_parquet(config.PREPARED_PARQUET, index=False)
    save_json(stats, config.METRICS / "step1_prepare.json")

    print(f"\nSample kept: {len(df):,} rows ({config.SAMPLE_FRACTION:.0%} of the corpus)")
    print(f"Distinct conclusions before any normalisation: {stats['distinct_conclusions_raw']:,}")
    print(f"Written to {config.PREPARED_PARQUET}")


if __name__ == "__main__":
    main()
