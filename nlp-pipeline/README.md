# Volet 1 — Nettoyage intelligent et classification des réclamations

Chaîne de traitement qui part de l'export Excel des réclamations CDG
(`RECLAMATION_CLIENT2.xls`) et produit deux modèles de classification.

```
Export Excel (.xls)
    │
    ├─ step1  préparation      Excel → Parquet, retrait des actes automatiques
    ├─ step2  nettoyage règles  regex : emails, téléphones, adresses, HTML
    ├─ step3  nettoyage LLM     Qwen3:8b en local, déduplication, cache disque
    ├─ step4  entraînement      2 cibles × 3 variantes × 2 modèles
    └─ step5  rapport           tableaux et figures prêts pour le mémoire
```

---

## Ce que contient réellement l'export — et pourquoi cela dicte la conception

L'analyse du fichier a révélé trois choses qui ont orienté tout le projet.

**1. Quatre cinquièmes du fichier ne sont pas des réclamations.**

| | Lignes |
|---|---|
| Export brut | 19 999 |
| « Attestation livrée depuis WEB » et assimilés | **16 497 (82,5 %)** |
| Réclamations réellement exploitables | **≈ 2 900** |

Ce sont des attestations téléchargées par le client depuis le portail : même table,
mêmes colonnes, mais la description est une phrase fixe répétée seize mille fois.
Les garder revenait à entraîner le modèle à reconnaître un gabarit.

**2. La colonne `CONCLUSION` ne peut pas servir de cible.**

