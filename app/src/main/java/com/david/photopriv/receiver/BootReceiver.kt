package com.david.photopriv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.david.photopriv.PhotoPrivApp
import com.david.photopriv.service.ExtractionSentinelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receptor de Inicio del Dispositivo (Boot Receiver).
 * Si el teléfono se reinicia o se apaga y enciende mientras una sesión de protección
 * estaba activa, este receptor reactiva automáticamente el servicio Centinela en segundo plano.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d(TAG, "Dispositivo reiniciado o paquete actualizado ($action). Verificando sesión activa...")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val app = context.applicationContext as? PhotoPrivApp
                    val activeSession = app?.database?.photoDao()?.getActiveSession()
                    if (activeSession != null) {
                        Log.d(TAG, "Sesión activa encontrada (${activeSession.sessionId}). Reiniciando Centinela...")
                        val serviceIntent = Intent(context, ExtractionSentinelService::class.java).apply {
                            this.action = ExtractionSentinelService.ACTION_START
                            putExtra(ExtractionSentinelService.EXTRA_SESSION_ID, activeSession.sessionId)
                            putExtra(ExtractionSentinelService.EXTRA_START_TIME, activeSession.startTime)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Log.d(TAG, "No hay sesiones activas. Centinela permanece inactivo.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reactivando Centinela en arranque: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
