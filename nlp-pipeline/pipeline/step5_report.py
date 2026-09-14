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
    print(f"\n{'=' * 72}\n{title}\n{'=' * 72}")


def main() -> None:
    step1 = load("step1_prepare.json")
    step2 = load("step2_rules.json")
    step3 = load("step3_llm.json")

    section("TABLEAU 1 — Du fichier source au corpus exploitable")

    if step1 and step2:
        total = step1["rows_raw"]
        rows = [
            ("Export Excel brut", total),
            ("− pièces jointes corrompues", -step1["office_artefacts_dropped"]),
            ("− actes automatiques (attestations libre-service)",
             -step1["automated_acts_dropped"]),
            ("− descriptions trop courtes", -step1["short_descriptions_dropped"]),
            ("− descriptions vidées par le nettoyage",
             -step2["emptied_descriptions_dropped"]),
            ("= Réclamations exploitables", step2["rows_out"]),
        ]
        for label, value in rows:
            print(f"  {label:<52} {value:>+9,}" if value < 0
                  else f"  {label:<52} {value:>10,}")

        print(f"\n  Soit {100 * step2['rows_out'] / total:.1f}% de l'export d'origine.")
        print(f"  Catégories distinctes : {step1['classes_request_category']}"
              f"   Niveaux de traitement : {step1['classes_routing_level']}")

    section("TABLEAU 2 — Effet du nettoyage sur le texte")

    if step2:
        print(f"  {'Longueur moyenne avant nettoyage':<52} "
              f"{step2['mean_description_chars_before']:>9.0f} car.")
        print(f"  {'Longueur moyenne après nettoyage':<52} "
              f"{step2['mean_description_chars_after']:>9.0f} car.")
        reduction = 100 * (
            1 - step2["mean_description_chars_after"] / step2["mean_description_chars_before"]
        )
        print(f"  {'Réduction':<52} {reduction:>9.1f} %")
        print(f"\n  {'Descriptions distinctes avant':<52} "
              f"{step2['distinct_descriptions_before']:>10,}")
        print(f"  {'Descriptions distinctes après':<52} "
              f"{step2['distinct_descriptions_after']:>10,}")

    section("TABLEAU 3 — Coût du nettoyage sémantique (déduplication)")

    if step3:
        for pass_name, pass_stats in (
            ("Conclusions", step3["conclusion_pass"]),
            ("Descriptions", step3["description_pass"]),
        ):
            print(f"\n  {pass_name}")
            print(f"    {'Lignes à traiter':<46} {pass_stats['rows']:>10,}")
            print(f"    {'Valeurs distinctes (= appels nécessaires)':<46} "
                  f"{pass_stats['unique_values']:>10,}")
            print(f"    {'Appels évités par déduplication':<46} "
                  f"{pass_stats['calls_saved_by_dedup']:>10,}")

            # Measured against the naive approach - one call per row - not against
            # the calls this particular run happened to make.
            if pass_stats["rows"]:
                saved = 100 * pass_stats["calls_saved_by_dedup"] / pass_stats["rows"]
                print(f"    {'Réduction du coût de traitement':<46} {saved:>9.0f} %")

            if pass_stats.get("aborted"):
                print("    (passe interrompue : Ollama indisponible lors de ce run)")

        consolidation = step3.get("consolidation")
        if consolidation and consolidation["distinct_before"]:
            print(f"\n  Normalisation des conclusions : "
                  f"{consolidation['distinct_before']:,} formulations distinctes -> "
                  f"{consolidation['distinct_after']:,} "
                  f"({consolidation['reduction_pct']}% fusionnées)")

    section("TABLEAU 4 — Comparaison des modèles (étude d'ablation)")

    ablation = load("step4_ablation.json")

    if ablation:
        table = pd.DataFrame(ablation).rename(
            columns={
                "target": "Cible", "variant": "Variante", "model": "Modèle",
                "accuracy": "Accuracy", "f1_macro": "F1 macro",
                "f1_weighted": "F1 pondéré",
            }
        )
        for target in table["Cible"].unique():
            print(f"\n  --- {config.TARGETS.get(target, target)} ---")
            print(table[table["Cible"] == target].drop(columns="Cible").to_string(index=False))

        csv_path = config.METRICS / "tableau_comparaison.csv"
        table.to_csv(csv_path, index=False, encoding="utf-8-sig")
        print(f"\n  CSV pour Word : {csv_path}")

        print("\n  Apport de chaque étape (meilleur modèle, en F1 macro) :")
        for target in table["Cible"].unique():
            best_per_variant = (
                table[table["Cible"] == target].groupby("Variante")["F1 macro"].max().to_dict()
            )
            print(f"\n    {config.TARGETS.get(target, target)}")
            if "raw" in best_per_variant and "rules" in best_per_variant:
                print(f"      nettoyage par règles  : "
                      f"{best_per_variant['rules'] - best_per_variant['raw']:+.3f}")
            if "rules" in best_per_variant and "rules+llm" in best_per_variant:
                print(f"      nettoyage sémantique  : "
                      f"{best_per_variant['rules+llm'] - best_per_variant['rules']:+.3f}")

    section("ANALYSE DES ERREURS")

    for target in config.TARGETS:
        best = load(f"step4_best_{target}.json")
        if not best:
            continue

        print(f"\n  --- {best['target_label']} ---")
        print(f"  Modèle retenu : {best['best']['model']} "
              f"sur la variante « {best['best']['variant']} »")
        print(f"  {best['classes']} classes, {best['rows']:,} lignes, "
              f"F1 macro {best['best']['f1_macro']}\n")

        for confusion in best["top_confusions"][:6]:
            print(f"    {confusion['count']:>4}x  « {confusion['true'][:34]:<34} »"
                  f"  ->  « {confusion['predicted'][:34]} »")

    section("FIGURES DISPONIBLES")

    for figure in sorted(config.FIGURES.glob("*.png")):
        print(f"  {figure.name}")


if __name__ == "__main__":
    main()
