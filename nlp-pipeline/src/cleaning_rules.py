"""
Rule-based cleaning: everything a regular expression can remove without risking
the meaning of the sentence.

This pass runs first because it is deterministic, free, and handles the bulk of
the noise. What it cannot do is normalise wording - two agents writing the same
outcome in two different sentences stay two different strings. That is the LLM's
job, and it only has to look at what survives here.
"""

from __future__ import annotations

import re

import pandas as pd
from bs4 import BeautifulSoup

# Exports carry the raw bytes of attached Office documents in some rows: the text
# column holds a zip directory listing rather than a complaint.
OFFICE_ARTEFACT = re.compile(
    r"docProps|core\.xml|\[Content_Types\]|word/|xl/", re.IGNORECASE
)

# Conclusions that record a clerical act rather than a business outcome. Left in,
# they become classes the model is asked to predict from a complaint that has
# nothing to do with them.
BAD_TARGETS = {
    "en double",
    "en double à cloturer",
    "sans suite // reçu en double",
    "en double // sans suite",
    "requête en double générée à tort",
    "communication coupée",
    "conversation interrompue",
    "test",
    "envoyée",
    "sans identifiant",
    "sans identifiant, sans identifiant",
}


def strip_markup(text) -> str | float:
    """HTML, template placeholders and stray whitespace - applied to every text column."""
    if pd.isna(text):
        return text

    text = str(text)
    text = BeautifulSoup(text, "html.parser").get_text(" ")
    text = re.sub(r"\$.*?\$", " ", text)          # $VARIABLE$ placeholders
    text = re.sub(r"[\r\n\t]", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def is_office_artefact(text) -> bool:
    return bool(OFFICE_ARTEFACT.search(str(text))) if pd.notna(text) else False


# ----------------------------------------------------------------- the target column

_CONCLUSION_PATTERNS: list[tuple[str, int]] = [
    # identifiers and contact details
    (r"[\w\.-]+@[\w\.-]+\.\w+", re.IGNORECASE),
    (r"http\S+|www\.\S+", 0),
    (r"(\+212[\s\-]?\d{9}|0\d{9})", 0),
    (r"\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b", 0),
    (
        r"\b(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)?\s*\d{1,2}\s+"
        r"(?:janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|"
        r"novembre|décembre)\s+\d{4}\b",
        re.IGNORECASE,
    ),
    (r"\b\d{1,2}[:h]\d{2}\b", 0),
    (r"\b\d{6,}\b", 0),
    # file references
    (r"num[: ]+\S+", re.IGNORECASE),
    (r"r[ée]f[: ]+\S+", re.IGNORECASE),
    (r"r[ée]f[ée]rence[: ]+\S+", re.IGNORECASE),
    (r"dossier[: ]+\S+", re.IGNORECASE),
    (r"n°[: ]+\S+", re.IGNORECASE),
    # mail client headers
    (r"(?:De|À|A|Cc|Cci|Objet|Envoyé|Subject|From|To|Sent)\s*:", re.IGNORECASE),
    (r"(?:Pièces jointes|Attachments)\s*:", re.IGNORECASE),
    # courtesy formulas
    (r"Bien cordialement|Cordialement|Salutations|Sincères salutations", re.IGNORECASE),
    (r"Bonne réception|Bonne journée", re.IGNORECASE),
    (r"Merci d.*|Veuillez.*|Je vous prie.*", re.IGNORECASE),
    (r"\bMerci\b", re.IGNORECASE),
]

_CONCLUSION_TAIL_PATTERNS = [
    r"réponse transmise par mail.*",
    r"notification.*",
    r"ce message.*",
    r"cet email.*",
    r"message automatique.*",
    r"courrier électronique.*",
    r"-----Original Message-----.*",
]


def clean_conclusion(text) -> str | float:
    """Strip identifiers, mail plumbing and politeness from a conclusion."""
    if pd.isna(text):
        return pd.NA

    text = str(text)

    for pattern, flags in _CONCLUSION_PATTERNS:
        text = re.sub(pattern, " ", text, flags=flags)

    for pattern in _CONCLUSION_TAIL_PATTERNS:
        text = re.sub(pattern, " ", text, flags=re.IGNORECASE | re.DOTALL)

    text = re.sub(r"[_\-]{2,}", " ", text)
    text = re.sub(r"\s+", " ", text)
    text = text.strip(" ,.;:-")

    return text if text else pd.NA


# ------------------------------------------------------------------ the input column


def clean_description(text) -> str:
    """
    Strip personal data and boilerplate from a complaint.

    Deliberately more conservative than the conclusion cleaner: the description is
    the model's only evidence, so anything ambiguous is left in rather than risk
    deleting the sentence that carries the customer's actual request.
    """
    if pd.isna(text):
        return ""

    text = str(text)

    text = re.sub(r"\b[\w\.-]+@[\w\.-]+\.\w+\b", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"(\+212|0)\s?\d(?:[\s.-]?\d){8,}", " ", text)
    text = re.sub(r"\b(?:cnra|rcar)\d+\w*\b", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"Num[ée]ro\s*client\s*:?\s*\d+", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"Affili[ée]\s*:?\s*\d+", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"(?:Fixe|Gsm)\s*:?.*", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"Adresse\s*:.*", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"E-?MAIL\s*:.*", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"NB\s*:.*?(?=Demande|Réclamation|$)", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"\bnull\b", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"-{3,}", " ", text)
    text = re.sub(r"_+", " ", text)
    text = re.sub(r"\s+", " ", text)

    return text.strip()


def is_bad_target(text) -> bool:
    return pd.notna(text) and str(text).strip().lower() in BAD_TARGETS


# -------------------------------------------------------------- conclusion assembly

CONCLUSION_COLUMNS = ["CONCLUSION_FO", "CONCLUSION_MO", "CONCLUSION_BO", "CONCLUSION"]


def merge_conclusions(row: pd.Series) -> str | float:
    """
    Collapse the per-unit conclusion columns into one.

    Order matters and follows the circuit itself: Front Office, then Middle, then
    Back, then the closing conclusion. A complaint that travelled the whole chain
    carries several, and the later ones qualify the earlier ones rather than
    replacing them - so they are concatenated, not overwritten.
    """
    values = []

    for column in CONCLUSION_COLUMNS:
        value = row.get(column)
        if pd.isna(value):
            continue
        value = str(value).strip()
        if value and value.lower() != "null":
            values.append(value)

    return ", ".join(values) if values else pd.NA
