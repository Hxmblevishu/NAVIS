package com.navis.companion.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.navis.companion.BuildConfig
import com.navis.companion.audio.HapticManager
import com.navis.companion.audio.TtsManager
import com.navis.companion.audio.WakeWordService
import com.navis.companion.commands.NavisLocalVoiceControl
import com.navis.companion.network.HybridVoiceCommand
import com.navis.companion.network.LocationCoordinate
import com.navis.companion.network.NavigationRouteResponse
import com.navis.companion.network.NavisApiClient
import com.navis.companion.network.NavisWebSocketManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val time: String,
    val routePreview: RoutePreview? = null
)

data class RoutePreview(val destination: String, val distance: String, val duration: String)

data class DetectedObject(
    val id: String,
    val label: String,
    val distanceM: Float?,
    val position: String,
    val priority: String,
    val distanceStatus: String = "unassociated"
)

data class HistoryItem(
    val id: String,
    val type: String,
    val title: String,
    val detail: String,
    val time: String
)

data class NavisUiState(
    val isBackendConnected: Boolean = false,
    val isEventStreamConnected: Boolean = false,
    val voiceOutputAvailable: Boolean? = null,
    val isCameraActive: Boolean = false,
    val cameraPreview: Bitmap? = null,
    val cameraPreviewMessage: String = "Waiting for a camera frame…",
    val distanceSensorActive: Boolean = false,
    val distanceSensorMode: String = "auto",
    val currentDistanceM: Float? = null,
    val isAiOnline: Boolean = false,
    val isGpsReady: Boolean = false,
    val serverUrl: String = "http://172.20.52.27:8000",
    val apiToken: String = "",
    val backendStatus: String = "Connecting to NAVIS backend…",
    val latestHazardMessage: String = "No live hazard alert received.",
    val latestHazardPriority: String = "low",

    val voiceState: VoiceState = VoiceState.IDLE,
    val userSpeechInput: String = "",
    val assistantSpokenText: String = "Connect to the NAVIS backend to begin.",
    val conversation: List<ChatMessage> = emptyList(),
    val responseStyle: String = "auto",
    val wakeWordEnabled: Boolean = true,
    val isWakeWordListening: Boolean = false,
    val awaitingWakeCommand: Boolean = false,

    val detectedObjects: List<DetectedObject> = emptyList(),
    val perceptionMessage: String = "Waiting for live camera data.",
    val sceneDescription: String = "No scene has been analyzed yet.",
    val detectedOcrText: String = "No text has been read yet.",
    val ocrStatus: String = "not_requested",

    val navigationActive: Boolean = false,
    val navigationPaused: Boolean = false,
    val destinationName: String = "",
    val navigationRouteId: String? = null,
    val navigationPolyline: String? = null,
    val currentLocation: LocationCoordinate? = null,
    val mapLocationMessage: String = "Showing Dehradun, Uttarakhand.",
    val navigationStatusMessage: String = "No pedestrian route has been requested.",
    val routeAttribution: String? = null,
    val remainingDistance: String = "—",
    val remainingTime: String = "—",
    val nextTurnInstruction: String = "No active route",
    val nextTurnDistance: String = "Request a route to begin",

    val isDarkTheme: Boolean = false,
    val textSizeMultiplier: Float = 1.0f,
    val voiceSpeed: Float = 1.0f,
    val volumeLevel: Float = 0.85f,
    val alertStyleVoice: Boolean = true,
    val preferredLanguage: String = "English (India)",
    val distanceUnit: String = "Metres",
    val walkingMode: String = "Walking",

    val historyItems: List<HistoryItem> = emptyList()
)

class NavisViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("navis_connection", Application.MODE_PRIVATE)
    private val initialServerUrl = preferences.getString("server_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000"
    private val savedApiToken = preferences.getString("api_token", "") ?: ""
    private val initialApiToken = if (preferences.getBoolean("api_token_user_override", false)) {
        savedApiToken
    } else {
        BuildConfig.NAVIS_API_TOKEN.ifBlank { savedApiToken }
    }
    private val initialResponseStyle = preferences.getString("response_style", "auto")
        ?.takeIf { it in setOf("auto", "quick", "detailed") } ?: "auto"
    private val initialWakeWordEnabled = preferences.getBoolean("wake_word_enabled", true)
    private val _uiState = MutableStateFlow(
        NavisUiState(
            serverUrl = initialServerUrl,
            apiToken = initialApiToken,
            responseStyle = initialResponseStyle,
            wakeWordEnabled = initialWakeWordEnabled
        )
    )
    val uiState: StateFlow<NavisUiState> = _uiState.asStateFlow()
    private val _voiceActionEvents = MutableSharedFlow<HybridVoiceCommand>(extraBufferCapacity = 1)
    val voiceActionEvents = _voiceActionEvents.asSharedFlow()

    private val ttsManager = TtsManager(
        context = application,
        onReady = { ready -> _uiState.update { it.copy(voiceOutputAvailable = ready) } },
        onError = { _uiState.update { it.copy(voiceOutputAvailable = false) } }
    )
    private val hapticManager = HapticManager(application)
    private val locationClient = LocationServices.getFusedLocationProviderClient(application)
    private val gson = Gson()
    private var apiClient = NavisApiClient(initialServerUrl, initialApiToken)
    private var webSocketManager: NavisWebSocketManager? = null
    private var navigationJob: Job? = null
    private var voiceCommandJob: Job? = null
    private var activeRoute: NavigationRouteResponse? = null
    private var lastLocation: LocationCoordinate? = null
    private var appVisible = false
    private var wakeGreetingJob: Job? = null

    init {
        connectWebSocket()
        startBackendSync()
    }

    /** Keep wake-word capture limited to the visible app session. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) {
            WakeWordService.stop(getApplication())
            wakeGreetingJob?.cancel()
            _uiState.update {
                it.copy(
                    isWakeWordListening = false,
                    awaitingWakeCommand = false,
                    voiceState = if (it.voiceState == VoiceState.LISTENING) VoiceState.IDLE else it.voiceState
                )
            }
            return
        }

        if (_uiState.value.wakeWordEnabled && hasRecordAudioPermission()) {
            WakeWordService.start(getApplication())
        } else {
            WakeWordService.stop(getApplication())
            _uiState.update { it.copy(isWakeWordListening = false, awaitingWakeCommand = false) }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("wake_word_enabled", enabled).apply()
        _uiState.update { it.copy(wakeWordEnabled = enabled, awaitingWakeCommand = false) }
        if (!enabled) {
            WakeWordService.stop(getApplication())
            _uiState.update { it.copy(isWakeWordListening = false, awaitingWakeCommand = false) }
        } else if (appVisible && hasRecordAudioPermission()) {
            WakeWordService.start(getApplication())
        }
    }

    fun onWakeServiceStatus(listening: Boolean, awaitingCommand: Boolean, manualListening: Boolean) {
        _uiState.update { current ->
            current.copy(
                isWakeWordListening = listening,
                awaitingWakeCommand = awaitingCommand,
                voiceState = when {
                    manualListening -> VoiceState.LISTENING
                    current.voiceState == VoiceState.LISTENING -> VoiceState.IDLE
                    else -> current.voiceState
                }
            )
        }
    }

    fun onWakeServiceError(message: String) {
        _uiState.update { it.copy(voiceState = VoiceState.ERROR, assistantSpokenText = message) }
        speakText(message, "caution")
    }

    fun onWakeWordHeard() {
        if (!appVisible) return
        val greeting = "I'm here. What can I help you with?"
        wakeGreetingJob?.cancel()
        _uiState.update { it.copy(assistantSpokenText = greeting, voiceState = VoiceState.SPEAKING) }
        hapticManager.trigger("normal")
        ttsManager.speak(greeting, "high")
        wakeGreetingJob = viewModelScope.launch {
            delay(2200)
            if (_uiState.value.voiceState == VoiceState.SPEAKING) {
                _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
            }
        }
    }

    fun onWakeServiceStoppedByUser() {
        preferences.edit().putBoolean("wake_word_enabled", false).apply()
        _uiState.update {
            it.copy(wakeWordEnabled = false, isWakeWordListening = false, awaitingWakeCommand = false)
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun connectWebSocket() {
        webSocketManager?.disconnect()
        val current = _uiState.value
        webSocketManager = NavisWebSocketManager(
            serverBaseUrl = current.serverUrl,
            apiToken = current.apiToken,
            onHazardReceived = { hazard ->
                _uiState.update {
                    it.copy(
                        latestHazardMessage = hazard.message,
                        latestHazardPriority = hazard.priority,
                        historyItems = (listOf(
                            HistoryItem(
                                id = hazard.eventId,
                                type = "detection",
                                title = "Hazard alert",
                                detail = hazard.message,
                                time = "Now"
                            )
                        ) + it.historyItems).take(50)
                    )
                }
                hapticManager.trigger(hazard.priority)
                ttsManager.speak(hazard.message, hazard.priority)
            },
            onStatusReceived = { status ->
                _uiState.update {
                    it.copy(backendStatus = "${status.component}: ${status.state}")
                }
            },
            onConnectionChanged = { connected ->
                _uiState.update { it.copy(isEventStreamConnected = connected) }
            }
        )
        webSocketManager?.connect()
    }

    private fun startBackendSync() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val healthTask = async { apiClient.getHealth() }
                    val deviceTask = async { apiClient.getDeviceStatus() }
                    val readingTask = async { apiClient.getDistanceReading() }
                    val perceptionTask = async { apiClient.getLatestPerception() }
                    val health = healthTask.await()
                    val device = deviceTask.await()
                    val reading = readingTask.await()
                    val perception = perceptionTask.await()
                    if (health != null || device != null) {
                        _uiState.update { old ->
                            val gpsPermission = hasLocationPermission()
                            old.copy(
                                isBackendConnected = health != null,
                                isCameraActive = device?.cameraConnected ?: health?.cameraConnected ?: false,
                                distanceSensorActive = reading?.valid ?: device?.distanceSensorConnected ?: false,
                                distanceSensorMode = health?.distanceSensorMode
                                    ?: device?.distanceSensorMode
                                    ?: old.distanceSensorMode,
                                currentDistanceM = reading?.distanceM.takeIf { reading?.valid == true },
                                isAiOnline = health?.voiceAssistantConfigured ?: health?.modelLoaded ?: false,
                                isGpsReady = gpsPermission,
                                backendStatus = health?.status?.replaceFirstChar { it.uppercase(Locale.getDefault()) }
                                    ?: device?.operationalState?.replaceFirstChar { it.uppercase(Locale.getDefault()) }
                                    ?: "Backend unavailable"
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isBackendConnected = false, backendStatus = "Backend unavailable") }
                    }
                    if (perception != null) applyPerception(perception)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _uiState.update { it.copy(isBackendConnected = false, backendStatus = "Backend unavailable") }
                }
                delay(4000)
            }
        }
    }

    private fun applyPerception(response: com.navis.companion.network.LatestPerceptionResponse) {
        val fused = response.fusion.associateBy { it.objectLabel.lowercase() to it.imagePosition }
        val objects = response.detections.mapIndexed { index, item ->
            val evidence = fused[item.label.lowercase() to item.position]
            val distance = evidence?.distanceM.takeIf { evidence?.distanceStatus == "associated" }
            val priority = when {
                distance != null && distance <= 0.8f -> "critical"
                distance != null && distance <= 1.2f -> "caution"
                item.position == "center" -> "caution"
                else -> "normal"
            }
            DetectedObject(
                id = "${response.frameId ?: "frame"}_$index",
                label = item.label,
                distanceM = distance,
                position = item.position,
                priority = priority,
                distanceStatus = evidence?.distanceStatus ?: "unassociated"
            )
        }
        val reading = response.distanceReading
        _uiState.update {
            it.copy(
                detectedObjects = objects,
                perceptionMessage = response.message
                    ?: if (response.status == "live") "Latest camera results. Camera detections do not verify a clear path." else "Waiting for live camera data.",
                currentDistanceM = reading?.distanceM.takeIf { reading?.valid == true },
                distanceSensorActive = reading?.valid == true
            )
        }
    }

    fun refreshPerception() {
        viewModelScope.launch {
            apiClient.getLatestPerception()?.let(::applyPerception)
        }
    }

    suspend fun refreshCameraSnapshot() {
        val bytes = apiClient.getCameraSnapshot()
        val bitmap = if (bytes != null) {
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } else null
        _uiState.update { state ->
            state.copy(
                cameraPreview = bitmap,
                cameraPreviewMessage = if (bitmap != null) "Live camera • updated just now" else
                    "No camera frame yet. Check that the ESP32-CAM is powered on and connected to Wi-Fi."
            )
        }
    }

    fun startListening() {
        hapticManager.trigger("confirmation")
        if (!hasRecordAudioPermission()) {
            onWakeServiceError("Microphone permission is required for voice input.")
            return
        }
        _uiState.update { it.copy(voiceState = VoiceState.LISTENING) }
        try {
            WakeWordService.startManualListening(getApplication())
        } catch (_: Exception) {
            _uiState.update { it.copy(voiceState = VoiceState.ERROR) }
            speakText("Speech recognition is unavailable on this device.", "normal")
        }
    }

    fun stopListening() {
        WakeWordService.stopManualListening(getApplication())
        _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
    }

    fun onUserSpeechCaptured(transcript: String) {
        val text = NavisLocalVoiceControl.stripWakePhrase(transcript).trim()
        if (text.isEmpty()) return
        voiceCommandJob?.cancel()
        voiceCommandJob = null
        ttsManager.stop()
        _uiState.update { it.copy(userSpeechInput = text, voiceState = VoiceState.PROCESSING) }

        val localAction = NavisLocalVoiceControl.resolve(text)
        when (localAction) {
            "STOP_SPEAKING" -> {
                stopSpeaking()
                return
            }
            "REPEAT_RESPONSE" -> {
                repeatResponse()
                return
            }
            "GO_BACK" -> {
                emitLocalVoiceAction("GO_BACK", "Going back.")
                return
            }
            "STOP_NAVIGATION" -> {
                emitLocalVoiceAction("STOP_NAVIGATION", "")
                return
            }
            "CANCEL_OPERATION" -> {
                emitLocalVoiceAction("CANCEL_OPERATION", "")
                return
            }
            "OPEN_CAMERA", "OPEN_SCENE_DESCRIPTION", "OPEN_OCR", "OPEN_NAVIGATION",
            "OPEN_SETTINGS", "OPEN_VOICE_ASSISTANT", "OPEN_HISTORY", "GO_HOME" -> {
                emitLocalVoiceAction(localAction, localActionMessage(localAction))
                return
            }
        }

        voiceCommandJob?.cancel()
        voiceCommandJob = viewModelScope.launch {
            val hybridCommand = apiClient.resolveVoiceCommand(text)
            if (hybridCommand == null) {
                publishVoiceResponse(
                    text,
                    backendVoiceFailureMessage(),
                    "caution"
                )
                return@launch
            }

            if (hybridCommand.success && hybridCommand.action != "NONE") {
                when (hybridCommand.action) {
                    "STOP_SPEAKING" -> {
                        stopSpeaking()
                        return@launch
                    }
                    "REPEAT_RESPONSE" -> {
                        repeatResponse()
                        return@launch
                    }
                    "STOP_NAVIGATION" -> {
                        _voiceActionEvents.emit(hybridCommand)
                        return@launch
                    }
                    "CANCEL_OPERATION" -> {
                        _voiceActionEvents.emit(hybridCommand)
                        return@launch
                    }
                }
                val destination = hybridCommand.parameters?.get("destination") as? String
                if (!destination.isNullOrBlank() && hybridCommand.action == "OPEN_NAVIGATION") {
                    setNavigationDestination(destination)
                }
                val message = if (hybridCommand.requiresConfirmation) {
                    "Please confirm before I start object detection."
                } else {
                    hybridCommand.message
                }
                publishVoiceResponse(text, message, if (hybridCommand.requiresConfirmation) "caution" else "normal")
                _voiceActionEvents.emit(hybridCommand)
                return@launch
            }

            val conversationBeforeReply = _uiState.value.conversation
            val history = conversationBeforeReply.takeLast(8).map { message ->
                mapOf(
                    "role" to if (message.sender == "user") "user" else "assistant",
                    "text" to message.text
                )
            }
            val now = System.currentTimeMillis()
            val userMessageId = "user_$now"
            val assistantMessageId = "navis_${now + 1}"
            _uiState.update { current ->
                current.copy(
                    voiceState = VoiceState.PROCESSING,
                    conversation = current.conversation + listOf(
                        ChatMessage(userMessageId, "user", text, "Now"),
                        ChatMessage(assistantMessageId, "navis", "Thinking…", "Now")
                    )
                )
            }

            val answer = StringBuilder()
            val pendingSpeech = StringBuilder()
            var queuedSpeech = false
            var firstSpeechAt: Long? = null
            val streamResult = apiClient.streamVoiceAssistantQuery(
                question = text,
                responseStyle = _uiState.value.responseStyle,
                history = history
            ) { delta ->
                if (delta.isNotBlank()) {
                    answer.append(delta)
                    pendingSpeech.append(delta)
                    val partialAnswer = answer.toString()
                    _uiState.update { current ->
                        current.copy(
                            assistantSpokenText = partialAnswer,
                            voiceState = if (queuedSpeech) VoiceState.SPEAKING else VoiceState.PROCESSING,
                            conversation = current.conversation.map { message ->
                                if (message.id == assistantMessageId) message.copy(text = partialAnswer)
                                else message
                            }
                        )
                    }
                    drainSpeechBuffer(pendingSpeech, flush = false).forEach { sentence ->
                        if (!queuedSpeech) {
                            firstSpeechAt = System.currentTimeMillis()
                            hapticManager.trigger("normal")
                        }
                        queuedSpeech = true
                        ttsManager.speak(sentence, "normal")
                    }
                    if (queuedSpeech) _uiState.update { it.copy(voiceState = VoiceState.SPEAKING) }
                }
            }

            val streamError = when {
                streamResult.completed && answer.isNotBlank() -> null
                streamResult.completed -> "Gemini returned an empty answer. Please try again."
                !streamResult.errorMessage.isNullOrBlank() -> streamResult.errorMessage
                else -> backendVoiceFailureMessage()
            }
            val finalAnswer = when {
                streamError == null -> answer.toString().trim()
                answer.isBlank() -> streamError
                else -> "${answer.toString().trim()}\n\n$streamError"
            }

            drainSpeechBuffer(pendingSpeech, flush = true).forEach { sentence ->
                if (!queuedSpeech) {
                    firstSpeechAt = System.currentTimeMillis()
                    hapticManager.trigger(if (streamError == null) "normal" else "caution")
                }
                queuedSpeech = true
                ttsManager.speak(sentence, "normal")
            }
            if (streamError != null) {
                if (!queuedSpeech) hapticManager.trigger("caution")
                queuedSpeech = true
                ttsManager.speak(streamError, "normal")
            }

            _uiState.update { current ->
                current.copy(
                    assistantSpokenText = finalAnswer,
                    voiceState = if (queuedSpeech) VoiceState.SPEAKING else VoiceState.IDLE,
                    conversation = current.conversation.map { message ->
                        if (message.id == assistantMessageId) message.copy(text = finalAnswer)
                        else message
                    },
                    historyItems = (listOf(
                        HistoryItem("voice_$now", "voice", "Voice request", text, "Now")
                    ) + current.historyItems).take(50)
                )
            }

            if (queuedSpeech) {
                val expectedSpeechMs = (finalAnswer.length * 65L).coerceIn(1500L, 90_000L)
                val elapsedSinceSpeechStarted = firstSpeechAt?.let { System.currentTimeMillis() - it } ?: 0L
                val remainingSpeechMs = (expectedSpeechMs - elapsedSinceSpeechStarted).coerceAtLeast(0L)
                if (remainingSpeechMs > 0) delay(remainingSpeechMs)
                if (_uiState.value.voiceState == VoiceState.SPEAKING) {
                    _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
                }
            }
        }
    }

    private fun drainSpeechBuffer(buffer: StringBuilder, flush: Boolean): List<String> {
        val ready = mutableListOf<String>()
        var index = 0
        while (index < buffer.length) {
            val character = buffer[index]
            val endsSentence = character == '\n' ||
                (character in ".!?" && (index == buffer.lastIndex || buffer[index + 1].isWhitespace()))
            if (endsSentence) {
                val end = index + 1
                buffer.substring(0, end).trim().takeIf { it.isNotEmpty() }?.let(ready::add)
                buffer.delete(0, end)
                while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
                index = 0
            } else {
                index += 1
            }
        }
        if (flush && buffer.isNotEmpty()) {
            buffer.toString().trim().takeIf { it.isNotEmpty() }?.let(ready::add)
            buffer.clear()
        } else if (buffer.length > 220) {
            val split = buffer.lastIndexOf(" ", 180)
            if (split > 0) {
                buffer.substring(0, split).trim().takeIf { it.isNotEmpty() }?.let(ready::add)
                buffer.delete(0, split + 1)
            }
        }
        return ready
    }

    private suspend fun backendVoiceFailureMessage(): String =
        if (apiClient.getHealth() != null) {
            "I can reach the NAVIS backend, but the voice response failed. Please try again."
        } else {
            "I couldn't reach the NAVIS backend. Check the server address and network, then try again."
        }

    private suspend fun publishVoiceResponse(question: String, answer: String, priority: String) {
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage("user_$now", "user", question, "Now")
        val responseMessage = ChatMessage("navis_${now + 1}", "navis", answer, "Now")
        _uiState.update {
            it.copy(
                assistantSpokenText = answer,
                voiceState = VoiceState.SPEAKING,
                conversation = it.conversation + listOf(userMessage, responseMessage),
                historyItems = (listOf(
                    HistoryItem("voice_$now", "voice", "Voice request", question, "Now")
                ) + it.historyItems).take(50)
            )
        }
        hapticManager.trigger(priority)
        ttsManager.speak(answer, priority)
        delay(3500)
        if (_uiState.value.voiceState == VoiceState.SPEAKING) {
            _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
        }
    }

    private fun emitLocalVoiceAction(action: String, message: String) {
        val command = HybridVoiceCommand(
            success = true,
            intent = action,
            action = action,
            confidence = 1.0,
            method = "local",
            message = message,
            requiresConfirmation = false,
            parameters = emptyMap()
        )
        _uiState.update {
            it.copy(
                assistantSpokenText = message.ifBlank { it.assistantSpokenText },
                voiceState = VoiceState.IDLE
            )
        }
        viewModelScope.launch {
            _voiceActionEvents.emit(command)
            if (message.isNotBlank()) speakText(message)
        }
    }

    private fun localActionMessage(action: String): String = when (action) {
        "OPEN_CAMERA" -> "Opening live assist."
        "OPEN_SCENE_DESCRIPTION" -> "Opening scene description."
        "OPEN_OCR" -> "Opening the text reader."
        "OPEN_NAVIGATION" -> "Opening navigation."
        "OPEN_SETTINGS" -> "Opening settings."
        "OPEN_VOICE_ASSISTANT" -> "Opening voice assistant."
        "OPEN_HISTORY" -> "Opening activity history."
        "GO_HOME" -> "Going home."
        else -> ""
    }

    fun repeatResponse() {
        val previous = _uiState.value.assistantSpokenText
        if (previous.isNotBlank()) {
            _uiState.update { it.copy(voiceState = VoiceState.SPEAKING) }
            hapticManager.trigger("normal")
            ttsManager.speak(previous, "high")
            viewModelScope.launch {
                delay(3500)
                if (_uiState.value.voiceState == VoiceState.SPEAKING) {
                    _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
                }
            }
        }
    }

    fun cancelCurrentOperation() {
        val wasNavigating = _uiState.value.navigationActive
        voiceCommandJob?.cancel()
        voiceCommandJob = null
        ttsManager.stop()
        if (wasNavigating) {
            stopNavigation()
        } else {
            speakText("Cancelled.")
        }
    }

    fun describeSurroundings(prompt: String = "Describe my surroundings concisely.") {
        viewModelScope.launch {
            _uiState.update { it.copy(perceptionMessage = "Analyzing the latest camera frame…") }
            val result = apiClient.analyzeCurrentScene(prompt)
            if (result == null) {
                _uiState.update { it.copy(sceneDescription = "Scene analysis is unavailable. Check the backend and camera connection.") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    sceneDescription = "${result.description}\n\n${result.uncertainty}",
                    historyItems = (listOf(
                        HistoryItem("scene_${System.currentTimeMillis()}", "voice", "Scene description", result.description, "Now")
                    ) + it.historyItems).take(50)
                )
            }
        }
    }

    fun readTextFromCamera() {
        viewModelScope.launch {
            _uiState.update { it.copy(ocrStatus = "processing", detectedOcrText = "Reading the latest camera frame…") }
            val result = apiClient.readTextFromCamera()
            if (result == null) {
                _uiState.update { it.copy(ocrStatus = "unavailable", detectedOcrText = "Text reading is unavailable. Check the backend and camera connection.") }
                return@launch
            }
            val displayText = when (result.status) {
                "success" -> result.text
                "no_text_found" -> "No readable text was found in this frame."
                "blurred" -> "The image is too blurry to read. Hold steady and try again."
                else -> "Text reading is unavailable on this system."
            }
            _uiState.update {
                it.copy(
                    ocrStatus = result.status,
                    detectedOcrText = displayText,
                    historyItems = if (result.status == "success") (listOf(
                        HistoryItem("ocr_${System.currentTimeMillis()}", "ocr", "Text read", result.text, "Now")
                    ) + it.historyItems).take(50) else it.historyItems
                )
            }
        }
    }

    fun speakText(text: String, priority: String = "normal") {
        if (text.isNotBlank()) {
            hapticManager.trigger(priority)
            ttsManager.speak(text, priority)
        }
    }

    fun stopSpeaking() {
        voiceCommandJob?.cancel()
        voiceCommandJob = null
        ttsManager.stop()
        _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
    }

    fun setNavigationDestination(destination: String) {
        _uiState.update { it.copy(destinationName = destination.trim()) }
    }

    fun startNavigation(destination: String = _uiState.value.destinationName) {
        val target = destination.trim()
        if (target.length < 2) {
            val message = "Enter a destination before requesting a route."
            _uiState.update { it.copy(navigationStatusMessage = message, assistantSpokenText = message) }
            speakText(message, "normal")
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    navigationStatusMessage = "Getting the phone's current location…",
                    destinationName = target,
                    navigationActive = false
                )
            }
            val location = getPhoneLocation()
            if (location == null) {
                val message = "Phone location is unavailable. Grant location permission and try again. No route has been generated."
                _uiState.update { it.copy(isGpsReady = false, navigationStatusMessage = message, assistantSpokenText = message) }
                speakText(message, "caution")
                return@launch
            }

            val route = apiClient.calculateRoute(target, location)
            if (route == null) {
                val message = "The NAVIS backend could not be reached. No route has been generated."
                _uiState.update { it.copy(navigationStatusMessage = message, assistantSpokenText = message) }
                speakText(message, "caution")
                return@launch
            }
            if (route.status != "active") {
                _uiState.update {
                    it.copy(
                        isGpsReady = true,
                        navigationStatusMessage = route.message,
                        assistantSpokenText = route.message,
                        navigationRouteId = null,
                        navigationActive = false
                    )
                }
                speakText(route.message, "caution")
                return@launch
            }

            lastLocation = location
            _uiState.update { it.copy(currentLocation = location, isGpsReady = true) }
            applyRoute(route, activate = true)
            hapticManager.trigger("confirmation")
            ttsManager.speak(route.message, "normal")
            startProgressUpdates(route.routeId)
        }
    }

    private fun applyRoute(route: NavigationRouteResponse, activate: Boolean) {
        activeRoute = route
        val distance = route.distanceRemainingM
        _uiState.update {
            it.copy(
                destinationName = route.destination,
                navigationRouteId = route.routeId,
                navigationPolyline = route.overviewPolyline,
                navigationActive = activate,
                navigationPaused = false,
                navigationStatusMessage = route.message,
                routeAttribution = route.attribution,
                remainingDistance = formatDistance(distance),
                remainingTime = formatDuration(route.etaS),
                nextTurnInstruction = route.nextManeuver ?: "No maneuver available",
                nextTurnDistance = route.maneuvers.firstOrNull()?.let { maneuver -> "In ${formatDistance(maneuver.distanceM)}" } ?: "No maneuver available"
            )
        }
    }

    private fun startProgressUpdates(routeId: String) {
        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            var lastInstruction = _uiState.value.nextTurnInstruction
            while (isActive && _uiState.value.navigationActive && _uiState.value.navigationRouteId == routeId) {
                if (_uiState.value.navigationPaused) {
                    delay(1000)
                    continue
                }
                delay(5000)
                val location = getPhoneLocation() ?: continue
                lastLocation = location
                val progress = apiClient.updateNavigationProgress(routeId, location) ?: continue
                _uiState.update {
                    it.copy(
                        isGpsReady = true,
                        currentLocation = location,
                        remainingDistance = formatDistance(progress.distanceRemainingM),
                        remainingTime = formatDuration(progress.etaS),
                        nextTurnInstruction = progress.nextManeuver,
                        navigationStatusMessage = progress.message,
                        navigationActive = progress.status == "in_progress"
                    )
                }
                if (progress.nextManeuver != lastInstruction || progress.status != "in_progress") {
                    ttsManager.speak(progress.message, if (progress.status == "off_route") "caution" else "normal")
                    lastInstruction = progress.nextManeuver
                }
                if (progress.status != "in_progress") break
            }
        }
    }

    fun stopNavigation() {
        navigationJob?.cancel()
        activeRoute = null
        _uiState.update {
            it.copy(
                navigationActive = false,
                navigationPaused = false,
                navigationRouteId = null,
                navigationPolyline = null,
                navigationStatusMessage = "Navigation stopped.",
                nextTurnInstruction = "No active route",
                nextTurnDistance = "Request a route to begin"
            )
        }
        hapticManager.trigger("confirmation")
        ttsManager.speak("Navigation stopped.", "normal")
    }

    private fun hasLocationPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun getPhoneLocation(): LocationCoordinate? = suspendCancellableCoroutine { continuation ->
        if (!hasLocationPermission()) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val cancellation = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        val coordinate = location?.let {
                            LocationCoordinate(it.latitude, it.longitude, it.accuracy.takeIf { accuracy -> accuracy >= 0f })
                        }
                        continuation.resume(coordinate)
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        } catch (_: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun formatDistance(metres: Number?): String {
        if (metres == null) return "—"
        val value = metres.toDouble()
        return if (value >= 1000.0) String.format(Locale.getDefault(), "%.1f km", value / 1000.0)
        else "${value.roundToInt()} m"
    }

    private fun formatDuration(seconds: Int?): String {
        if (seconds == null) return "—"
        val minutes = (seconds / 60.0).roundToInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
    }

    fun updateServerUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            _uiState.update { it.copy(backendStatus = "Server URL must start with http:// or https://") }
            return
        }
        preferences.edit().putString("server_url", normalized).apply()
        _uiState.update { it.copy(serverUrl = normalized, isBackendConnected = false, backendStatus = "Connecting…") }
        apiClient = NavisApiClient(normalized, _uiState.value.apiToken)
        connectWebSocket()
    }

    fun updateApiToken(token: String) {
        val normalized = token.trim().ifBlank { BuildConfig.NAVIS_API_TOKEN }
        preferences.edit()
            .putString("api_token", normalized)
            .putBoolean("api_token_user_override", true)
            .apply()
        _uiState.update { it.copy(apiToken = normalized, isBackendConnected = false, backendStatus = "Connecting…") }
        apiClient = NavisApiClient(_uiState.value.serverUrl, normalized)
        connectWebSocket()
    }

    fun setResponseStyle(style: String) {
        val normalized = style.trim().lowercase(Locale.ROOT)
        if (normalized !in setOf("auto", "quick", "detailed")) return
        preferences.edit().putString("response_style", normalized).apply()
        _uiState.update { it.copy(responseStyle = normalized) }
    }

    fun reportLocationPermissionDenied() {
        _uiState.update { it.copy(isGpsReady = false, navigationStatusMessage = "Location permission is needed to calculate a route.") }
    }

    fun setNavigationPaused(paused: Boolean) {
        _uiState.update { it.copy(navigationPaused = paused) }
        ttsManager.speak(if (paused) "Navigation updates paused." else "Navigation updates resumed.", "normal")
    }

    fun setThemeMode(isDark: Boolean) { _uiState.update { it.copy(isDarkTheme = isDark) } }
    fun setTextSizeMultiplier(mult: Float) { _uiState.update { it.copy(textSizeMultiplier = mult) } }
    fun setVoiceSpeed(speed: Float) { _uiState.update { it.copy(voiceSpeed = speed) } }
    fun setUnits(unit: String) { _uiState.update { it.copy(distanceUnit = unit) } }

    override fun onCleared() {
        navigationJob?.cancel()
        ttsManager.shutdown()
        webSocketManager?.disconnect()
        super.onCleared()
    }
}
