"""
Central configuration for the complaint cleaning and classification pipeline.

Every knob lives here so an experiment can be replayed exactly: the report cites
numbers, and those numbers have to come from a run somebody can reproduce.
"""

from pathlib import Path

# --------------------------------------------------------------------------- paths

ROOT = Path(__file__).parent
DATA = ROOT / "data"
OUTPUTS = ROOT / "outputs"
CACHE = ROOT / ".cache"

RAW_PARQUET = DATA / "reclamations_complet.parquet"
PREPARED_PARQUET = OUTPUTS / "01_prepared.parquet"
RULES_PARQUET = OUTPUTS / "02_rules_cleaned.parquet"
LLM_PARQUET = OUTPUTS / "03_llm_cleaned.parquet"

FIGURES = OUTPUTS / "figures"
METRICS = OUTPUTS / "metrics"
MODELS = OUTPUTS / "models"

for directory in (DATA, OUTPUTS, CACHE, FIGURES, METRICS, MODELS):
    directory.mkdir(parents=True, exist_ok=True)

# --------------------------------------------------------------------------- sampling

# A quarter of the corpus. Drawn stratified on the target, not with head(n):
# the export is ordered by date, so the first rows over-represent whatever
# categories were common that month.
SAMPLE_FRACTION = 0.25
RANDOM_SEED = 42

# A class the model sees three times cannot be learned, and it inflates the macro
# F1 denominator with noise. Below this count the class is dropped.
MIN_SAMPLES_PER_CLASS = 10

# --------------------------------------------------------------------------- LLM

# Run locally through Ollama: the complaints are customer data and must not leave
# the machine. That constraint is what rules out a hosted API here.
LLM_MODEL = "qwen3:8b"
LLM_TEMPERATURE = 0.0

# LLM calls are the slowest step by far. Descriptions are nearly all distinct, so
# cleaning every one of them costs hours; this cap keeps a demonstration run short.
# Set to None to clean the whole sample.
LLM_DESCRIPTION_LIMIT = 1500

# --------------------------------------------------------------------------- features

# Multilingual, unlike all-MiniLM-L6-v2 which was trained on English only. The
# complaints are in French, so the English model embeds them roughly by shape
# rather than by meaning. Same 384 dimensions, so nothing downstream changes.
EMBEDDING_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
EMBEDDING_DIM = 384
EMBEDDING_BATCH_SIZE = 64

# --------------------------------------------------------------------------- training

TEST_SIZE = 0.2
EPOCHS = 60
BATCH_SIZE = 64
LEARNING_RATE = 2e-4
WEIGHT_DECAY = 1e-4
EARLY_STOPPING_PATIENCE = 8

# The three text variants compared in the ablation. Each one adds a cleaning stage
# on top of the previous, so the difference in score is the value that stage added.
VARIANTS = {
    "raw": "DESCRIPTION",
    "rules": "DESCRIPTION_RULES",
    "rules+llm": "DESCRIPTION_LLM",
}
