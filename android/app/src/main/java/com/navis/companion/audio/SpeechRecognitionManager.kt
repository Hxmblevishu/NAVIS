package com.navis.companion.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Android speech recognition for tap-to-talk and Hello NAVIS wake listening. */
class SpeechRecognitionManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit,
    private val onWakeWordStateChange: (Boolean) -> Unit = {},
    private val onWakeCommandPending: (Boolean) -> Unit = {},
    private val onWakePhraseMiss: () -> Unit = {},
    private val onWakeWordHeard: () -> Unit = {}
) : RecognitionListener {

    private val tag = "NavisASR"
    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var wakeWordEnabled = false
    private var manualListening = false
    private var awaitingWakeCommand = false
    private var locale = "en-IN"
    private var restartTask: Runnable? = null
    private var commandTimeoutTask: Runnable? = null

    /** Starts a one-shot user-requested query; wake listening resumes afterward if enabled. */
    fun startListening(locale: String = this.locale) {
        this.locale = locale
        manualListening = true
        awaitingWakeCommand = false
        cancelRestart()
        cancelCommandTimeout()
        destroyRecognizer()
        onWakeCommandPending(false)
        if (wakeWordEnabled) onWakeWordStateChange(false)
        startRecognizer(manual = true)
    }

    /** Continuously re-arms Android recognition while the listener service is active. */
    fun startWakeWordListening(locale: String = this.locale) {
        this.locale = locale
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onWakeWordStateChange(false)
            onError("Speech recognition service is unavailable on this device.")
            return
        }
        if (wakeWordEnabled && (manualListening || isListening || restartTask != null)) return

        wakeWordEnabled = true
        manualListening = false
        awaitingWakeCommand = false
        cancelCommandTimeout()
        onWakeCommandPending(false)
        onWakeWordStateChange(true)
        scheduleWakeRestart(0L)
    }

    /** Stops all microphone capture when wake listening is disabled. */
    fun stopWakeWordListening() {
        val wasWakeWordEnabled = wakeWordEnabled
        wakeWordEnabled = false
        manualListening = false
        awaitingWakeCommand = false
        cancelRestart()
        cancelCommandTimeout()
        destroyRecognizer()
        onWakeCommandPending(false)
        if (wasWakeWordEnabled) onWakeWordStateChange(false)
        onStateChange(false)
    }

    /** Stops a manual listen while preserving the foreground wake-word preference. */
    fun stopListening() {
        manualListening = false
        cancelRestart()
        destroyRecognizer()
        onStateChange(false)
        if (wakeWordEnabled) scheduleWakeRestart(500L)
    }

    private fun startRecognizer(manual: Boolean) {
        if (manual && !manualListening) return
        if (!manual && (!wakeWordEnabled || manualListening)) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            if (manual) {
                manualListening = false
                onStateChange(false)
                onError("Speech recognition service is unavailable on this device.")
            } else {
                wakeWordEnabled = false
                onWakeWordStateChange(false)
                onError("Speech recognition service is unavailable on this device.")
            }
            return
        }

        val recognizer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                Log.i(tag, "Using on-device speech recognition for wake listening")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                Log.i(tag, "Using the Android system speech recognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (error: Exception) {
            handleStartFailure(manual, error)
            return
        }

        speechRecognizer = recognizer
        recognizer.setRecognitionListener(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
        }

        try {
            recognizer.startListening(intent)
            isListening = true
            if (manual) onStateChange(true)
            else onWakeWordStateChange(true)
            Log.i(tag, if (manual) "Manual speech listening started" else "Wake phrase listening started")
        } catch (error: Exception) {
            destroyRecognizer()
            handleStartFailure(manual, error)
        }
    }

    private fun handleStartFailure(manual: Boolean, error: Exception) {
        Log.w(tag, "Could not start speech recognition: ${error.javaClass.simpleName}")
        if (manual) {
            manualListening = false
            onStateChange(false)
            onError("Could not start speech recognition. Check microphone permission and try again.")
            if (wakeWordEnabled) scheduleWakeRestart(900L)
        } else if (wakeWordEnabled) {
            scheduleWakeRestart(1500L)
        }
    }

    private fun scheduleWakeRestart(delayMs: Long) {
        cancelRestart()
        if (!wakeWordEnabled || manualListening) return
        val task = Runnable {
            restartTask = null
            startRecognizer(manual = false)
        }
        restartTask = task
        handler.postDelayed(task, delayMs)
    }

    private fun cancelRestart() {
        restartTask?.let(handler::removeCallbacks)
        restartTask = null
    }

    private fun scheduleCommandTimeout() {
        cancelCommandTimeout()
        val task = Runnable {
            commandTimeoutTask = null
            if (awaitingWakeCommand) {
                awaitingWakeCommand = false
                onWakeCommandPending(false)
                scheduleWakeRestart(0L)
            }
        }
        commandTimeoutTask = task
        handler.postDelayed(task, 8000L)
    }

    private fun cancelCommandTimeout() {
        commandTimeoutTask?.let(handler::removeCallbacks)
        commandTimeoutTask = null
    }

    private fun destroyRecognizer() {
        val recognizer = speechRecognizer
        speechRecognizer = null
        isListening = false
        if (recognizer != null) {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (_: Exception) {
                // The recognizer may already have completed or been destroyed by Android.
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val recognizedText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        val wasManual = manualListening
        isListening = false
        destroyRecognizer()

        if (wasManual) {
            manualListening = false
            onStateChange(false)
            if (recognizedText.isNotBlank()) {
                Log.i(tag, "Speech recognized: $recognizedText")
                onResult(recognizedText)
            } else {
                onError("No clear speech recognized.")
            }
            if (wakeWordEnabled) scheduleWakeRestart(900L)
            return
        }

        if (!wakeWordEnabled) return
        if (recognizedText.isBlank()) {
            scheduleWakeRestart(350L)
            return
        }

        if (awaitingWakeCommand) {
            val nextUtterance = WakePhraseParser.parse(recognizedText)
            val command = if (nextUtterance.detected) nextUtterance.command else recognizedText
            if (command.isBlank()) {
                scheduleWakeRestart(350L)
                return
            }
            awaitingWakeCommand = false
            cancelCommandTimeout()
            onWakeCommandPending(false)
            Log.i(tag, "Wake command recognized: $command")
            onResult(command)
            scheduleWakeRestart(1000L)
            return
        }

        val wakeMatch = WakePhraseParser.parse(recognizedText)
        if (!wakeMatch.detected) {
            Log.d(tag, "Speech was recognized but did not contain the wake phrase")
            onWakePhraseMiss()
            scheduleWakeRestart(350L)
            return
        }

        if (wakeMatch.command.isBlank()) {
            awaitingWakeCommand = true
            onWakeCommandPending(true)
            onWakeWordHeard()
            scheduleCommandTimeout()
            // Let the short spoken acknowledgement finish before reopening capture.
            scheduleWakeRestart(2200L)
            return
        }

        Log.i(tag, "Wake phrase and command recognized: ${wakeMatch.command}")
        onResult(wakeMatch.command)
        scheduleWakeRestart(1000L)
    }

    override fun onError(error: Int) {
        val wasManual = manualListening
        isListening = false
        destroyRecognizer()

        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Say 'Hello NAVIS' and a command."
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection."
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio could not be captured."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
            else -> "Speech recognition error ($error)"
        }

        if (wasManual) {
            manualListening = false
            onStateChange(false)
            onError(message)
            if (wakeWordEnabled) scheduleWakeRestart(900L)
            return
        }

        if (!wakeWordEnabled) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            wakeWordEnabled = false
            awaitingWakeCommand = false
            cancelCommandTimeout()
            onWakeWordStateChange(false)
            onWakeCommandPending(false)
            onError(message)
            return
        }

        if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && awaitingWakeCommand) {
            awaitingWakeCommand = false
            cancelCommandTimeout()
            onWakeCommandPending(false)
        }
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            Log.w(tag, message)
        }
        scheduleWakeRestart(if (error == SpeechRecognizer.ERROR_NETWORK) 1800L else 500L)
    }

    override fun onReadyForSpeech(params: Bundle?) { Log.d(tag, "Speech recognizer ready") }
    override fun onBeginningOfSpeech() { Log.d(tag, "Speech detected") }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { Log.d(tag, "Speech ended") }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
