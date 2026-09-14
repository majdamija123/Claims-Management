"""
Semantic cleaning with a locally-run language model.

Why a model at all, when regular expressions already removed the noise: the target
column is free text written by dozens of agents over years. "Client informé qu'il
doit déposer une demande", "Le client a été informé de la procédure de dépôt" and
"Information communiquée au client concernant le dépôt" are one business outcome
written three ways. A classifier sees three classes with one example each and
learns nothing. No regular expression can merge them, because the merge depends on
meaning rather than on form.

Why locally: the text is customer correspondence. Sending it to a hosted API would
put CDG's complaint history on somebody else's servers, which is not a trade this
project is allowed to make. Ollama runs the model on the same machine as the data.

Two things make this affordable:

- Deduplication. The same conclusion is written thousands of times. Calling the
  model once per *distinct* string and mapping the answer back cuts the call count
  by roughly the duplication factor of the column - typically four to five fold.
- A disk cache. Every answer is written to JSON as it arrives, so a crash, a laptop
  closing, or a second run costs nothing already paid for.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pandas as pd
from tqdm.auto import tqdm

# Qwen3 emits its chain of thought inside <think> tags before the answer. Left in,
# the "normalised conclusion" would be several paragraphs of reasoning.
THINK_BLOCK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)


CONCLUSION_PROMPT = """\
You are preparing the TARGET column of a machine learning dataset.

Your goal is to normalize customer service conclusions.
Different sentences having the same business meaning must produce the SAME output.

Rules:
1. Keep ONLY the business outcome.
2. Remove names, emails, phone numbers, dates, references, IDs, greetings,
   signatures, duplicated text and metadata.
3. Normalize the wording.

Examples:
Client informé qu'il doit déposer une demande. -> Information fournie au client.
Le client a été orienté vers l'agence. -> Client orienté vers l'agence.
Attestation envoyée par email. -> Attestation envoyée.
Paiement effectué. -> Paiement effectué.
Dossier transmis au service concerné. -> Dossier transféré au service concerné.

Never invent anything. Maximum 8 words.
Return only the normalized conclusion, nothing else.

Conclusion:
{text}"""


DESCRIPTION_PROMPT = """\
You are a data cleaning assistant.
Your task is to clean customer complaints before machine learning.

Rules:
1. Keep ONLY the complaint.
2. Remove:
   - names
   - emails
   - phone numbers
   - addresses
   - customer numbers
   - references
   - greetings
   - signatures
   - thanks
   - politeness
3. Preserve the original meaning. Never invent anything not written.
4. Rewrite the complaint in fluent French, in one concise sentence.
5. Return ONLY the cleaned complaint - no explanation, no quotes, no markdown.

Example:
Bonjour, Je vous prie de bien vouloir me délivrer une attestation IR.
Num client : 556262790 Email : abc@gmail.com Adresse : Casablanca. Merci beaucoup.
-> Demande d'attestation d'impôt sur le revenu.

Complaint:
{text}"""


class LlmCleaner:
    """A cached, deduplicating wrapper around a local Ollama model."""

    def __init__(
        self,
        model: str,
        temperature: float,
        cache_path: Path,
        disable_thinking: bool = True,
    ):
        self.model = model
        self.cache_path = cache_path
        self.cache: dict[str, str] = self._load_cache()
        self._llm = None
        self._temperature = temperature
        self._disable_thinking = disable_thinking

    # ------------------------------------------------------------------ cache

    def _load_cache(self) -> dict[str, str]:
        if self.cache_path.exists():
            return json.loads(self.cache_path.read_text(encoding="utf-8"))
        return {}

    def _save_cache(self) -> None:
        self.cache_path.write_text(
            json.dumps(self.cache, ensure_ascii=False, indent=1), encoding="utf-8"
        )

    # ------------------------------------------------------------------ model

    def _client(self):
        """Built on first use, so importing this module never requires Ollama."""
        if self._llm is None:
            from langchain_ollama import ChatOllama

            self._llm = ChatOllama(model=self.model, temperature=self._temperature)
        return self._llm

    def _ask(self, prompt: str) -> str:
        if self._disable_thinking:
            # Qwen3's own switch. The <think> block is stripped below either way,
            # but not generating it in the first place is what saves the time.
            prompt = f"{prompt}\n/no_think"

        answer = self._client().invoke(prompt).content
        answer = THINK_BLOCK.sub("", answer)
        return answer.strip().strip('"').strip()

    # ------------------------------------------------------------------ public

    def clean_series(
        self, series: pd.Series, prompt_template: str, label: str
    ) -> tuple[pd.Series, dict]:
        """
        Clean every value of a column, calling the model once per distinct value.

        Returns the cleaned column and the statistics the report quotes: how many
        rows, how many distinct values, how many calls were actually needed.
        """
        present = series.dropna().astype(str).str.strip()
        present = present[present != ""]

        unique_values = present.unique().tolist()
        to_call = [value for value in unique_values if value not in self.cache]

        stats = {
            "column": label,
            "rows": int(len(present)),
            "unique_values": int(len(unique_values)),
            "already_cached": int(len(unique_values) - len(to_call)),
            "llm_calls": int(len(to_call)),
            "calls_saved_by_dedup": int(len(present) - len(unique_values)),
        }

        consecutive_failures = 0

        for index, value in enumerate(tqdm(to_call, desc=f"LLM · {label}"), start=1):
            try:
                self.cache[value] = self._ask(prompt_template.format(text=value))
                consecutive_failures = 0
            except Exception as error:  # noqa: BLE001 - a failed call must not lose the run
                # Deliberately NOT cached: a fallback written to the cache would be
                # reused by a later, working run and silently replace a real answer.
                consecutive_failures += 1
                if consecutive_failures == 1:
                    print(f"\n  ! LLM call failed: {error}")

                # Three in a row is not bad luck, it is a broken setup - Ollama not
                # running, model not pulled, package missing. Carrying on would print
                # the same error thousands of times and waste the operator's evening.
                if consecutive_failures >= 3:
                    print(
                        f"  ! giving up on the {label} pass after 3 consecutive failures.\n"
                        f"    Every uncleaned value keeps its rule-cleaned text, so the\n"
                        f"    pipeline still runs - but the 'rules+llm' variant will be\n"
                        f"    identical to 'rules'. Check that Ollama is running and that\n"
                        f"    '{self.model}' is pulled, then run this step again."
                    )
                    stats["llm_calls"] = index - 1
                    stats["aborted"] = True
                    break

            if index % 25 == 0:  # checkpoint often; LLM time is expensive to lose
                self._save_cache()

        self._save_cache()

        cleaned = series.map(
            lambda value: self.cache.get(str(value).strip(), value)
            if pd.notna(value)
            else value
        )
        return cleaned, stats


def consolidation_ratio(before: pd.Series, after: pd.Series) -> dict:
    """
    How much the normalisation actually merged.

    This is the number that justifies the whole stage: if the LLM turned 2 400
    distinct conclusions into 260, the classification problem went from
    hopeless to tractable, and the report can say so with a figure.
    """
    distinct_before = int(before.dropna().nunique())
    distinct_after = int(after.dropna().nunique())

    return {
        "distinct_before": distinct_before,
        "distinct_after": distinct_after,
        "reduction_pct": round(
            100 * (1 - distinct_after / distinct_before), 1
        )
        if distinct_before
        else 0.0,
    }
