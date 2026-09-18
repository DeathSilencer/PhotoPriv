package com.david.photopriv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.david.photopriv.MainActivity

/**
 * Receptor de Código Secreto telefónico.
 * Se activa cuando el usuario marca en el marcador telefónico:
 * - *#*#7468#*#* (7468 = P-H-O-T)
 * - *#*#1234#*#* (Código de respaldo numérico simple)
 * 
 * Permite abrir PhotoPriv instantáneamente incluso si el icono de la app está 100% oculto.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecretCodeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val host = intent?.data?.host ?: intent?.action ?: ""
        Log.d(TAG, "Código secreto detectado en marcador telefónico: $host")

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_FROM_SECRET_CODE", true)
        }

        try {
            context.startActivity(launchIntent)
            Log.d(TAG, "MainActivity lanzada con éxito desde código secreto telefónico.")
        } catch (e: Exception) {
            Log.e(TAG, "Error lanzando MainActivity desde código secreto: ${e.message}", e)
        }
    }
}
