package com.github.gafiatulin.parakeetflow.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.github.gafiatulin.parakeetflow.MainActivity
import com.github.gafiatulin.parakeetflow.R
import com.github.gafiatulin.parakeetflow.bubble.BubbleOverlayManager
import com.github.gafiatulin.parakeetflow.core.model.AppPhase
import com.github.gafiatulin.parakeetflow.core.preferences.PreferencesDataStore
import com.github.gafiatulin.parakeetflow.orchestrator.DictationOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DictationService : Service() {

    companion object {
        private const val TAG = "DictationService"
        const val CHANNEL_ID = "dictation_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.github.gafiatulin.parakeetflow.START"
        const val ACTION_STOP = "com.github.gafiatulin.parakeetflow.STOP"
        const val ACTION_TOGGLE_RECORDING = "com.github.gafiatulin.parakeetflow.TOGGLE"
        const val ACTION_RELOAD = "com.github.gafiatulin.parakeetflow.RELOAD"
        const val ACTION_REFRESH_BUBBLE = "com.github.gafiatulin.parakeetflow.REFRESH_BUBBLE"
    }

    @Inject lateinit var orchestrator: DictationOrchestrator
    @Inject lateinit var serviceBridge: ServiceBridge
    @Inject lateinit var bubbleOverlayManager: BubbleOverlayManager
    @Inject lateinit var preferencesDataStore: PreferencesDataStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dismissedByUser = false
    private var lastFocusedNodeClass: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observePhaseChanges()
        observeTextFieldFocus()
        Log.i(TAG, "DictationService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure foreground notification is up to satisfy the system
        startForegroundWithNotification()

        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting foreground service")
                initializeEngine()
                dismissedByUser = false
                if (serviceBridge.textFieldFocused.value) {
                    showBubble()
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping foreground service")
                orchestrator.cancelRecording()
                bubbleOverlayManager.dismiss()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_RECORDING -> {
                Log.d(TAG, "Toggle bubble")
                dismissedByUser = false
                showBubble()
            }
            ACTION_RELOAD -> {
                Log.i(TAG, "Reloading engine with new settings")
                orchestrator.cancelRecording()
                initializeEngine(force = true)
            }
            ACTION_REFRESH_BUBBLE -> {
                // Re-show the bubble so appearance changes (size/color/opacity)
                // take effect immediately, without reloading the ASR engine.
                if (bubbleOverlayManager.isShowing) {
                    bubbleOverlayManager.dismiss()
                    showBubble()
                }
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeEngine(force: Boolean = false) {
        serviceScope.launch {
            val success = orchestrator.initializeEngine(force)
            if (!success) {
                Log.e(TAG, "ASR engine initialization failed -- transcription will attempt lazy init")
            }
        }
    }

    private fun showBubble() {
        if (bubbleOverlayManager.isShowing) {
            Log.d(TAG, "showBubble: already showing, skipping")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "showBubble: overlay permission not granted, skipping")
            return
        }
        Log.d(TAG, "showBubble: showing bubble")

        serviceScope.launch {
            val settings = preferencesDataStore.settings.first()
            bubbleOverlayManager.show(
                context = this@DictationService,
                initialPosition = settings.bubblePosition,
                bubbleSizeDp = settings.bubbleSizeDp,
                bubbleColorArgb = settings.bubbleColor,
                bubbleOpacity = settings.bubbleOpacity,
                controlsBeside = settings.bubbleControlsBeside,
                onTap = { orchestrator.toggleRecording() },
                onHoldStart = { orchestrator.startRecording() },
                onHoldEnd = { orchestrator.stopRecordingAndProcess() },
                onDismiss = {
                    orchestrator.cancelRecording()
                    bubbleOverlayManager.dismiss()
                    dismissedByUser = true
                },
                onPositionChanged = { pos ->
                    serviceScope.launch {
                        preferencesDataStore.setBubblePosition(pos)
                    }
                },
                onCancel = { orchestrator.cancelRecording() },
                onPauseResume = {
                    if (serviceBridge.phase.value == AppPhase.PAUSED) {
                        orchestrator.resumeRecording()
                    } else {
                        orchestrator.pauseRecording()
                    }
                }
            )
        }
    }

    private fun observeTextFieldFocus() {
        serviceScope.launch {
            serviceBridge.textFieldFocusEvent.collect { nodeId ->
                Log.d(TAG, "Focus event: node=$nodeId, dismissed=$dismissedByUser, last=$lastFocusedNodeClass")
                if (dismissedByUser && nodeId == lastFocusedNodeClass) {
                    return@collect
                }
                dismissedByUser = false
                lastFocusedNodeClass = nodeId
                showBubble()
            }
        }
        serviceScope.launch {
            serviceBridge.textFieldFocused.collect { focused ->
                if (!focused && bubbleOverlayManager.isShowing) {
                    val phase = serviceBridge.phase.value
                    if (phase == AppPhase.RECORDING || phase == AppPhase.PAUSED || phase == AppPhase.PROCESSING || phase == AppPhase.INSERTING) {
                        Log.d(TAG, "Focus lost but pipeline active, keeping bubble")
                        return@collect
                    }
                    val lingering = preferencesDataStore.settings.first().lingeringBubble
                    if (!lingering) {
                        Log.d(TAG, "No text field focused, hiding bubble")
                        bubbleOverlayManager.dismiss()
                    }
                }
            }
        }
    }

    private fun observePhaseChanges() {
        serviceScope.launch {
            serviceBridge.phase.collect { phase ->
                bubbleOverlayManager.updatePhase(phase)

                val text = when (phase) {
                    AppPhase.IDLE -> "ParakeetFlow ready"
                    AppPhase.RECORDING -> "Recording..."
                    AppPhase.PAUSED -> "Paused"
                    AppPhase.PROCESSING -> "Processing..."
                    AppPhase.INSERTING -> if (serviceBridge.textFieldFocused.value) "Inserting text..." else "Copied to clipboard"
                    AppPhase.ERROR -> "Error occurred"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("ParakeetFlow ready")
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DictationService::class.java).apply {
                action = ACTION_TOGGLE_RECORDING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DictationService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val historyIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to", "history")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "Show",
                showIntent
            )
            .addAction(
                android.R.drawable.ic_menu_recent_history,
                "History",
                historyIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dictation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when ParakeetFlow is active and listening"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "DictationService destroyed")
        bubbleOverlayManager.dismiss()
        serviceScope.cancel()
        orchestrator.release()
        super.onDestroy()
    }
}
