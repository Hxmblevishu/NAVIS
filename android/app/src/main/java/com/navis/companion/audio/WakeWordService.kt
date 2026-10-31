package com.navis.companion.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.navis.companion.MainActivity

/** Owns the single speech recognizer used for wake-word and tap-to-talk input. */
class WakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var speechManager: SpeechRecognitionManager? = null
    private var started = false
    private var manualOnly = false
    private var wakeListening = false
    private var awaitingCommand = false
    private var manualListening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(KEY_WAKE_ENABLED, false)
                    .commit()
                sendBroadcast(Intent(ACTION_WAKE_DISABLED).setPackage(packageName))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_MANUAL -> {
                speechManager?.stopListening()
                if (speechManager == null || manualOnly) stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Speech listener not started because microphone permission is missing")
            sendStatus(listening = false, awaiting = false, manual = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Say ‘Hello NAVIS’ to speak."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Say ‘Hello NAVIS’ to speak."))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not start microphone foreground service", error)
            sendStatus(listening = false, awaiting = false, manual = false)
            sendBroadcast(Intent(ACTION_WAKE_ERROR).apply {
                setPackage(packageName)
                putExtra(EXTRA_ERROR_MESSAGE, "NAVIS could not start microphone listening. Check microphone permission and try again.")
            })
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!started) createSpeechManager()

        when (intent?.action) {
            ACTION_MANUAL_LISTEN -> {
                val wakeEnabled = isWakeEnabled()
                manualOnly = !wakeEnabled
                if (wakeEnabled) speechManager?.startWakeWordListening()
                speechManager?.startListening()
                if (manualOnly) updateNotification("Speak now. NAVIS will process one request.")
            }
            else -> {
                if (isWakeEnabled()) {
                    manualOnly = false
                    speechManager?.startWakeWordListening()
                } else {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createSpeechManager() {
        started = true
        speechManager = SpeechRecognitionManager(
            context = this,
            onResult = ::onRecognizedSpeech,
            onError = { message ->
                Log.w(TAG, message)
                sendBroadcast(Intent(ACTION_WAKE_ERROR).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_ERROR_MESSAGE, message)
                })
                updateNotification(message)
                if (manualOnly) stopSelf()
            },
            onStateChange = { listening ->
                manualListening = listening
                sendStatus(wakeListening, awaitingCommand, manualListening)
                if (listening) updateNotification("Speak now. NAVIS is listening.")
                else if (wakeListening) updateNotification("Say ‘Hello NAVIS’ to speak.")
            },
            onWakeWordStateChange = { listening ->
                wakeListening = listening
                sendStatus(wakeListening, awaitingCommand, manualListening)
                if (listening && !manualListening) updateNotification("Say ‘Hello NAVIS’ to speak.")
            },
            onWakeCommandPending = { awaiting ->
                awaitingCommand = awaiting
                sendStatus(wakeListening, awaitingCommand, manualListening)
                if (awaiting) updateNotification("I’m listening for your command.")
                else if (wakeListening) updateNotification("Say ‘Hello NAVIS’ to speak.")
            },
            onWakePhraseMiss = {
                updateNotification("I heard speech. Begin with ‘Hello NAVIS’.")
            },
            onWakeWordHeard = {
                sendBroadcast(Intent(ACTION_WAKE_HEARD).setPackage(packageName))
            }
        )
    }

    private fun onRecognizedSpeech(transcript: String) {
        if (transcript.isBlank()) return
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        preferences.edit()
            .putString(KEY_PENDING_COMMAND, transcript)
            .putLong(KEY_PENDING_COMMAND_TIME, System.currentTimeMillis())
            .commit()

        sendBroadcast(Intent(ACTION_WAKE_RESULT).setPackage(packageName))
        updateNotification("NAVIS heard you. Processing your request.")
        handler.removeCallbacksAndMessages(NOTIFICATION_RESET_TOKEN)
        handler.postAtTime(
            { if (started && wakeListening) updateNotification("Say ‘Hello NAVIS’ to speak.") },
            NOTIFICATION_RESET_TOKEN,
            SystemClock.uptimeMillis() + 3500L
        )
        if (manualOnly) {
            handler.postAtTime(
                { if (manualOnly) stopSelf() },
                MANUAL_STOP_TOKEN,
                SystemClock.uptimeMillis() + 700L
            )
        }
    }

    private fun sendStatus(listening: Boolean, awaiting: Boolean, manual: Boolean) {
        sendBroadcast(Intent(ACTION_WAKE_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_LISTENING, listening)
            putExtra(EXTRA_AWAITING_COMMAND, awaiting)
            putExtra(EXTRA_MANUAL_LISTENING, manual)
        })
    }

    private fun isWakeEnabled(): Boolean = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        .getBoolean(KEY_WAKE_ENABLED, true)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NAVIS microphone listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when NAVIS is listening for voice input."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopListening = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NAVIS is listening")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop listening", stopListening).build())
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        started = false
        handler.removeCallbacksAndMessages(null)
        speechManager?.stopWakeWordListening()
        speechManager = null
        wakeListening = false
        awaitingCommand = false
        manualListening = false
        sendStatus(listening = false, awaiting = false, manual = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WAKE_STATUS = "com.navis.companion.action.WAKE_STATUS"
        const val ACTION_WAKE_RESULT = "com.navis.companion.action.WAKE_RESULT"
        const val ACTION_WAKE_HEARD = "com.navis.companion.action.WAKE_HEARD"
        const val ACTION_WAKE_DISABLED = "com.navis.companion.action.WAKE_DISABLED"
        const val ACTION_WAKE_ERROR = "com.navis.companion.action.WAKE_ERROR"
        const val EXTRA_LISTENING = "listening"
        const val EXTRA_AWAITING_COMMAND = "awaiting_command"
        const val EXTRA_MANUAL_LISTENING = "manual_listening"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        private const val TAG = "NavisWakeService"
        private const val ACTION_START = "com.navis.companion.action.START_WAKE_LISTENING"
        private const val ACTION_MANUAL_LISTEN = "com.navis.companion.action.MANUAL_LISTEN"
        private const val ACTION_STOP_MANUAL = "com.navis.companion.action.STOP_MANUAL_LISTENING"
        private const val ACTION_STOP = "com.navis.companion.action.STOP_WAKE_LISTENING"
        private const val CHANNEL_ID = "navis_wake_listener"
        private const val NOTIFICATION_ID = 8104
        private const val REQUEST_OPEN = 8105
        private const val REQUEST_STOP = 8106
        private const val PREFERENCES = "navis_connection"
        private const val KEY_PENDING_COMMAND = "pending_wake_command"
        private const val KEY_PENDING_COMMAND_TIME = "pending_wake_command_time"
        private const val KEY_WAKE_ENABLED = "wake_word_enabled"
        private val NOTIFICATION_RESET_TOKEN = Any()
        private val MANUAL_STOP_TOKEN = Any()

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startManualListening(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_MANUAL_LISTEN)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopManualListening(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_STOP_MANUAL)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun consumePendingCommand(context: Context): String? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val command = preferences.getString(KEY_PENDING_COMMAND, null)
            val capturedAt = preferences.getLong(KEY_PENDING_COMMAND_TIME, 0L)
            preferences.edit()
                .remove(KEY_PENDING_COMMAND)
                .remove(KEY_PENDING_COMMAND_TIME)
                .commit()
            if (System.currentTimeMillis() - capturedAt > 120_000L) return null
            return command
        }
    }
}
