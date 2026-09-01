# Claim classification service

Serves the complaint-category model built during the first month of the internship to the
Spring Boot backend.

## Run

```bash
cd ml-service
python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

Then start the backend with the model enabled:

```bash
ML_ENABLED=true ./mvnw spring-boot:run
```

## Plugging in your own model

Export the trained pipeline (vectoriser + classifier) with `joblib` and save it as
`model/claim_classifier.joblib`:

```python
import joblib
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline

pipeline = make_pipeline(
    TfidfVectorizer(ngram_range=(1, 2), min_df=2, sublinear_tf=True),
    LogisticRegression(max_iter=1000, class_weight="balanced"),
)
pipeline.fit(texts, labels)          # labels must be the ClaimType names
joblib.dump(pipeline, "model/claim_classifier.joblib")
```

The label set must match `ma.cdg.claims.domain.ClaimType` on the backend:
`DEPOSIT_CONSIGNATION`, `PENSION_RETIREMENT`, `ACCOUNT_MANAGEMENT`, `PAYMENT_TRANSFER`,
`FEES_CHARGES`, `DOCUMENT_REQUEST`, `DELAY`, `SERVICE_QUALITY`, `TECHNICAL_ISSUE`, `OTHER`.
Any label the service does not recognise is reported as `OTHER`.

## Endpoints

| Method | Path       | Purpose                                    |
|--------|------------|--------------------------------------------|
| GET    | `/health`  | liveness, and whether a model is loaded    |
| POST   | `/predict` | `{subject, description}` → category + score |

Without a model file the service answers from the same keyword rules the backend uses, so
the whole chain is testable before the model is exported.
