package com.david.photopriv.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.david.photopriv.MainActivity
import com.david.photopriv.PhotoPrivApp
import com.david.photopriv.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class ExtractionSentinelService : Service() {

    companion object {
        const val ACTION_START = "com.david.photopriv.START_SENTINEL"
        const val ACTION_STOP = "com.david.photopriv.STOP_SENTINEL"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_START_TIME = "extra_start_time"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "SentinelService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: ContentObserver? = null
    private var currentSessionId: Long = -1L
    private var sessionStartTime: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSentinel()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val sId = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
                val sTime = intent?.getLongExtra(EXTRA_START_TIME, 0L) ?: 0L

                if (sId != -1L && sTime != 0L) {
                    currentSessionId = sId
                    sessionStartTime = sTime
                    startSentinel()
                } else {
                    // Si no vinieron en el intent, consultar la sesión activa de la BD
                    serviceScope.launch {
                        val app = applicationContext as? PhotoPrivApp
                        val active = app?.repository?.getActiveSession()
                        if (active != null) {
                            currentSessionId = active.sessionId
                            sessionStartTime = active.startTime
                            launch(Dispatchers.Main) {
                                startSentinel()
                            }
                        } else {
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private var pollingJob: Job? = null

    private fun startSentinel() {
        Log.d(TAG, "Iniciando servicio Centinela para la sesión $currentSessionId")
        startForegroundNotification("🛡️ Centinela activo: Monitoreando fotos y videos...")
        registerObserver()

        // 1. Chequeo inicial inmediato
        triggerCheck()

        // 2. Bucle periódico de sondeo cada 2.5 segundos para capturar lotes y videos al terminar de escribirse
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                delay(2500)
                triggerCheck()
            }
        }
    }

    private fun stopSentinel() {
        Log.d(TAG, "Deteniendo servicio Centinela")
        currentSessionId = -1L  // Señaliza parada voluntaria → sin auto-restart
        pollingJob?.cancel()
        pollingJob = null
        unregisterObserver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
    }


    private fun registerObserver() {
        if (contentObserver != null) return

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Cambio detectado en MediaStore por ContentObserver: $uri")
                triggerCheck()
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
    }


    private fun unregisterObserver() {
        contentObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error desregistrando observer: ${e.message}")
            }
            contentObserver = null
        }
    }

    private fun triggerCheck() {
        if (currentSessionId == -1L || sessionStartTime == 0L) return

        serviceScope.launch {
            val app = applicationContext as? PhotoPrivApp ?: return@launch
            val repo = app.repository
            val dao = app.database.photoDao()

            // 1. Escanear nuevas fotos que salieron de la carpeta bloqueada
            repo.scanAndProcessNewPhotos(currentSessionId, sessionStartTime)

            // 2. Verificar fotos existentes para ver si ya fueron re-bloqueadas
            repo.checkProtectionStatus(currentSessionId)

            // 3. Si hay conexión Wi-Fi o red no medida, despertar archivos pesados en pausa
            if (NetworkMonitor.isWifiOrUnmetered(applicationContext)) {
                repo.dispatchImmediateUpload(currentSessionId)
            }

            // 4. Actualizar la notificación con el estado actual
            val total = dao.getTotalCount(currentSessionId).firstOrNull() ?: 0
            val protectedCount = dao.getProtectedCount(currentSessionId).firstOrNull() ?: 0
            val unprotected = total - protectedCount

            val notificationText = when {
                total == 0 -> "Esperando fotos extraídas de la Carpeta Bloqueada..."
                unprotected > 0 -> "⚠️ $unprotected foto(s) expuestas en galería pública (Total: $total, $protectedCount re-protegidas)"
                else -> "🔒 ¡Todas las $total fotos están a salvo en la Carpeta Bloqueada!"
            }

            updateNotification(notificationText)
        }
    }

    private fun startForegroundNotification(initialText: String) {
        val notification = buildNotification(initialText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val app = applicationContext as? PhotoPrivApp
        val stealthMode = app?.settingsManager?.getAppPreferences()?.stealthMode ?: true

        return if (stealthMode) {
            // Notificación ultra-sigilosa: apariencia de Servicios de Google inerte (NO abre nada al tocarla)
            NotificationCompat.Builder(this, PhotoPrivApp.CHANNEL_ID_SENTINEL)
                .setContentTitle(getString(com.david.photopriv.R.string.stealth_notif_title))
                .setContentText(getString(com.david.photopriv.R.string.stealth_notif_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
        } else {
            // Notificación explícita de PhotoPriv (solo si el usuario desactiva el modo sigilo)
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            NotificationCompat.Builder(this, PhotoPrivApp.CHANNEL_ID_SENTINEL)
                .setContentTitle("PhotoPriv - Auditor de Extracción")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterObserver()
        serviceScope.cancel()

        // Auto-resurrect: if the service was killed by the OS (not by ACTION_STOP),
        // schedule an AlarmManager wakeup to restart it in 3 seconds.
        if (currentSessionId != -1L) {
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(this, ExtractionSentinelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, currentSessionId)
                putExtra(EXTRA_START_TIME, sessionStartTime)
            }
            val pendingIntent = PendingIntent.getService(
                this,
                0,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + 3_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.d(TAG, "Auto-restart programado en 3 segundos vía AlarmManager")
        } catch (e: Exception) {
            Log.e(TAG, "Error programando auto-restart: ${e.message}")
        }
    }
}
