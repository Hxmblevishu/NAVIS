package com.navis.companion.commands

import com.navis.companion.network.HybridVoiceCommand

/** Executes only recognized, allow-listed actions through existing Android features. */
class NavisActionDispatcher(
    private val openCamera: () -> Unit,
    private val startDetection: () -> Unit,
    private val openSceneDescription: () -> Unit,
    private val openNavigation: (String?) -> Unit,
    private val openOcr: () -> Unit,
    private val openVoiceAssistant: () -> Unit,
    private val openHistory: () -> Unit,
    private val goHome: () -> Unit,
    private val goBack: () -> Unit,
    private val openSettings: () -> Unit,
    private val stopSpeaking: () -> Unit,
    private val repeatResponse: () -> Unit,
    private val stopNavigation: () -> Unit,
    private val cancelOperation: () -> Unit
) {
    fun execute(command: HybridVoiceCommand): Boolean {
        if (!command.success) return false
        when (command.action) {
            "OPEN_CAMERA" -> openCamera()
            "START_DETECTION" -> startDetection()
            "OPEN_SCENE_DESCRIPTION" -> openSceneDescription()
            "OPEN_NAVIGATION" -> openNavigation(command.parameters?.get("destination") as? String)
            "OPEN_OCR" -> openOcr()
            "OPEN_VOICE_ASSISTANT" -> openVoiceAssistant()
            "OPEN_HISTORY" -> openHistory()
            "GO_HOME" -> goHome()
            "GO_BACK" -> goBack()
            "STOP_SPEAKING" -> stopSpeaking()
            "REPEAT_RESPONSE" -> repeatResponse()
            "OPEN_SETTINGS" -> openSettings()
            "STOP_NAVIGATION" -> stopNavigation()
            "CANCEL_OPERATION" -> cancelOperation()
            "CHECK_STATUS" -> Unit // The backend already supplied the status message.
            else -> return false
        }
        return true
    }
}
