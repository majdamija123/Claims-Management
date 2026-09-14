"""
A synthetic stand-in for the real export.

Its only purpose is to let the pipeline be run end to end - to check that every
step executes, that the figures are produced, that nothing crashes - before the
real parquet is available.

IT IS NOT DATA. Nothing produced from it may appear in the report as a result.
Numbers measured on invented complaints describe the invention, not CDG's service,
and presenting them as findings would be fabrication. Every file it writes is
named accordingly and every run prints this warning.
"""

from __future__ import annotations

import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config

SEED = 7
ROWS = 4000

CATEGORIES = [
    ("RECLAMATION", "Retard de paiement"),
    ("RECLAMATION", "Erreur de calcul"),
    ("DEMANDE", "Attestation"),
    ("DEMANDE", "Changement de coordonnées"),
    ("RECLAMATION", "Prélèvement contesté"),
    ("DEMANDE", "Duplicata de document"),
    ("RECLAMATION", "Qualité de service"),
    ("DEMANDE", "Information sur le dossier"),
]

CLIENT_KINDS = ["BENEFICIAIRE", "AFFILIE", "EMPLOYEUR", "AYANT DROIT"]

# Several phrasings per outcome, which is exactly the problem the LLM pass solves.
OUTCOMES = {
    "Information fournie au client": [
        "Client informé qu'il doit déposer une demande",
        "Le client a été informé de la procédure à suivre",
        "Information communiquée au client concernant sa demande",
        "Nous avons informé le client des modalités",
    ],
    "Attestation envoyée": [
        "Attestation envoyée par email",
        "Attestation transmise au client",
        "Document envoyé par courrier électronique",
        "Attestation délivrée et envoyée",
    ],
    "Client orienté vers l'agence": [
        "Le client a été orienté vers l'agence",
        "Client invité à se présenter en agence",
        "Orientation du client vers l'agence la plus proche",
    ],
    "Paiement effectué": [
        "Paiement effectué",
        "Le virement a été réalisé",
        "Règlement effectué au profit du client",
    ],
    "Dossier transféré au service concerné": [
        "Dossier transmis au service concerné",
        "Transfert du dossier vers le service compétent",
        "Dossier orienté vers le back office",
    ],
    "Demande rejetée": [
        "Demande rejetée faute de pièces justificatives",
        "Rejet de la demande, dossier incomplet",
        "Demande non recevable",
    ],
    "Coordonnées mises à jour": [
        "Coordonnées mises à jour dans le système",
        "Mise à jour des informations du client effectuée",
        "Changement d'adresse enregistré",
    ],
}

COMPLAINT_TEMPLATES = [
    "Bonjour, je n'ai toujours pas reçu {sujet} malgré plusieurs relances. Merci d'avance. Cordialement",
    "Madame, Monsieur, je vous écris concernant {sujet}. Pourriez-vous régulariser ma situation ? Cordialement",
    "Bonjour, {sujet} depuis plus de deux mois. Je vous prie de bien vouloir traiter ce dossier. Merci",
    "Je souhaite obtenir {sujet}. Numéro client : {num}. Merci de votre retour. Bien cordialement",
    "Bonsoir, suite à mon appel, {sujet} n'a pas été résolu. Fixe : 0{tel}. Salutations",
]

SUBJECTS = [
    "ma pension du mois dernier",
    "mon attestation de cotisation",
    "le remboursement promis",
    "la correction de mon dossier",
    "un duplicata de mon relevé",
    "la mise à jour de mon adresse",
    "le paiement de mes droits",
    "une réponse à ma réclamation",
]


def main() -> None:
    print(__doc__)
    random.seed(SEED)

    outcome_names = list(OUTCOMES)
    # Deliberately unbalanced, like the real thing.
    outcome_weights = [30, 22, 15, 12, 9, 7, 5][: len(outcome_names)]

    rows = []

    for index in range(ROWS):
        category, subcategory = random.choice(CATEGORIES)
        outcome = random.choices(outcome_names, weights=outcome_weights, k=1)[0]

        template = random.choice(COMPLAINT_TEMPLATES)
        description = template.format(
            sujet=random.choice(SUBJECTS),
            num=random.randint(100000, 999999),
            tel=random.randint(600000000, 699999999),
        )

        if random.random() < 0.25:
            description += " <br/> E-MAIL : client{}@example.com".format(index)

        phrasing = random.choice(OUTCOMES[outcome])

        rows.append(
            {
                "NUMEROREQUETE": 100000 + index,
                "IDT_CLIENT": random.randint(1000, 9999),
                "DESCRIPTION": description,
                "LIBELLE": category,
                "LIBELLE_1": subcategory,
                "NATURECLIENT": random.choice(CLIENT_KINDS),
                "IDENTIFIANT_CANAL": f"client{index}@example.com",
                "CONCLUSION_FO": phrasing if random.random() < 0.6 else None,
                "CONCLUSION_MO": phrasing if random.random() < 0.2 else None,
                "CONCLUSION_BO": phrasing if random.random() < 0.1 else None,
                "CONCLUSION": phrasing if random.random() < 0.4 else None,
                "ETATENGAGEMENT": "CLOS",
            }
        )

    df = pd.DataFrame(rows)

    # A handful of rows carrying attachment bytes instead of text, so step 1 has
    # something to filter and the pipeline is exercised as it would be for real.
    for position in random.sample(range(len(df)), 20):
        df.loc[position, "DESCRIPTION"] = "[Content_Types].xml word/document.xml docProps/core.xml"

    df.to_parquet(config.RAW_PARQUET, index=False)

    print(f"\nWrote {len(df):,} SYNTHETIC rows to {config.RAW_PARQUET}")
    print("Replace this file with the real export before producing any reported result.")


if __name__ == "__main__":
    main()