Elle est renseignée sur 1 106 lignes seulement (5,5 % de l'export), et son contenu
n'est pas une issue normalisée mais une reformulation libre de la demande, noms et
numéros de téléphone compris. Elle est néanmoins conservée : la **normalisation
sémantique par LLM** y est démontrée et mesurée, ce qui reste un résultat du volet.

**3. `DEGRE_URGENCE` est vide à 100 %.**

L'urgence annoncée dans le cahier des charges n'est pas présente dans cet export.

### Les deux cibles retenues

| Cible | Classes | Couverture | À quoi elle sert dans la plateforme |
|---|---|---|---|
| `REQUEST_CATEGORY` | 35 | 100 % | La catégorie proposée à l'agent Qualification lors de l'enregistrement |
| `ROUTING_LEVEL` | 3 (FO / MO / BO) | 100 % | La décision que prend le gateway BPMN juste après la qualification |

La seconde est la plus parlante pour la soutenance : elle relie directement le
volet Data au workflow du volet Plateforme.

---

## Trois choix qui structurent le projet

**1. Le nettoyage est mesuré, pas affirmé.**

Le step 4 entraîne les mêmes modèles sur **trois versions du texte** : brut,
nettoyé par règles, puis nettoyé par LLM. L'écart de F1 entre les trois *est* la
valeur de chaque étape. Le rapport ne dit plus « nous avons utilisé un LLM », il
dit « le nettoyage sémantique a apporté X points de F1 macro ».

**2. Le LLM ne tourne qu'une fois par texte distinct.**

Les descriptions se répètent : 2 932 lignes pour 2 184 textes distincts, et les
conclusions 841 lignes pour 401 valeurs. Appeler le modèle une fois par valeur
unique puis remapper évite **748 appels sur les descriptions et 440 sur les
conclusions**. Chaque réponse est mise en cache sur disque : une coupure ne
recoûte rien.

**3. Le découpage train/test est groupé par texte.**

Ce corpus se répète. Un `train_test_split` classique place certaines copies d'une
même réclamation à l'entraînement et les autres au test : le modèle est alors noté
sur des textes qu'il a déjà vus, et le score obtenu est flatteur mais faux.
`GroupShuffleSplit` sur le texte lui-même supprime cette fuite — c'est pour cela
que les scores sont plus bas ici, et honnêtes.

---

## Installation

```bash
cd nlp-pipeline
python -m venv .venv
.venv\Scripts\activate        # Windows
pip install -r requirements.txt
```

Pour le nettoyage sémantique, il faut [Ollama](https://ollama.com) :

```bash
ollama pull qwen3:8b
```

Les données clients ne quittent jamais la machine : c'est la raison de ce choix
plutôt qu'une API hébergée.

---

## Utilisation

Placez `RECLAMATION_CLIENT2.xls` dans `data/`, puis :

```bash
python pipeline/step1_prepare.py      # convertit l'Excel en parquet (une fois)
python pipeline/step2_clean_rules.py
python pipeline/step3_clean_llm.py    # le plus long : ~2 200 appels LLM
python pipeline/step4_train.py        # télécharge le modèle d'embeddings au 1er lancement
python pipeline/step5_report.py
```

Si Ollama n'est pas lancé, le step 3 le signale, conserve le texte nettoyé par
règles et laisse le pipeline continuer — la variante `rules+llm` sera simplement
identique à `rules`.

---

## Réglages

Tout est dans `config.py` :

| Paramètre | Défaut | Effet |
|---|---|---|
| `AUTOMATED_ACT_PATTERNS` | attestations web | Motifs des lignes à écarter |
| `MIN_DESCRIPTION_CHARS` | `25` | Seuil sous lequel une description est vide de sens |
| `MIN_SAMPLES_PER_CLASS` | `15` | Seuil sous lequel une classe est écartée |
| `LLM_DESCRIPTION_LIMIT` | `None` | Nombre de textes distincts envoyés au LLM |
| `EMBEDDING_MODEL` | multilingue | Modèle de vectorisation |
| `TARGETS` | catégorie + niveau | Les deux tâches de classification |

---

## Ce que produit le pipeline

```
outputs/
├── 01_prepared.parquet · 02_rules_cleaned.parquet · 03_llm_cleaned.parquet
├── figures/
│   ├── ablation_REQUEST_CATEGORY.png    apport de chaque étape
│   ├── ablation_ROUTING_LEVEL.png
│   ├── confusion_REQUEST_CATEGORY.png   matrice de confusion
│   ├── confusion_ROUTING_LEVEL.png
│   └── loss_*.png                       courbes de perte
└── metrics/
    ├── step1_prepare.json · step2_rules.json · step3_llm.json
    ├── step4_ablation.json              les douze scores
    ├── step4_best_*.json                modèle retenu + confusions fréquentes
    ├── classification_report_*.txt      précision/rappel par classe
    └── tableau_comparaison.csv          à importer dans Word
```

`step5_report.py` réimprime tout cela sous forme de tableaux copiables dans les
chapitres 4 et 5 du mémoire.

---

## Correctifs par rapport à la première version

La version perdue contenait des défauts que celle-ci corrige :

- **Boucle d'entraînement** — `scheduler.step()`, l'early stopping et la sauvegarde
  du meilleur modèle étaient hors de la boucle `for` (indentation) : ils ne
  s'exécutaient qu'une fois, à la fin. Aucun early stopping n'avait donc lieu.
- **Early stopping sur la mauvaise perte** — il surveillait la perte
  d'entraînement, qui décroît même quand le modèle sur-apprend.
- **Balises `<think>`** — Qwen3 émet son raisonnement avant sa réponse ; sans
  filtrage, la « conclusion normalisée » contenait des paragraphes de réflexion.
- **Fuite de données** — le découpage ne tenait pas compte des doublons de texte.
- **Désalignement d'index** — `df["X"] = sample_df["Y"]` entre deux DataFrames
  d'index différents produisait des `NaN` silencieux.
- **Modèle d'embeddings anglophone** — `all-MiniLM-L6-v2` sur du texte français.

---

## `tools/make_demo_data.py`

Génère des réclamations **inventées**, uniquement pour vérifier que le code
s'exécute quand l'export n'est pas disponible. Aucun chiffre mesuré dessus ne doit
apparaître dans le rapport.
