"""
Claim classification service.

Wraps the model trained during the data-analysis phase of the internship and exposes it
to the Spring Boot backend over HTTP. The backend calls POST /predict when
``cdg.ml.enabled=true`` and silently falls back to its own keyword rules if this service
is unavailable, so registering a complaint never depends on it being up.

Run:
    pip install -r requirements.txt
    uvicorn main:app --reload --port 8000
"""

from __future__ import annotations

import logging
import os
import re
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from pydantic import BaseModel, Field

logger = logging.getLogger("ml-service")
logging.basicConfig(level=logging.INFO)

# Must stay in step with ma.cdg.claims.domain.ClaimType on the backend.
CLAIM_TYPES = [
    "DEPOSIT_CONSIGNATION",
    "PENSION_RETIREMENT",
    "ACCOUNT_MANAGEMENT",
    "PAYMENT_TRANSFER",
    "FEES_CHARGES",
    "DOCUMENT_REQUEST",
    "DELAY",
    "SERVICE_QUALITY",
    "TECHNICAL_ISSUE",
    "OTHER",
]

MODEL_PATH = Path(os.getenv("MODEL_PATH", "model/claim_classifier.joblib"))

app = FastAPI(
    title="CDG claim classification",
    version="1.0.0",
    description="Predicts the category of a customer complaint from its wording.",
)


class PredictRequest(BaseModel):
    subject: str | None = Field(default=None, max_length=250)
    description: str | None = Field(default=None, max_length=4000)

    def text(self) -> str:
        return f"{self.subject or ''} {self.description or ''}".strip()


class ScoredType(BaseModel):
    type: str
    confidence: float


class PredictResponse(BaseModel):
    type: str
    confidence: float
    alternatives: list[ScoredType] = []
    source: str


class _Classifier:
    """Loads the trained pipeline once, or falls back to a keyword scorer."""

    def __init__(self) -> None:
        self.pipeline: Any | None = None
        self._load()

    def _load(self) -> None:
        if not MODEL_PATH.exists():
            logger.warning(
                "No model at %s — answering with keyword rules. "
                "Drop your trained joblib pipeline there to use the real model.",
                MODEL_PATH,
            )
            return
        try:
            import joblib  # imported lazily so the service runs without scikit-learn

            self.pipeline = joblib.load(MODEL_PATH)
            logger.info("Loaded classification model from %s", MODEL_PATH)
        except Exception:  # noqa: BLE001 - never let a bad artefact stop the service
            logger.exception("Could not load %s — falling back to keyword rules", MODEL_PATH)

    def predict(self, text: str) -> PredictResponse:
        if self.pipeline is not None:
            try:
                return self._predict_with_model(text)
            except Exception:  # noqa: BLE001
                logger.exception("Model inference failed — falling back to keyword rules")
        return _predict_with_rules(text)

    def _predict_with_model(self, text: str) -> PredictResponse:
        labels = list(getattr(self.pipeline, "classes_", CLAIM_TYPES))

        if hasattr(self.pipeline, "predict_proba"):
            scores = self.pipeline.predict_proba([text])[0]
            ranked = sorted(zip(labels, scores), key=lambda pair: pair[1], reverse=True)
        else:
            ranked = [(self.pipeline.predict([text])[0], 1.0)]

        best_label, best_score = ranked[0]
        return PredictResponse(
            type=_normalise(str(best_label)),
            confidence=round(float(best_score), 3),
            alternatives=[
                ScoredType(type=_normalise(str(label)), confidence=round(float(score), 3))
                for label, score in ranked[1:4]
            ],
            source="MODEL",
        )


# French and English cues, mirroring the backend's own fallback so the two agree.
KEYWORDS: dict[str, list[str]] = {
    "DEPOSIT_CONSIGNATION": ["consignation", "consigne", "depot", "dépôt", "restitution", "caution"],
    "PENSION_RETIREMENT": ["retraite", "pension", "rcar", "cnra", "pensionne"],
    "ACCOUNT_MANAGEMENT": ["compte", "rib", "releve", "relevé", "cloture", "ouverture"],
    "PAYMENT_TRANSFER": ["virement", "paiement", "prelevement", "prélèvement", "cheque", "chèque"],
    "FEES_CHARGES": ["frais", "commission", "agios", "facturation"],
    "DOCUMENT_REQUEST": ["attestation", "document", "justificatif", "certificat", "duplicata"],
    "DELAY": ["retard", "delai", "délai", "attente", "toujours pas"],
    "SERVICE_QUALITY": ["accueil", "comportement", "impoli", "guichet", "mauvais service"],
    "TECHNICAL_ISSUE": ["site", "application", "erreur", "bug", "connexion", "mot de passe"],
}


def _normalise(label: str) -> str:
    upper = re.sub(r"[^A-Z_]", "_", label.strip().upper())
    return upper if upper in CLAIM_TYPES else "OTHER"


def _predict_with_rules(text: str) -> PredictResponse:
    lowered = text.lower()
    scores = {
        claim_type: sum(1 for word in words if word in lowered)
        for claim_type, words in KEYWORDS.items()
    }
    scores = {claim_type: score for claim_type, score in scores.items() if score > 0}

    if not scores:
        return PredictResponse(type="OTHER", confidence=0.2, alternatives=[], source="RULES")

    total = sum(scores.values())
    ranked = sorted(scores.items(), key=lambda pair: pair[1], reverse=True)
    return PredictResponse(
        type=ranked[0][0],
        confidence=round(ranked[0][1] / total, 2),
        alternatives=[
            ScoredType(type=label, confidence=round(score / total, 2))
            for label, score in ranked[1:4]
        ],
        source="RULES",
    )


classifier = _Classifier()


@app.get("/health")
def health() -> dict[str, object]:
    return {"status": "UP", "modelLoaded": classifier.pipeline is not None}


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    return classifier.predict(request.text())
