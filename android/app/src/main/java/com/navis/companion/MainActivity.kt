package com.navis.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.navis.companion.audio.WakeWordService
import com.navis.companion.ui.NavisAppRoot
import com.navis.companion.ui.viewmodel.NavisViewModel

/**
 * Main activity for the NAVIS Android Companion Application.
 * Built with Jetpack Compose for high-contrast accessibility and voice-first interaction.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: NavisViewModel by viewModels()
    private var wakeReceiverRegistered = false
    private val wakeServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WakeWordService.ACTION_WAKE_STATUS -> viewModel.onWakeServiceStatus(
                    listening = intent.getBooleanExtra(WakeWordService.EXTRA_LISTENING, false),
                    awaitingCommand = intent.getBooleanExtra(WakeWordService.EXTRA_AWAITING_COMMAND, false),
                    manualListening = intent.getBooleanExtra(WakeWordService.EXTRA_MANUAL_LISTENING, false)
                )
                WakeWordService.ACTION_WAKE_RESULT -> {
                    WakeWordService.consumePendingCommand(context)?.let(viewModel::onUserSpeechCaptured)
                }
                WakeWordService.ACTION_WAKE_HEARD -> viewModel.onWakeWordHeard()
                WakeWordService.ACTION_WAKE_DISABLED -> viewModel.onWakeServiceStoppedByUser()
                WakeWordService.ACTION_WAKE_ERROR -> viewModel.onWakeServiceError(
                    intent.getStringExtra(WakeWordService.EXTRA_ERROR_MESSAGE)
                        ?: "Speech recognition is unavailable."
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerWakeServiceReceiver()
        checkAndRequestPermissions()

        setContent {
            NavisAppRoot(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setAppVisible(true)
        WakeWordService.consumePendingCommand(this)?.let(viewModel::onUserSpeechCaptured)
    }

    override fun onPause() {
        viewModel.setAppVisible(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (wakeReceiverRegistered) {
            unregisterReceiver(wakeServiceReceiver)
            wakeReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) viewModel.setAppVisible(true)
    }

    /**
     * Physical Volume Key shortcut for blind and visually impaired users:
     * Pressing Volume Down immediately activates speech listening without touching the screen.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            viewModel.startListening()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun registerWakeServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(WakeWordService.ACTION_WAKE_STATUS)
            addAction(WakeWordService.ACTION_WAKE_RESULT)
            addAction(WakeWordService.ACTION_WAKE_HEARD)
            addAction(WakeWordService.ACTION_WAKE_DISABLED)
            addAction(WakeWordService.ACTION_WAKE_ERROR)
        }
        ContextCompat.registerReceiver(this, wakeServiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        wakeReceiverRegistered = true
    }
}
