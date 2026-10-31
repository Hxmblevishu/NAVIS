package com.navis.companion.audio

/** Matches the spoken wake phrase and returns any request spoken in the same utterance. */
data class WakePhraseMatch(val detected: Boolean, val command: String)

object WakePhraseParser {
    private val wakePrefix = Regex(
        "\\b(?:hello|hey|hi|okay|ok|yo)(?:\\s+there)?[\\s,;:!.-]*(?:navis|navi|navy|novice|navus|n\\s*a\\s*v\\s*i\\s*s)\\b[\\s,;:!?.-]*(?:and\\s+)?",
        RegexOption.IGNORE_CASE
    )

    fun parse(transcript: String): WakePhraseMatch {
        val trimmed = transcript.trim()
        val match = wakePrefix.find(trimmed) ?: return WakePhraseMatch(false, "")
        return WakePhraseMatch(
            detected = true,
            command = trimmed.substring(match.range.last + 1).trim().trimStart(',', ';', ':', '!', '?', '-', ' ')
        )
    }

    fun stripWakePhrase(transcript: String): String {
        val trimmed = transcript.trim()
        val match = wakePrefix.find(trimmed) ?: return trimmed
        return trimmed.substring(match.range.last + 1).trim().trimStart(',', ';', ':', '!', '?', '-', ' ')
    }
}
