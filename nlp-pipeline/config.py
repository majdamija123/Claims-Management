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

# The export as the department produces it: a binary .xls workbook.
RAW_EXCEL = DATA / "RECLAMATION_CLIENT2.xls"

# Same content in a columnar format. Reading the workbook takes about a minute and
# has to reparse the whole file every time; the parquet loads in well under a
# second and keeps its column types, so every later step starts from it.
RAW_PARQUET = DATA / "reclamations_complet.parquet"

PREPARED_PARQUET = OUTPUTS / "01_prepared.parquet"
RULES_PARQUET = OUTPUTS / "02_rules_cleaned.parquet"
LLM_PARQUET = OUTPUTS / "03_llm_cleaned.parquet"

FIGURES = OUTPUTS / "figures"
METRICS = OUTPUTS / "metrics"
MODELS = OUTPUTS / "models"
DIAGNOSTIC = OUTPUTS / "diagnostic"

for directory in (DATA, OUTPUTS, CACHE, FIGURES, METRICS, MODELS, DIAGNOSTIC):
    directory.mkdir(parents=True, exist_ok=True)

# --------------------------------------------------------------------------- corpus

# Four fifths of the export are self-service acts, not complaints: an attestation
# the customer downloaded from the portal is recorded in the same table, with the
# same columns, and the DESCRIPTION field holds a fixed sentence rather than
# anything a customer wrote. Training on them would teach the model to recognise
# one template repeated sixteen thousand times.
AUTOMATED_ACT_PATTERNS = [
    "Attestation livrée depuis",
    "SANS IDENTIFIANT",
    # Found running step0_diagnostic.py on the full 510 108-row export: agent
    # log notes rather than customer complaints, repeated hundreds of times.
    "communication coupée",
    "conversation interrompue",
    "centre d'appel cnra",  # catches "N° centre d'appel cnra..." and "numéro centre d'appel cnra est..."
]

# A description shorter than this is a contact block or a stray fragment, not a
# request the model can classify.
MIN_DESCRIPTION_CHARS = 25

RANDOM_SEED = 42

# A class seen a handful of times cannot be learned and only adds noise to the
# macro F1. Below this count the class is dropped.
MIN_SAMPLES_PER_CLASS = 15

# --------------------------------------------------------------------------- training sample
#
# Cleaning (steps 1-3) runs on the FULL corpus: every real complaint in the
# export is filtered, regex-cleaned and passed through the LLM. The volumetry
# quoted in the report - how many complaints survive filtering, how much the
# LLM merged - is measured on all of it.
#
# Training (step 4) is different: encoding tens of thousands of complaints and
# running two models across three variants for two targets multiplies runtime
# for no benefit to what the ablation is actually measuring - the *relative*
# gap between raw/rules/rules+llm, and between the two models. That comparison
# holds on a representative subset as well as on the whole corpus. A stratified
# 20% sample keeps a full run to a manageable length without changing which
# variant or which model wins.
#
# The diagnostic and the filtering funnel (step 0 and step 1) measure the WHOLE
# export: the volumetry the report quotes - 510 108 rows, how many are
# self-service acts, how many real complaints survive - is counted on all of it.
#
# What follows the funnel is drawn down to a tenth. The binding cost is the
# LLM pass: one local Qwen3 call per distinct description, on a corpus this size,
# runs for days. A stratified tenth keeps every category represented and brings
# a full run into the time actually available before the defence.
#
# The draw is stratified by category and floored at MIN_SAMPLES_PER_CLASS rows
# per class: a plain per-class 10% draw pushes any class that only just cleared
# the floor straight back under it - measured on the pilot extract, a flat draw
# took REQUEST_CATEGORY from 20 classes down to 9. With the floor, the smaller
# categories keep their 15 rows, so the sample ends up slightly above 10% of the
# corpus overall - step 1 prints the figure actually obtained, and that is the
# one to quote.
#
# Report wording: "l'export complet a été diagnostiqué et filtré ; le nettoyage
# sémantique et l'entraînement ont ensuite été menés sur un échantillon
# stratifié de 10 % du corpus exploitable (avec un plancher de 15 exemples par
# catégorie), contrainte de temps de calcul assumée."
CORPUS_SAMPLE_FRACTION = 0.10

# Step 4 trains on the whole sampled corpus: the quarter drawn above already is
# the reduction, and cutting it twice would leave too little per class.
TRAIN_SAMPLE_FRACTION = 1.0

# --------------------------------------------------------------------------- targets
#
# What the model is asked to predict, and why these two rather than the conclusion.
#
# The conclusion columns are 94% empty in this export, and what little they hold is
# free narrative - the agent restating the request, names and phone numbers
# included - not a normalised outcome. They cannot carry a classification task.
#
# These two can, and both feed the platform of the second part directly:
#
#   REQUEST_CATEGORY  the complaint's category, which the application suggests to
#                     the qualification agent when a complaint is registered.
#   ROUTING_LEVEL     which tier handles it, which is the decision the BPMN
#                     gateway makes right after qualification.
TARGETS = {
    "REQUEST_CATEGORY": "Catégorie de la demande",
    "ROUTING_LEVEL": "Niveau de traitement (FO / MO / BO)",
}

# --------------------------------------------------------------------------- LLM

# Run locally through Ollama: the complaints are customer data and must not leave
# the machine. That constraint is what rules out a hosted API here.
LLM_MODEL = "qwen3:8b"
LLM_TEMPERATURE = 0.0

# Descriptions are deduplicated before being sent, so this caps distinct texts,
# not rows. None cleans every distinct description.
LLM_DESCRIPTION_LIMIT = None

# --------------------------------------------------------------------------- features

# Multilingual, unlike all-MiniLM-L6-v2 which was trained on English only. The
# complaints are in French - with some Arabic - so the English model embeds them
# by shape rather than by meaning. Same 384 dimensions, so nothing downstream
# changes.
EMBEDDING_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
EMBEDDING_DIM = 384
EMBEDDING_BATCH_SIZE = 64

# --------------------------------------------------------------------------- training

TEST_SIZE = 0.2
EPOCHS = 60
BATCH_SIZE = 32
LEARNING_RATE = 2e-4
WEIGHT_DECAY = 1e-4
EARLY_STOPPING_PATIENCE = 8

# The three text variants compared in the ablation. Each adds a cleaning stage on
# top of the previous, so the difference in score is what that stage was worth.
VARIANTS = {
    "raw": "DESCRIPTION",
    "rules": "DESCRIPTION_RULES",
    "rules+llm": "DESCRIPTION_LLM",
}
