"""Rule-first intent resolution with character TF-IDF paraphrase matching."""

import re
from typing import Any, Dict, List, Optional

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity

from backend.app.voice.intents import INTENTS
from backend.app.voice.normalizer import normalize_command


class NavisIntentEngine:
    """Resolve spoken UI requests locally without sending command text to an LLM."""

    def __init__(self, minimum_confidence: float = 0.60, minimum_margin: float = 0.05):
        self.minimum_confidence = minimum_confidence
        self.minimum_margin = minimum_margin
        self.examples: List[str] = []
        self.labels: List[str] = []
        self.exact_matches: Dict[str, str] = {}

        for intent, metadata in INTENTS.items():
            for example in metadata["examples"]:
                normalized = normalize_command(example)
                self.examples.append(normalized)
                self.labels.append(intent)
                self.exact_matches[normalized] = intent

        self.vectorizer = TfidfVectorizer(
            analyzer="char_wb",
            ngram_range=(2, 5),
            lowercase=True,
            dtype=np.float32,
        )
        self.matrix = self.vectorizer.fit_transform(self.examples)
        self.navigation_pattern = re.compile(
            r"^(?:(?:please|could you|can you)\s+)?(?:(?:navigate|directions|route|take me|go)\s+(?:to|toward|towards)|how do i get to)\s+(.+)$"
        )

    def predict(self, command: str) -> Optional[Dict[str, Any]]:
        """Return intent, action, confidence, method, and safe UI parameters."""
        normalized = normalize_command(command)
        if not normalized:
            return None

        exact_intent = self.exact_matches.get(normalized)
        if exact_intent is not None:
            return {
                "intent": exact_intent,
                "action": exact_intent,
                "confidence": 1.0,
                "method": "rule",
                "parameters": dict(INTENTS[exact_intent].get("parameters", {})),
            }

        navigation_match = self.navigation_pattern.fullmatch(normalized)
        if navigation_match and navigation_match.group(1).strip() not in {
            "somewhere", "a place", "a location", "a destination"
        }:
            return {
                "intent": "OPEN_NAVIGATION",
                "action": "OPEN_NAVIGATION",
                "confidence": 1.0,
                "method": "rule",
                "parameters": {
                    **INTENTS["OPEN_NAVIGATION"].get("parameters", {}),
                    "destination": navigation_match.group(1).strip(),
                },
            }

        # Question-shaped requests are conversational by default. Supported scene,
        # status, and OCR questions are matched above through their explicit examples.
        if re.match(r"^(?:who|what|when|where|why|how|which)\b", normalized) or re.match(
            r"^(?:(?:could you|can you)\s+)?tell me\b", normalized
        ):
            return None

        query = self.vectorizer.transform([normalized])
        scores = cosine_similarity(query, self.matrix)[0]
        intent_scores: Dict[str, float] = {}
        for index, score in enumerate(scores):
            intent = self.labels[index]
            intent_scores[intent] = max(intent_scores.get(intent, 0.0), float(score))

        ranked = sorted(intent_scores.items(), key=lambda item: item[1], reverse=True)
        if not ranked:
            return None
        best_intent, confidence = ranked[0]
        second_score = ranked[1][1] if len(ranked) > 1 else 0.0
        if confidence < self.minimum_confidence or confidence - second_score < self.minimum_margin:
            return None

        return {
            "intent": best_intent,
            "action": best_intent,
            "confidence": confidence,
            "method": "nlp",
            "parameters": dict(INTENTS[best_intent].get("parameters", {})),
        }
