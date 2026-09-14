# Volet 1 — Nettoyage intelligent et classification des réclamations

Chaîne de traitement qui part de l'export brut des réclamations CDG et produit un
modèle capable de prédire la conclusion métier d'une nouvelle réclamation.

```
Export brut (parquet)
    │
    ├─ step1  préparation      colonnes, fusion FO→MO→BO→CONCLUSION, échantillon 25 %
    ├─ step2  nettoyage règles  regex : emails, téléphones, dates, en-têtes, signatures
    ├─ step3  nettoyage LLM     Qwen3:8b en local, normalisation sémantique
    ├─ step4  entraînement      3 variantes × 2 modèles (ablation)
    └─ step5  rapport           tableaux et figures prêts pour le mémoire
```

---

## Trois choix qui structurent ce projet

**1. Le nettoyage sémantique est mesuré, pas affirmé.**
Le step 4 entraîne les mêmes modèles sur trois versions du texte : brut, nettoyé par
règles, puis nettoyé par LLM. L'écart de F1 entre les trois *est* la valeur de chaque
étape. Le rapport ne dit plus « nous avons utilisé un LLM », il dit « le nettoyage
sémantique a apporté X points de F1 macro ».

**2. Le LLM ne tourne qu'une fois par valeur distincte.**
La colonne des conclusions se répète massivement : la même phrase revient des
centaines de fois. Appeler le modèle une fois par *valeur unique* puis remapper divise
le nombre d'appels par le facteur de duplication de la colonne. Chaque réponse est en
plus mise en cache sur disque, donc une coupure ou un second passage ne recoûte rien.

**3. Les embeddings sont multilingues.**
`all-MiniLM-L6-v2`, le modèle par défaut dans la plupart des tutoriels, est entraîné
uniquement sur de l'anglais. Sur du texte français il renvoie bien 384 nombres, mais
ceux-ci encodent la forme plus que le sens. `paraphrase-multilingual-MiniLM-L12-v2`
a les mêmes dimensions et lit réellement le français.

---

## Installation

```bash
cd nlp-pipeline
python -m venv .venv
.venv\Scripts\activate        # Windows
pip install -r requirements.txt
```

Pour le nettoyage sémantique, il faut [Ollama](https://ollama.com) et le modèle :

```bash
ollama pull qwen3:8b
```

Les données clients ne quittent jamais la machine : c'est la raison de ce choix
plutôt qu'une API hébergée.

---

## Utilisation

Placez l'export dans `data/reclamations_complet.parquet`, puis :

```bash
python pipeline/step1_prepare.py
python pipeline/step2_clean_rules.py
python pipeline/step3_clean_llm.py
python pipeline/step4_train.py
python pipeline/step5_report.py
```

**Sans l'export sous la main**, pour vérifier que tout s'exécute :

```bash
python tools/make_demo_data.py
```

Ce générateur produit des réclamations **inventées**. Elles servent uniquement à
tester le code : aucun chiffre mesuré dessus ne doit apparaître dans le rapport.

---

## Réglages

Tout est dans `config.py` :

| Paramètre | Défaut | Effet |
|---|---|---|
| `SAMPLE_FRACTION` | `0.25` | Part du corpus traitée |
| `MIN_SAMPLES_PER_CLASS` | `10` | Seuil sous lequel une classe est écartée |
| `LLM_DESCRIPTION_LIMIT` | `1500` | Nombre de descriptions passées au LLM |
| `EMBEDDING_MODEL` | multilingue | Modèle de vectorisation |
| `EPOCHS` / `BATCH_SIZE` | `60` / `64` | Entraînement du réseau |

L'échantillon est tiré de façon **stratifiée** sur la conclusion, pas avec
`head(n)` : l'export étant trié par date, ses premières lignes ne représentent
qu'une période de l'année.

---

## Ce que produit le pipeline

```
outputs/
├── 01_prepared.parquet          après préparation
├── 02_rules_cleaned.parquet     après nettoyage par règles
├── 03_llm_cleaned.parquet       après nettoyage sémantique
├── figures/
│   ├── ablation.png             apport de chaque étape (figure principale)
│   ├── confusion_matrix.png     matrice de confusion du meilleur modèle
│   └── loss_*.png               courbes de perte par variante
└── metrics/
    ├── step1_prepare.json       volumétrie à chaque filtrage
    ├── step2_rules.json         effet des règles
    ├── step3_llm.json           consolidation des classes, appels économisés
    ├── step4_ablation.json      les six scores
    ├── step4_best.json          modèle retenu + confusions les plus fréquentes
    ├── classification_report.txt précision/rappel par classe
    └── tableau_comparaison.csv  à importer dans Word
```

`step5_report.py` réimprime tout cela sous forme de tableaux directement
copiables dans les chapitres 4 et 5 du mémoire.

---

## Correctifs par rapport à la première version

La version perdue contenait quelques défauts que celle-ci corrige :

- **Boucle d'entraînement** — `scheduler.step()`, l'early stopping et la sauvegarde
  du meilleur modèle étaient hors de la boucle `for` (problème d'indentation) : ils
  ne s'exécutaient qu'une fois, à la fin. Aucun early stopping n'avait donc lieu.
- **Early stopping sur la mauvaise perte** — il surveillait la perte
  d'entraînement, qui décroît même quand le modèle sur-apprend. Il suit désormais la
  perte de validation.
- **Balises `<think>`** — Qwen3 émet son raisonnement avant sa réponse ; sans
  filtrage, la « conclusion normalisée » contenait des paragraphes de réflexion.
- **Découpage non stratifié** — avec autant de classes rares, un split aléatoire
  place régulièrement tous les exemples d'une classe du même côté.
- **Désalignement d'index** — `df["X"] = sample_df["Y"]` entre deux DataFrames
  d'index différents produisait des `NaN` silencieux.
