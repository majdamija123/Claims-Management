"""
Step 5 - the numbers, formatted for the report.

Reads what the previous steps wrote and prints tables that can be pasted straight
into chapters 4 and 5, plus a CSV of the same for Word's table import.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config


def load(name: str) -> dict | list | None:
    path = config.METRICS / name
    if not path.exists():
        print(f"  (missing {name} - run the corresponding step first)")
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def section(title: str) -> None:
    print(f"\n{'=' * 70}\n{title}\n{'=' * 70}")


def main() -> None:
    section("TABLEAU 1 — Réduction du corpus")

    step1 = load("step1_prepare.json")
    step2 = load("step2_rules.json")

    if step1 and step2:
        rows = [
            ("Export brut", step1["rows_raw"]),
            ("Après retrait des pièces jointes corrompues",
             step1["rows_raw"] - step1["office_artefacts_dropped"]),
            ("Après retrait des lignes sans conclusion",
             step1["rows_raw"] - step1["office_artefacts_dropped"]
             - step1["rows_without_conclusion_dropped"]),
            (f"Échantillon retenu ({step1['sample_fraction']:.0%})", step1["rows_sampled"]),
            ("Après nettoyage par règles", step2["rows_out"]),
        ]
        for label, value in rows:
            print(f"  {label:<50} {value:>10,}")

    section("TABLEAU 2 — Effet du nettoyage sur la colonne cible")

    step3 = load("step3_llm.json")

    if step2 and step3:
        consolidation = step3["consolidation"]
        print(f"  {'Conclusions distinctes (brut)':<50} {step2['distinct_conclusions_raw']:>10,}")
        print(f"  {'Après nettoyage par règles':<50} "
              f"{step2['distinct_conclusions_after_rules']:>10,}")
        print(f"  {'Après normalisation sémantique (LLM)':<50} "
              f"{consolidation['distinct_after']:>10,}")
        print(f"\n  Classes fusionnées par le LLM : {consolidation['reduction_pct']}%")

        conclusion_pass = step3["conclusion_pass"]
        print(f"\n  Appels LLM nécessaires        : {conclusion_pass['llm_calls']:,}")
        print(f"  Appels évités par déduplication : "
              f"{conclusion_pass['calls_saved_by_dedup']:,}")

        total = conclusion_pass["llm_calls"] + conclusion_pass["calls_saved_by_dedup"]
        if total:
            print(f"  Réduction du coût de traitement : "
                  f"{100 * conclusion_pass['calls_saved_by_dedup'] / total:.0f}%")

    section("TABLEAU 3 — Comparaison des modèles (étude d'ablation)")

    ablation = load("step4_ablation.json")

    if ablation:
        table = pd.DataFrame(ablation)
        table = table.rename(
            columns={
                "variant": "Variante",
                "model": "Modèle",
                "accuracy": "Accuracy",
                "f1_macro": "F1 macro",
                "f1_weighted": "F1 pondéré",
            }
        )
        print(table.to_string(index=False))

        csv_path = config.METRICS / "tableau_comparaison.csv"
        table.to_csv(csv_path, index=False, encoding="utf-8-sig")
        print(f"\n  CSV pour Word : {csv_path}")

        # What each stage bought, in F1 points - the sentence the report needs.
        best_per_variant = (
            table.groupby("Variante")["F1 macro"].max().to_dict()
        )
        if "raw" in best_per_variant and "rules" in best_per_variant:
            gain = best_per_variant["rules"] - best_per_variant["raw"]
            print(f"\n  Apport du nettoyage par règles : {gain:+.3f} F1 macro")
        if "rules" in best_per_variant and "rules+llm" in best_per_variant:
            gain = best_per_variant["rules+llm"] - best_per_variant["rules"]
            print(f"  Apport du nettoyage sémantique : {gain:+.3f} F1 macro")

    section("ANALYSE DES ERREURS — confusions les plus fréquentes")

    best = load("step4_best.json")

    if best:
        print(f"  Modèle retenu : {best['best']['model']} "
              f"sur la variante « {best['best']['variant']} »")
        print(f"  Classes : {best['classes']:,}   Lignes : {best['rows']:,}\n")

        for confusion in best["top_confusions"]:
            print(f"  {confusion['count']:>4}x  « {confusion['true'][:38]:<38} »"
                  f"  ->  « {confusion['predicted'][:38]} »")

    section("FIGURES DISPONIBLES")

    for figure in sorted(config.FIGURES.glob("*.png")):
        print(f"  {figure}")


if __name__ == "__main__":
    main()
