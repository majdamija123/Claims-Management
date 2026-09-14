"""
The two classifiers being compared.

A neural network is not automatically better than a linear model. On 384-dimension
embeddings of a few thousand examples it can just as easily overfit and lose to a
logistic regression that trains in two seconds. Running both is what turns "we used
deep learning" into a defensible choice - the report can say which one won and by
how much, instead of assuming.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
from sklearn.linear_model import LogisticRegression
from sklearn.utils.class_weight import compute_class_weight
from torch.utils.data import DataLoader, TensorDataset


class ConclusionMLP(nn.Module):
    """
    384 -> 512 -> 256 -> 128 -> classes.

    Batch normalisation and dropout are there because the dataset is small relative
    to the parameter count: without them the network memorises the training split
    within a handful of epochs.
    """

    def __init__(self, input_dim: int, num_classes: int):
        super().__init__()
        self.network = nn.Sequential(
            nn.Linear(input_dim, 512),
            nn.BatchNorm1d(512),
            nn.ReLU(),
            nn.Dropout(0.4),
            nn.Linear(512, 256),
            nn.BatchNorm1d(256),
            nn.ReLU(),
            nn.Dropout(0.3),
            nn.Linear(256, 128),
            nn.ReLU(),
            nn.Linear(128, num_classes),
        )

    def forward(self, x):
        return self.network(x)


def train_mlp(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_test: np.ndarray,
    y_test: np.ndarray,
    num_classes: int,
    *,
    epochs: int,
    batch_size: int,
    learning_rate: float,
    weight_decay: float,
    patience: int,
    checkpoint: Path,
    seed: int,
) -> tuple[np.ndarray, list[float], list[float]]:
    """
    Train the network and return its predictions on the test split.

    Mini-batches rather than one gradient step per epoch on the whole matrix: batch
    normalisation needs batch statistics to mean anything, and the noise between
    batches is part of what keeps the network from settling into the nearest bad
    minimum.

    Early stopping watches the *validation* loss. Watching the training loss - the
    easy mistake - stops only when the model has finished memorising, which is the
    opposite of what is wanted.
    """
    torch.manual_seed(seed)

    train_dataset = TensorDataset(
        torch.tensor(X_train, dtype=torch.float32),
        torch.tensor(y_train, dtype=torch.long),
    )
    loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True, drop_last=True)

    X_test_t = torch.tensor(X_test, dtype=torch.float32)
    y_test_t = torch.tensor(y_test, dtype=torch.long)

    model = ConclusionMLP(X_train.shape[1], num_classes)

    # Rare classes would otherwise be ignored entirely: predicting only the majority
    # class already scores well on accuracy. Weighting the loss by inverse frequency
    # makes a mistake on a rare class cost as much as one on a common class.
    present = np.unique(y_train)
    weights = compute_class_weight("balanced", classes=present, y=y_train)
    weight_vector = np.ones(num_classes, dtype=np.float32)
    weight_vector[present] = weights
    criterion = nn.CrossEntropyLoss(weight=torch.tensor(weight_vector))

    optimizer = torch.optim.AdamW(
        model.parameters(), lr=learning_rate, weight_decay=weight_decay
    )
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
        optimizer, mode="min", factor=0.5, patience=3
    )

    train_history: list[float] = []
    val_history: list[float] = []
    best_loss = float("inf")
    since_improvement = 0

    for epoch in range(1, epochs + 1):
        model.train()
        epoch_loss = 0.0

        for batch_x, batch_y in loader:
            optimizer.zero_grad()
            loss = criterion(model(batch_x), batch_y)
            loss.backward()
            optimizer.step()
            epoch_loss += loss.item()

        train_loss = epoch_loss / max(len(loader), 1)

        model.eval()
        with torch.no_grad():
            val_loss = criterion(model(X_test_t), y_test_t).item()

        train_history.append(train_loss)
        val_history.append(val_loss)
        scheduler.step(val_loss)

        if val_loss < best_loss - 1e-4:
            best_loss = val_loss
            since_improvement = 0
            torch.save(model.state_dict(), checkpoint)
        else:
            since_improvement += 1

        if epoch % 10 == 0 or epoch == 1:
            print(
                f"    epoch {epoch:>3}/{epochs}  train={train_loss:.4f}  val={val_loss:.4f}"
            )

        if since_improvement >= patience:
            print(f"    early stop at epoch {epoch} (best val={best_loss:.4f})")
            break

    model.load_state_dict(torch.load(checkpoint))
    model.eval()
    with torch.no_grad():
        predictions = torch.argmax(model(X_test_t), dim=1).numpy()

    return predictions, train_history, val_history


def train_logreg(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_test: np.ndarray,
    seed: int,
) -> np.ndarray:
    """The baseline. Same class weighting, so the comparison is fair."""
    model = LogisticRegression(
        max_iter=2000,
        class_weight="balanced",
        n_jobs=-1,
        random_state=seed,
    )
    model.fit(X_train, y_train)
    return model.predict(X_test)
