package com.navis.companion.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * Native Android Text-to-Speech manager for blind and low-vision assistance.
 * Flushes queue immediately for high-priority hazards and supports en-IN and hi-IN.
 */
class TtsManager(
    context: Context,
    private val onReady: ((Boolean) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val tag = "NavisTTS"
    private var isInitialized = false
    private var initializationFailed = false
    private val pendingSpeech = ArrayDeque<PendingSpeech>()

    private data class PendingSpeech(val message: String, val priority: String)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    @Synchronized
    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            initializationFailed = true
            pendingSpeech.clear()
            Log.e(tag, "TextToSpeech initialization failed with code: $status")
            onReady?.invoke(false)
            onError?.invoke("Text-to-speech engine failed to initialize.")
            return
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(1.05f)
        engine.setPitch(1.0f)
        val languageResult = engine.setLanguage(Locale.forLanguageTag("en-IN"))
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(tag, "en-IN voice unavailable; falling back to en-US")
            engine.setLanguage(Locale.US)
        }
        isInitialized = true
        Log.i(tag, "TextToSpeech successfully initialized")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(tag, "TTS speaking started: $utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    Log.d(tag, "TTS speaking finished: $utteranceId")
                }
                override fun onError(utteranceId: String?) {
                    reportError("Text-to-speech could not play the response.")
                }
            })
        onReady?.invoke(true)

        while (pendingSpeech.isNotEmpty()) {
            val pending = pendingSpeech.removeFirst()
            speakNow(pending.message, pending.priority)
        }
    }

    /**
     * Speak alert message with priority handling.
     * @param message Text to speak
     * @param priority "critical" interrupts ongoing speech immediately; "normal" queues.
     */
    @Synchronized
    fun speak(message: String, priority: String = "normal") {
        if (message.isBlank()) return
        if (initializationFailed) {
            reportError("Text-to-speech is unavailable. Check the device's speech engine.")
            return
        }
        if (!isInitialized || tts == null) {
            // TTS initializes asynchronously. Queue requests so early assistant
            // responses and the welcome greeting are not silently discarded.
            pendingSpeech.addLast(PendingSpeech(message, priority))
            Log.i(tag, "Queued speech until TextToSpeech is ready")
            return
        }
        speakNow(message, priority)
    }

    private fun speakNow(message: String, priority: String) {
        val engine = tts ?: return
        // Auto-switch to Hindi voice if text contains Devanagari characters
        val containsHindi = message.any { it in '\u0900'..'\u097F' }
        val requestedLocale = if (containsHindi) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
        val languageResult = engine.setLanguage(requestedLocale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallbackLocale = Locale.US
            Log.w(tag, "Requested voice unavailable; falling back to ${fallbackLocale.toLanguageTag()}")
            engine.setLanguage(fallbackLocale)
        }

        val queueMode = if (priority == "critical" || priority == "high") {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        val utteranceId = "navis_${System.currentTimeMillis()}"
        if (engine.speak(message, queueMode, null, utteranceId) == TextToSpeech.ERROR) {
            reportError("Text-to-speech could not start playback.")
        }
    }

    private fun reportError(message: String) {
        Log.e(tag, message)
        onError?.invoke(message)
    }

    @Synchronized
    fun stop() {
        pendingSpeech.clear()
        tts?.stop()
    }

    @Synchronized
    fun shutdown() {
        pendingSpeech.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        initializationFailed = true
    }
}
