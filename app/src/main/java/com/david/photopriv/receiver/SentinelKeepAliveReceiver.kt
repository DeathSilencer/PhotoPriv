package com.david.photopriv.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.david.photopriv.PhotoPrivApp
import com.david.photopriv.service.ExtractionSentinelService
import com.david.photopriv.service.PhotoBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receptor Guardián de ultra-persistencia 24/7.
 * Se dispara mediante AlarmManager (setExactAndAllowWhileIdle) para romper el Doze Mode de Android
 * y asegurar que el servicio Centinela y el escaneo de fotos sigan activos incluso si el teléfono
 * pasa toda la noche en reposo profundo (deep sleep) o si el optimizador de Motorola mata procesos.
 */
class SentinelKeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SentinelKeepAlive"
        const val ACTION_KEEP_ALIVE = "com.david.photopriv.ACTION_KEEP_ALIVE"
        const val HEARTBEAT_INTERVAL_MS = 7 * 60 * 1000L // Cada 7 minutos

        /**
         * Programa el siguiente latido exacto del centinela con permiso para despertar de reposo profundo.
         */
        fun scheduleKeepAlive(context: Context, delayMs: Long = HEARTBEAT_INTERVAL_MS) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, SentinelKeepAliveReceiver::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1002,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = SystemClock.elapsedRealtime() + delayMs
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
                Log.d(TAG, "Latido programado con éxito para dentro de ${delayMs / 1000}s vía AlarmManager")
            } catch (e: Exception) {
                Log.e(TAG, "Error programando KeepAlive: ${e.message}", e)
            }
        }

        /**
         * Cancela cualquier latido pendiente cuando la sesión de extracción termina.
         */
        fun cancelKeepAlive(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, SentinelKeepAliveReceiver::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1002,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    Log.d(TAG, "Latidos KeepAlive cancelados correctamente.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelando KeepAlive: ${e.message}", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "⏰ Alarma KeepAlive recibida (${intent?.action}). Despertando CPU...")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhotoPriv:KeepAliveWakeLock"
        )
        // Adquirir WakeLock de seguridad durante 45 segundos para garantizar procesamiento de fondo
        wakeLock?.acquire(45_000L)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? PhotoPrivApp
                val activeSession = app?.database?.photoDao()?.getActiveSession()

                if (activeSession != null) {
                    Log.d(TAG, "Sesión activa ${activeSession.sessionId} confirmada. Verificando estado del Centinela...")

                    // 1. Asegurar que el Foreground Service esté levantado
                    val serviceIntent = Intent(context, ExtractionSentinelService::class.java).apply {
                        action = ExtractionSentinelService.ACTION_START
                        putExtra(ExtractionSentinelService.EXTRA_SESSION_ID, activeSession.sessionId)
                        putExtra(ExtractionSentinelService.EXTRA_START_TIME, activeSession.startTime)
                    }
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error arrancando foreground service desde receiver: ${e.message}", e)
                    }

                    // 2. Realizar escaneo directo e inmediato de MediaStore
                    app.repository.scanAndProcessNewPhotos(activeSession.sessionId, activeSession.startTime)

                    // 3. Despachar subida de cualquier archivo en cola
                    app.repository.dispatchImmediateUpload(activeSession.sessionId)

                    // 4. Asegurar que el vigilante de cambios de URI de WorkManager esté activo
                    PhotoBackupWorker.scheduleMediaWatcher(context)

                    // 5. Re-programar el siguiente latido para dentro de 7 minutos
                    scheduleKeepAlive(context, HEARTBEAT_INTERVAL_MS)
                } else {
                    Log.d(TAG, "No hay sesión activa en base de datos. Centinela permanece inactivo.")
                    cancelKeepAlive(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en procesamiento de KeepAlive: ${e.message}", e)
            } finally {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
