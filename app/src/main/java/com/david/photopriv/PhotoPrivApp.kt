package com.david.photopriv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.david.photopriv.data.PhotoPrivDatabase
import com.david.photopriv.data.preferences.SettingsManager
import com.david.photopriv.data.repository.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhotoPrivApp : Application() {

    companion object {
        const val CHANNEL_ID_SENTINEL = "sentinel_channel"
        lateinit var instance: PhotoPrivApp
            private set
    }

    val database by lazy { PhotoPrivDatabase.getDatabase(this) }
    val repository by lazy { PhotoRepository(database.photoDao(), this) }
    val settingsManager by lazy { SettingsManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // Sincronizar visibilidad del icono de launcher según preferencia guardada
        val prefs = settingsManager.getAppPreferences()
        settingsManager.setLauncherIconVisibility(!prefs.hideAppIcon)

        // Verificar si existe una sesión activa y garantizar que el centinela y guardianes estén vivos
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Auto-limpieza de caché y purga de archivos huérfanos/expirados de bóveda al arranque
                com.david.photopriv.util.CacheCleanerHelper.cleanAll(this@PhotoPrivApp, database.photoDao())
                repository.pruneExpiredVaultFiles()

                val active = database.photoDao().getActiveSession()
                if (active != null) {
                    val serviceIntent = android.content.Intent(this@PhotoPrivApp, com.david.photopriv.service.ExtractionSentinelService::class.java).apply {
                        action = com.david.photopriv.service.ExtractionSentinelService.ACTION_START
                        putExtra(com.david.photopriv.service.ExtractionSentinelService.EXTRA_SESSION_ID, active.sessionId)
                        putExtra(com.david.photopriv.service.ExtractionSentinelService.EXTRA_START_TIME, active.startTime)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this@PhotoPrivApp, serviceIntent)
                    com.david.photopriv.receiver.SentinelKeepAliveReceiver.scheduleKeepAlive(this@PhotoPrivApp)
                    com.david.photopriv.service.PhotoBackupWorker.scheduleMediaWatcher(this@PhotoPrivApp)
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoPrivApp", "Error restaurando centinela en onCreate: ${e.message}")
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SENTINEL,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
