"""
Turning a complaint into a vector.

A classifier cannot read French. It needs each complaint as a fixed-length list of
numbers where distance means similarity of meaning - so that "je n'ai pas reçu ma
pension" and "ma retraite n'est toujours pas versée" land close together even
though they share almost no words. That is what a sentence embedding gives.

The model choice is not neutral. all-MiniLM-L6-v2, the default in most tutorials,
was trained on English; fed French it still returns 384 numbers, but they encode
surface form more than meaning. The multilingual sibling used here was trained on
parallel data across fifty languages including French, at the same 384 dimensions,
so it is a drop-in replacement that actually reads the text.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

import numpy as np
import pandas as pd


def build_model_text(
    df: pd.DataFrame, description_column: str
) -> pd.Series:
    """
    Assemble what the embedder sees.

    The complaint alone is not everything the agent knows when qualifying it: the
    request category and the kind of customer are on the screen too, and they carry
    real signal (a pension question from a beneficiary rarely ends the same way as
    the same words from an employer). They are appended as tagged fields so the
    embedding model treats them as part of the sentence.
    """
    parts = df[description_column].fillna("").astype(str)

    if "REQUEST_CATEGORY" in df.columns:
        parts = parts + " [CATEGORY] " + df["REQUEST_CATEGORY"].fillna("").astype(str)

    if "NATURECLIENT" in df.columns:
        parts = parts + " [CLIENT] " + df["NATURECLIENT"].fillna("").astype(str)

    return parts.str.strip()


def encode(
    texts: list[str],
    model_name: str,
    batch_size: int,
    cache_dir: Path,
) -> np.ndarray:
    """
    Embed a list of texts, caching the result.

    Encoding a few thousand sentences takes minutes on a laptop CPU, and the
    ablation re-encodes the same variants repeatedly. The cache key is the model
    name plus a hash of the texts, so a changed variant recomputes and an unchanged
    one loads from disk.
    """
    fingerprint = hashlib.sha256(
        (model_name + "||" + "\n".join(texts)).encode("utf-8")
    ).hexdigest()[:16]

    cache_file = cache_dir / f"emb_{fingerprint}.npy"

    if cache_file.exists():
        print(f"  embeddings loaded from cache ({cache_file.name})")
        return np.load(cache_file)

    from sentence_transformers import SentenceTransformer

    model = SentenceTransformer(model_name)
    embeddings = model.encode(
        texts,
        batch_size=batch_size,
        show_progress_bar=True,
        normalize_embeddings=True,
    )

    np.save(cache_file, embeddings)
    return embeddings
