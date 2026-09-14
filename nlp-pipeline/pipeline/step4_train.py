"""
Step 4 - the ablation, on two prediction tasks.

Three text variants times two models times two targets, all trained and scored
identically. The point is not the individual numbers but the differences:

- raw -> rules tells you what the regular expressions were worth.
- rules -> rules+llm tells you what the language model was worth.
- MLP vs logistic regression tells you whether the network earned its complexity.

Without this, "we cleaned the data with an LLM" is an assertion. With it, it is a
measurement, and the size of the effect is visible.

The two tasks, and why they matter to the platform of the second part:

  REQUEST_CATEGORY  the category the application suggests when a complaint is
                    registered, so the qualification agent starts from a proposal
                    rather than a blank field.
  ROUTING_LEVEL     which tier should handle it - the decision the BPMN gateway
                    makes immediately after qualification.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import numpy as np
import pandas as pd
from sklearn.model_selection import GroupShuffleSplit
from sklearn.preprocessing import LabelEncoder

import config
from src import evaluation
from src.features import build_model_text, encode
from src.models import train_logreg, train_mlp


def grouped_split(groups: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """
    Split so that identical complaints never straddle the two sides.

    This corpus repeats itself: the same request, worded identically, appears
    several times. A plain random split puts some of those copies in training and
    the rest in test, and the model is then scored on texts it has already seen -
    a score that looks excellent and means nothing. Grouping by the text itself
    removes that leak, which is why the numbers here are lower, and honest.
    """
    splitter = GroupShuffleSplit(
        n_splits=1, test_size=config.TEST_SIZE, random_state=config.RANDOM_SEED
    )
    train_index, test_index = next(splitter.split(np.zeros(len(y)), y, groups))
    return train_index, test_index


def stratified_subsample(
    df: pd.DataFrame, target: str, fraction: float, seed: int, min_per_class: int
) -> pd.DataFrame:
    """
    Take a fraction of the cleaned corpus, keeping each class's share intact
    - with a floor, so a class that only just cleared MIN_SAMPLES_PER_CLASS
    does not get sampled straight back under it.

    A plain per-class `frac=fraction` draw is what "20% of the corpus" means,
    but taken literally it reintroduces the problem the pre-filter just
    solved: a class with exactly 15 rows keeps only 3 at 20%, well under the
    floor that made it viable in the first place, and disappears from the
    task entirely. Every class entering this function already has at least
    `min_per_class` rows (the caller filtered smaller ones out), so flooring
    the draw at that count - instead of at the fraction - keeps every
    surviving class actually usable, while still shrinking every large class
    down to its 20% share.
    """
    parts = []
    for _, group in df.groupby(target):
        keep = max(round(len(group) * fraction), min_per_class)
        parts.append(group.sample(n=min(keep, len(group)), random_state=seed))
    return pd.concat(parts).reset_index(drop=True)


def run_task(df: pd.DataFrame, target: str, target_label: str) -> list[dict]:
    print(f"\n{'#' * 70}\n# TASK: {target}  —  {target_label}\n{'#' * 70}")

    task_df = df[df[target].notna()].copy()
    task_df[target] = task_df[target].astype(str).str.strip()
    task_df = task_df[task_df[target] != ""]

    # Class filtering happens BEFORE subsampling, on the full cleaned corpus:
    # filtering after would let the 20% draw itself starve a borderline class
    # below the floor by chance, making the sample size change which classes
    # exist rather than just how many examples of each are used.
    counts = task_df[target].value_counts()
    keepable = counts[counts >= config.MIN_SAMPLES_PER_CLASS].index
    dropped = int((~task_df[target].isin(keepable)).sum())
    task_df = task_df[task_df[target].isin(keepable)].reset_index(drop=True)

    print(f"Full cleaned corpus for this task: {len(task_df):,} rows, "
          f"{len(keepable)} classes "
          f"(dropped {dropped:,} rows in classes under {config.MIN_SAMPLES_PER_CLASS})")

    if config.TRAIN_SAMPLE_FRACTION < 1.0:
        before = len(task_df)
        before_classes = task_df[target].nunique()
        task_df = stratified_subsample(
            task_df, target, config.TRAIN_SAMPLE_FRACTION, config.RANDOM_SEED,
            config.MIN_SAMPLES_PER_CLASS,
        )
        print(f"Training sample ({config.TRAIN_SAMPLE_FRACTION:.0%} of the cleaned corpus, "
              f"floored at {config.MIN_SAMPLES_PER_CLASS} rows/class): "
              f"{before:,} -> {len(task_df):,} rows, "
              f"{before_classes} -> {task_df[target].nunique()} classes")

    encoder = LabelEncoder()
    y = encoder.fit_transform(task_df[target])
    label_names = list(encoder.classes_)
    num_classes = len(label_names)

    # Identical texts stay on the same side of the split.
    groups = task_df["DESCRIPTION_RULES"].fillna("").str.strip().values

    results: list[dict] = []
    best = {"f1_macro": -1.0}

    for variant, column in config.VARIANTS.items():
        if column not in task_df.columns:
            print(f"\n! variant '{variant}' skipped: column {column} missing")
            continue

        print(f"\n--- variant: {variant} ({column}) ---")

        texts = build_model_text(task_df, column).tolist()
        X = encode(texts, config.EMBEDDING_MODEL, config.EMBEDDING_BATCH_SIZE, config.CACHE)

        train_index, test_index = grouped_split(groups, y)
        X_train, X_test = X[train_index], X[test_index]
        y_train, y_test = y[train_index], y[test_index]

        print(f"  train={len(X_train):,}  test={len(X_test):,}  classes={num_classes}")

        # --- neural network ------------------------------------------------
        predictions, train_history, val_history = train_mlp(
            X_train, y_train, X_test, y_test, num_classes,
            epochs=config.EPOCHS,
            batch_size=config.BATCH_SIZE,
            learning_rate=config.LEARNING_RATE,
            weight_decay=config.WEIGHT_DECAY,
            patience=config.EARLY_STOPPING_PATIENCE,
            checkpoint=config.MODELS / f"mlp_{target}_{variant.replace('+', '_')}.pt",
            seed=config.RANDOM_SEED,
        )
        mlp_scores = evaluation.score(y_test, predictions)
        results.append({"target": target, "variant": variant,
                        "model": "MLP (PyTorch)", **mlp_scores})
        print(f"  MLP                   -> {mlp_scores}")

        evaluation.plot_loss_curves(
            train_history, val_history,
            config.FIGURES / f"loss_{target}_{variant.replace('+', '_')}.png",
            f"Perte — {target_label}, variante « {variant} »",
        )

        if mlp_scores["f1_macro"] > best["f1_macro"]:
            best = {**mlp_scores, "variant": variant, "model": "MLP (PyTorch)",
                    "y_test": y_test, "predictions": predictions}

        # --- baseline ------------------------------------------------------
        baseline = train_logreg(X_train, y_train, X_test, config.RANDOM_SEED)
        baseline_scores = evaluation.score(y_test, baseline)
        results.append({"target": target, "variant": variant,
                        "model": "Régression logistique", **baseline_scores})
        print(f"  Régression logistique -> {baseline_scores}")

        if baseline_scores["f1_macro"] > best["f1_macro"]:
            best = {**baseline_scores, "variant": variant, "model": "Régression logistique",
                    "y_test": y_test, "predictions": baseline}

    # --- best model for this task ------------------------------------------
    y_test = best.pop("y_test")
    predictions = best.pop("predictions")

    evaluation.plot_confusion_matrix(
        y_test, predictions, label_names,
        config.FIGURES / f"confusion_{target}.png",
        top_n=min(12, num_classes),
    )
    evaluation.save_classification_report(
        y_test, predictions, label_names,
        config.METRICS / f"classification_report_{target}.txt",
    )
    evaluation.save_json(
        {
            "target": target,
            "target_label": target_label,
            "best": best,
            "classes": num_classes,
            "rows": int(len(task_df)),
            "top_confusions": evaluation.top_confusions(y_test, predictions, label_names),
        },
        config.METRICS / f"step4_best_{target}.json",
    )
    np.save(config.MODELS / f"label_classes_{target}.npy", encoder.classes_)

    print(f"\n  Best for {target}: {best['model']} on '{best['variant']}' "
          f"— F1 macro {best['f1_macro']}")

    return results


def main() -> None:
    df = pd.read_parquet(config.LLM_PARQUET)
    print(f"Loaded {len(df):,} complaints")

    all_results: list[dict] = []
    for target, target_label in config.TARGETS.items():
        all_results.extend(run_task(df, target, target_label))

    evaluation.save_json(all_results, config.METRICS / "step4_ablation.json")

    for target in config.TARGETS:
        subset = [row for row in all_results if row["target"] == target]
        if subset:
            evaluation.plot_ablation(subset, config.FIGURES / f"ablation_{target}.png")

    print(f"\n{'=' * 70}\nFigures in {config.FIGURES}\nMetrics in {config.METRICS}")


if __name__ == "__main__":
    main()
