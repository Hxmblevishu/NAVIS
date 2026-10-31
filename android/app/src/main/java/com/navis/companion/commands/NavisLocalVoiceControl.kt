package com.navis.companion.commands

import com.navis.companion.audio.WakePhraseParser
import java.text.Normalizer
import java.util.Locale

/** Immediate allow-listed controls that remain available without the backend. */
object NavisLocalVoiceControl {
    private val openVerb = Regex(
        "^(?:please\\s+)?(?:open|launch|show|start|go to|switch to|take me to)\\s+"
    )
    private val closeVerb = Regex("^(?:please\\s+)?(?:close|exit|hide|leave)\\s+")

    fun stripWakePhrase(text: String): String = WakePhraseParser.stripWakePhrase(text)

    fun resolve(text: String): String? {
        val normalized = normalize(stripWakePhrase(text))
        if (normalized.isEmpty()) return null

        when (normalized) {
            "stop speaking", "stop talking", "be quiet", "silence", "shut up", "mute navis" -> return "STOP_SPEAKING"
            "repeat", "repeat that", "say that again", "repeat your answer", "what did you say", "say it again" -> return "REPEAT_RESPONSE"
            "go back", "back", "previous screen", "return to previous page", "take me back", "close it", "close this", "close that", "close current screen", "close current feature", "exit this screen" -> return "GO_BACK"
            "go home", "home", "open home", "open home screen", "show home screen", "return home", "return to dashboard" -> return "GO_HOME"
            "stop navigation", "end navigation", "stop route guidance", "cancel navigation" -> return "STOP_NAVIGATION"
            "cancel current operation", "cancel the current task", "cancel that", "cancel this operation", "cancel the current action" -> return "CANCEL_OPERATION"
        }

        val closeTarget = closeVerb.replaceFirst(normalized, "").removePrefix("the ").removePrefix("my ")
        if (closeTarget != normalized && isKnownFeature(closeTarget)) {
            return if (closeTarget in setOf("app", "application", "navis")) "GO_HOME" else "GO_BACK"
        }

        val target = openVerb.replaceFirst(normalized, "").removePrefix("the ").removePrefix("my ")
        val hasOpenVerb = target != normalized
        val feature = if (hasOpenVerb) target else normalized

        return when {
            feature in setOf("camera", "live camera", "live assist", "camera view", "obstacle detection") -> "OPEN_CAMERA"
            feature in setOf("scene", "scene description", "surroundings", "describe surroundings", "surrounding view") ||
                feature.startsWith("describe surroundings") || feature.startsWith("describe my surroundings") -> "OPEN_SCENE_DESCRIPTION"
            feature in setOf("text", "read text", "text reader", "ocr", "sign reader", "read sign", "read this sign", "document reader", "read document") ||
                feature.startsWith("read the text") || feature.startsWith("read this document") -> "OPEN_OCR"
            feature in setOf("navigation", "navigate", "maps", "walking directions", "route planner") -> "OPEN_NAVIGATION"
            feature in setOf("settings", "preferences") -> "OPEN_SETTINGS"
            feature in setOf("voice assistant", "assistant", "chat", "voice chat") -> "OPEN_VOICE_ASSISTANT"
            feature in setOf("history", "activity history", "recent activity") -> "OPEN_HISTORY"
            normalized.startsWith("close ") && isKnownFeature(normalized.removePrefix("close ").removePrefix("the ")) -> "GO_BACK"
            else -> null
        }
    }

    private fun isKnownFeature(target: String): Boolean = target in setOf(
        "camera", "live camera", "live assist", "camera view", "scene", "scene description",
        "surroundings", "surrounding view", "describe surroundings", "text", "read text",
        "text reader", "ocr", "sign reader", "read sign", "read this sign", "document reader",
        "read document", "navigation", "maps", "walking directions", "route planner", "settings",
        "preferences", "voice assistant", "assistant", "chat", "voice chat", "history",
        "activity history", "recent activity", "app", "application", "navis"
    )

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
