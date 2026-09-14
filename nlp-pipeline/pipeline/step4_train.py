"""
Step 4 - the ablation.

Three text variants times two models, trained and scored identically. The point is
not the six numbers themselves but the differences between them:

- raw -> rules tells you what the regular expressions were worth.
- rules -> rules+llm tells you what the language model was worth.
- MLP vs logistic regression tells you whether the network earned its complexity.

Without this, "we cleaned the data with an LLM" is an assertion. With it, it is a
measurement, and the jury can see the size of the effect.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder

import config
from src import evaluation
from src.features import build_model_text, encode
from src.models import train_logreg, train_mlp


def main() -> None:
    df = pd.read_parquet(config.LLM_PARQUET)
    print(f"Loaded {len(df):,} rows")

    # The target is the LLM-normalised conclusion in every variant: the ablation
    # isolates the effect of cleaning the *input*, so the labels have to stay fixed.
    # Changing both at once would make the comparison meaningless.
    df = df[df["CONCLUSION_LLM"].notna()].copy()
    df["TARGET"] = df["CONCLUSION_LLM"].astype(str).str.strip()
    df = df[df["TARGET"] != ""]

    counts = df["TARGET"].value_counts()
    keepable = counts[counts >= config.MIN_SAMPLES_PER_CLASS].index
    dropped_rows = int((~df["TARGET"].isin(keepable)).sum())
    df = df[df["TARGET"].isin(keepable)].reset_index(drop=True)

    print(
        f"Classes with at least {config.MIN_SAMPLES_PER_CLASS} examples: "
        f"{len(keepable):,} (dropped {dropped_rows:,} rows in rarer classes)"
    )

    encoder = LabelEncoder()
    y = encoder.fit_transform(df["TARGET"])
    label_names = list(encoder.classes_)
    num_classes = len(label_names)

    results: list[dict] = []
    best = {"f1_macro": -1.0}

    for variant, column in config.VARIANTS.items():
        if column not in df.columns:
            print(f"\n! variant '{variant}' skipped: column {column} is missing")
            continue

        print(f"\n{'=' * 62}\nVariant: {variant}  (column {column})\n{'=' * 62}")

        texts = build_model_text(df, column).tolist()
        X = encode(
            texts,
            config.EMBEDDING_MODEL,
            config.EMBEDDING_BATCH_SIZE,
            config.CACHE,
        )

        # Stratified: with this many rare classes, an unstratified split routinely
        # puts every example of a class on one side and none on the other.
        X_train, X_test, y_train, y_test = train_test_split(
            X,
            y,
            test_size=config.TEST_SIZE,
            random_state=config.RANDOM_SEED,
            stratify=y,
        )

        print(f"  train={len(X_train):,}  test={len(X_test):,}  classes={num_classes}")

        # --- neural network ------------------------------------------------
        print("  MLP (PyTorch)")
        predictions, train_history, val_history = train_mlp(
            X_train,
            y_train,
            X_test,
            y_test,
            num_classes,
            epochs=config.EPOCHS,
            batch_size=config.BATCH_SIZE,
            learning_rate=config.LEARNING_RATE,
            weight_decay=config.WEIGHT_DECAY,
            patience=config.EARLY_STOPPING_PATIENCE,
            checkpoint=config.MODELS / f"mlp_{variant.replace('+', '_')}.pt",
            seed=config.RANDOM_SEED,
        )

        mlp_scores = evaluation.score(y_test, predictions)
        results.append({"variant": variant, "model": "MLP (PyTorch)", **mlp_scores})
        print(f"    -> {mlp_scores}")

        evaluation.plot_loss_curves(
            train_history,
            val_history,
            config.FIGURES / f"loss_{variant.replace('+', '_')}.png",
            f"Courbe de perte — variante « {variant} »",
        )

        if mlp_scores["f1_macro"] > best["f1_macro"]:
            best = {
                **mlp_scores,
                "variant": variant,
                "model": "MLP (PyTorch)",
                "y_test": y_test,
                "predictions": predictions,
            }

        # --- baseline ------------------------------------------------------
        print("  Logistic regression")
        baseline_predictions = train_logreg(X_train, y_train, X_test, config.RANDOM_SEED)
        baseline_scores = evaluation.score(y_test, baseline_predictions)
        results.append(
            {"variant": variant, "model": "Régression logistique", **baseline_scores}
        )
        print(f"    -> {baseline_scores}")

        if baseline_scores["f1_macro"] > best["f1_macro"]:
            best = {
                **baseline_scores,
                "variant": variant,
                "model": "Régression logistique",
                "y_test": y_test,
                "predictions": baseline_predictions,
            }

    # ------------------------------------------------------------------ output
    evaluation.save_json(results, config.METRICS / "step4_ablation.json")
    evaluation.plot_ablation(results, config.FIGURES / "ablation.png")

    y_test = best.pop("y_test")
    predictions = best.pop("predictions")

    evaluation.plot_confusion_matrix(
        y_test, predictions, label_names, config.FIGURES / "confusion_matrix.png"
    )
    evaluation.save_classification_report(
        y_test, predictions, label_names, config.METRICS / "classification_report.txt"
    )
    evaluation.save_json(
        {
            "best": best,
            "classes": num_classes,
            "rows": int(len(df)),
            "top_confusions": evaluation.top_confusions(y_test, predictions, label_names),
        },
        config.METRICS / "step4_best.json",
    )
    np.save(config.MODELS / "label_classes.npy", encoder.classes_)

    print(f"\n{'=' * 62}")
    print(f"Best: {best['model']} on '{best['variant']}' — F1 macro {best['f1_macro']}")
    print(f"Figures in {config.FIGURES}, metrics in {config.METRICS}")


if __name__ == "__main__":
    main()
