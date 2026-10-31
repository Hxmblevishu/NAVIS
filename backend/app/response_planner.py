"""Fast local planning for conversational answer depth and latency."""

import re
from dataclasses import dataclass
from typing import Literal


ResponseStyle = Literal["auto", "quick", "detailed"]

_EXPLICIT_QUICK_CUES = re.compile(
    r"\b(?:brief(?:ly)?|short(?:ly)?|quick(?:ly)?|concise|one sentence|"
    r"tl\s*;?\s*dr)\b",
    re.IGNORECASE,
)
_SUMMARY_CUES = re.compile(r"\b(?:summary|summari[sz]e)\b", re.IGNORECASE)
_DETAILED_CUES = re.compile(
    r"\b(?:detailed?|in depth|thorough|comprehensive|review|step by step|"
    r"explain|analyse|analyze|compare|pros and cons|why)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class ResponsePlan:
    """A no-network plan chosen before calling the language model."""

    style: Literal["quick", "detailed"]
    thinking_level: Literal["low", "medium"]
    max_output_tokens: int
    system_instruction: str


def plan_response(
    question: str,
    requested_style: ResponseStyle = "auto",
    base_instruction: str = "",
) -> ResponsePlan:
    """Choose a concise or deep answer without spending a model call on routing.

    Explicit wording in the question wins over a saved UI preference. Auto mode
    defaults to quick answers and expands when the user asks for analysis.
    """
    if _EXPLICIT_QUICK_CUES.search(question):
        style: Literal["quick", "detailed"] = "quick"
    elif _DETAILED_CUES.search(question):
        style = "detailed"
    elif _SUMMARY_CUES.search(question):
        style = "quick"
    elif requested_style == "detailed":
        style = "detailed"
    else:
        style = "quick"

    if style == "detailed":
        instruction = (
            "Give a detailed, well-organized explanation or review. Explain the key "
            "reasoning, important caveats, and practical next steps. Use headings or "
            "bullets when they improve clarity. State uncertainty instead of guessing."
        )
        return ResponsePlan(
            style=style,
            thinking_level="medium",
            max_output_tokens=768,
            system_instruction=f"{base_instruction}\n\n{instruction}".strip(),
        )

    instruction = (
        "Give the useful answer first in at most two short sentences (about 45 words). "
        "Skip the preamble. Keep any safety-critical caveat."
    )
    return ResponsePlan(
        style=style,
        thinking_level="low",
        max_output_tokens=128,
        system_instruction=f"{base_instruction}\n\n{instruction}".strip(),
    )
