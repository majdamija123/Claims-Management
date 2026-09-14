"""
Scoring, figures and error analysis.

Accuracy alone is misleading on this dataset: the classes are heavily unbalanced,
so a model that always answers with the most common conclusion already looks
respectable. The macro F1 is the honest number - it averages the per-class score
without weighting by frequency, so ignoring the rare classes costs exactly as much
as it should.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib

# Agg lets the pipeline scripts write figures with no display attached, which is
# what they need. Claimed only when nothing else has already chosen a backend:
# a notebook selects its own with %matplotlib inline, and overriding that here -
# this module is imported indirectly by every pipeline step - would silently
# stop every figure in the notebook from rendering.
if "matplotlib.pyplot" not in sys.modules:
    matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)

GREEN_DARK = "#1B5E3A"
GREEN_MED = "#2E7D4F"
GREY_DARK = "#3A3A3A"


def score(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    return {
        "accuracy": round(float(accuracy_score(y_true, y_pred)), 4),
        "f1_macro": round(float(f1_score(y_true, y_pred, average="macro", zero_division=0)), 4),
        "f1_weighted": round(
            float(f1_score(y_true, y_pred, average="weighted", zero_division=0)), 4
        ),
    }


def save_json(payload: dict | list, path: Path) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def save_classification_report(
    y_true: np.ndarray, y_pred: np.ndarray, label_names: list[str], path: Path
) -> None:
    present = sorted(set(y_true.tolist()) | set(y_pred.tolist()))
    text = classification_report(
        y_true,
        y_pred,
        labels=present,
        target_names=[label_names[i] for i in present],
        zero_division=0,
    )
    path.write_text(text, encoding="utf-8")


def plot_loss_curves(
    train_history: list[float], val_history: list[float], path: Path, title: str
) -> None:
    plt.figure(figsize=(8, 4.5), dpi=150)
    plt.plot(train_history, label="Entraînement", color=GREEN_MED, linewidth=2)
    plt.plot(val_history, label="Validation", color=GREEN_DARK, linewidth=2, linestyle="--")
    plt.xlabel("Époque")
    plt.ylabel("Perte")
    plt.title(title, fontweight="bold", color=GREY_DARK)
    plt.legend(frameon=False)
    plt.grid(alpha=0.25)
    plt.tight_layout()
    plt.savefig(path, facecolor="white")
    plt.close()


def plot_confusion_matrix(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    label_names: list[str],
    path: Path,
    top_n: int = 12,
) -> None:
    """
    Confusion matrix over the most frequent classes.

    Restricted to the top classes on purpose: a 200x200 grid is unreadable on a
    printed page, and the interesting confusions are between the categories that
    actually carry volume.
    """
    counts = np.bincount(y_true, minlength=len(label_names))
    top = np.argsort(counts)[::-1][:top_n]
    top = [int(i) for i in top if counts[i] > 0]

    matrix = confusion_matrix(y_true, y_pred, labels=top)
    normalised = matrix / np.clip(matrix.sum(axis=1, keepdims=True), 1, None)

    names = [
        (label_names[i][:32] + "…") if len(label_names[i]) > 33 else label_names[i]
        for i in top
    ]

    fig, ax = plt.subplots(figsize=(10, 8.5), dpi=150)
    image = ax.imshow(normalised, cmap="Greens", vmin=0, vmax=1)

    ax.set_xticks(range(len(top)))
    ax.set_yticks(range(len(top)))
    ax.set_xticklabels(names, rotation=45, ha="right", fontsize=7.5)
    ax.set_yticklabels(names, fontsize=7.5)
    ax.set_xlabel("Classe prédite", fontweight="bold")
    ax.set_ylabel("Classe réelle", fontweight="bold")
    ax.set_title(
        f"Matrice de confusion — {len(top)} classes les plus fréquentes",
        fontweight="bold",
        color=GREY_DARK,
        pad=14,
    )

    for row in range(len(top)):
        for column in range(len(top)):
            value = normalised[row, column]
            if value > 0.005:
                ax.text(
                    column,
                    row,
                    f"{value:.0%}",
                    ha="center",
                    va="center",
                    fontsize=6.5,
                    color="white" if value > 0.5 else GREY_DARK,
                )

    fig.colorbar(image, ax=ax, shrink=0.75, label="Proportion de la classe réelle")
    plt.tight_layout()
    plt.savefig(path, facecolor="white")
    plt.close()


def plot_ablation(results: list[dict], path: Path) -> None:
    """The headline figure: what each cleaning stage bought, per model."""
    variants = sorted({row["variant"] for row in results}, key=lambda v: len(v))
    models = sorted({row["model"] for row in results})

    x = np.arange(len(variants))
    width = 0.36
    ceiling = max((row["f1_macro"] for row in results), default=1.0)

    plt.figure(figsize=(9, 5), dpi=150)

    for index, model_name in enumerate(models):
        scores = [
            next(
                (
                    row["f1_macro"]
                    for row in results
                    if row["variant"] == variant and row["model"] == model_name
                ),
                0,
            )
            for variant in variants
        ]
        plt.bar(
            x + (index - 0.5) * width,
            scores,
            width,
            label=model_name,
            color=[GREEN_MED, GREEN_DARK][index % 2],
        )
        for position, value in zip(x + (index - 0.5) * width, scores):
            plt.text(
                position,
                value + ceiling * 0.02,
                f"{value:.3f}",
                ha="center",
                fontsize=8,
            )

    # Headroom so the value labels stay inside the axes.
    plt.ylim(0, ceiling * 1.15)
    plt.xticks(x, variants)
    plt.ylabel("F1-score macro")
    plt.xlabel("Variante de nettoyage")
    plt.title(
        "Apport mesuré de chaque étape de nettoyage",
        fontweight="bold",
        color=GREY_DARK,
    )
    plt.legend(frameon=False)
    plt.grid(axis="y", alpha=0.25)
    plt.tight_layout()
    plt.savefig(path, facecolor="white")
    plt.close()


def top_confusions(
    y_true: np.ndarray, y_pred: np.ndarray, label_names: list[str], top_n: int = 10
) -> list[dict]:
    """
    The pairs the model mixes up most.

    This is what the error analysis section of the report is made of: not "the model
    made 137 mistakes" but "it confuses 'Information fournie au client' with
    'Client orienté vers l'agence' 23 times, and those two outcomes genuinely
    overlap in the source data".
    """
    pairs: dict[tuple[int, int], int] = {}

    for true_label, predicted in zip(y_true.tolist(), y_pred.tolist()):
        if true_label != predicted:
            pairs[(true_label, predicted)] = pairs.get((true_label, predicted), 0) + 1

    ordered = sorted(pairs.items(), key=lambda item: item[1], reverse=True)[:top_n]

    return [
        {
            "true": label_names[true_label],
            "predicted": label_names[predicted],
            "count": count,
        }
        for (true_label, predicted), count in ordered
    ]
