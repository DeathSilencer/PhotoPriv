package com.david.photopriv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.david.photopriv.data.PhotoPrivDatabase
import com.david.photopriv.data.preferences.SettingsManager
import com.david.photopriv.data.repository.PhotoRepository

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
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SENTINEL,
                "Sincronización del Sistema",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Servicio de sincronización en segundo plano"
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
