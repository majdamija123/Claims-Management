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

    # Parsing is what costs here: this runs on every cell of every text column,
    # which is tens of millions of calls on the full export, and BeautifulSoup
    # is milliseconds per call. Most cells carry no markup at all, and the
    # cheap membership test that skips them turns hours into minutes.
    if "<" in text:
        text = BeautifulSoup(text, "html.parser").get_text(" ")
    if "$" in text:
        text = re.sub(r"\$.*?\$", " ", text)      # $VARIABLE$ placeholders

    text = re.sub(r"\s+", " ", text)              # also collapses \r \n \t
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


# Contact details and identifiers: removed wherever they appear.
_DESCRIPTION_PATTERNS: list[tuple[str, int]] = [
    (r"\b[\w\.-]+@[\w\.-]+\.\w+\b", re.IGNORECASE),          # email addresses
    (r"https?://\S+|www\.\S+", re.IGNORECASE),               # links
    (r"(\+212|0)\s?\d(?:[\s.-]?\d){8,}", 0),                 # phone numbers
    (r"\b(?:cnra|rcar)\d+\w*\b", re.IGNORECASE),             # web request references
    (r"\b\d{6,}\b", 0),                                      # client / affiliation numbers
]

# Labels that open a contact block: whatever follows them, to the end of the
# segment, is address or signature boilerplate rather than part of the request.
_DESCRIPTION_BLOCK_LABELS = (
    r"adresse|rue|quartier|appartement|appt|bloc|ville|code\s*postal|"
    r"e-?mail|mail|courriel|t[ée]l[ée]phone|t[ée]l|fixe|gsm|mobile|portable|"
    r"signature|envoy[ée]\s+de(?:puis)?|nb"
)

# Labels that appear mid-sentence, inside prose that must survive: "... au sujet
# de Mme X, CIN : BK 348947 ayant le numéro 887261974 qui réclame ...". Only the
# identifier itself is removed - tokens carrying a digit, or written in capitals
# - never the words around it, which carry the request.
_DESCRIPTION_INLINE_LABELS = (
    r"n°|num[ée]ro|num|r[ée]f[ée]rence|r[ée]f|dossier|cin|client|affili[ée]|"
    r"nom|pr[ée]nom"
)

# An identifier-shaped token: contains a digit, or is an all-caps code.
_IDENTIFIER_TOKEN = r"(?:\S*\d\S*|[A-Z]{2,}[\w\-/]*)"

# Greetings and sign-offs. The sign-offs also take everything after them: what
# follows "Cordialement" is a signature block, never part of the request.
_DESCRIPTION_GREETINGS = (
    r"bonjour|bonsoir|salut|madame|monsieur|mesdames|messieurs|"
    r"cher\s+client|ch[eè]re\s+cliente|cher|ch[eè]re"
)

_DESCRIPTION_SIGNOFFS = (
    r"bien\s+cordialement|cordialement|sinc[eè]res\s+salutations?|"
    r"(?:mes\s+)?salutations?\s+distingu[ée]es|mes\s+salutations?|"
    r"veuillez\s+agr[ée]er|je\s+vous\s+prie\s+d['’]agr[ée]er|"
    r"dans\s+l['’]attente\s+de\s+votre\s+r[ée]ponse|"
    r"envoy[ée]\s+de(?:puis)?\s+mon|merci\s+d['’]avance|merci\s+beaucoup|"
    r"avec\s+mes\s+remerciements"
)


def clean_description(text) -> str:
    """
    Strip personal data, contact blocks and politeness from a complaint.

    What survives is meant to be the request and nothing else - the department's
    own specification for this dataset. The aggressive parts are anchored on an
    explicit label ("Adresse :", "Numéro client :") or on a sign-off, both of
    which reliably introduce boilerplate rather than content; free prose is left
    alone, because the description is the model's only evidence and a sentence
    deleted here is a complaint the classifier can no longer read.

    The LLM pass that follows handles what no pattern can: a name written on its
    own line, a request buried in three paragraphs of courtesy.
    """
    if pd.isna(text):
        return ""

    text = str(text)

    # Labels run BEFORE the bare-identifier patterns below: "Num client :
    # 556262790" has to be removed as one unit, because stripping the number
    # first would leave the orphaned label behind with nothing to match.

    # A contact-block label and everything after it, to the end of the segment.
    text = re.sub(
        # The value stops at a sentence break, but a dot inside a token (a
        # domain name, an initial) belongs to the value and is consumed with it.
        rf"\b(?:{_DESCRIPTION_BLOCK_LABELS})\s*:(?:[^\n.;]|\.(?!\s))*",
        " ",
        text,
        flags=re.IGNORECASE,
    )

    # An inline label and the identifier it introduces - and nothing else.
    text = re.sub(
        # The label is matched case-insensitively; the identifier token is not,
        # because recognising an all-caps code is the whole point of it.
        rf"(?i:\b(?:{_DESCRIPTION_INLINE_LABELS})"
        rf"(?:\s+(?:client|(?:d['’])?affiliation|de\s+dossier))?\s*:?\s*)"
        rf"(?:{_IDENTIFIER_TOKEN}\s*){{1,4}}",
        " ",
        text,
    )

    # Bare identifiers, wherever they survived without a label in front.
    for pattern, flags in _DESCRIPTION_PATTERNS:
        text = re.sub(pattern, " ", text, flags=flags)

    # Greetings, where they open the text or a sentence - repeated, since they
    # come stacked ("Madame, Monsieur,"). Anchoring on the sentence start keeps
    # a "monsieur" written about a third party inside the request.
    text = re.sub(
        rf"(?:^|(?<=[.;!?]))\s*(?:(?:{_DESCRIPTION_GREETINGS})\b[\s,.:;]*)+",
        " ",
        text,
        flags=re.IGNORECASE,
    )

    # A sign-off and everything after it.
    text = re.sub(rf"\b(?:{_DESCRIPTION_SIGNOFFS})\b.*", " ", text, flags=re.IGNORECASE | re.DOTALL)

    text = re.sub(r"\bnull\b", " ", text, flags=re.IGNORECASE)
    text = re.sub(r"[-_]{2,}", " ", text)
    text = re.sub(r"\s+", " ", text)

    return text.strip(" ,.;:-")


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
