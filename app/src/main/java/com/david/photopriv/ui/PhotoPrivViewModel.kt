package com.david.photopriv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.david.photopriv.PhotoPrivApp
import com.david.photopriv.data.model.BackupStatus
import com.david.photopriv.data.model.ExtractionSession
import com.david.photopriv.data.model.TrackedPhoto
import com.david.photopriv.data.preferences.AppPreferences
import com.david.photopriv.data.preferences.SmtpConfig
import com.david.photopriv.data.preferences.TelegramConfig
import com.david.photopriv.network.TelegramSenderHelper
import com.david.photopriv.service.MediaStoreScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionStats(
    val total: Int = 0,
    val backedUp: Int = 0,
    val failed: Int = 0,
    val unprotected: Int = 0,
    val protected: Int = 0
)

class PhotoPrivViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PhotoPrivApp
    private val repository = app.repository
    private val settingsManager = app.settingsManager

    val activeSession: StateFlow<ExtractionSession?> = repository.activeSessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val latestSession: StateFlow<ExtractionSession?> = repository.latestSessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _smtpConfig = MutableStateFlow(settingsManager.getSmtpConfig())
    val smtpConfig: StateFlow<SmtpConfig> = _smtpConfig.asStateFlow()

    private val _telegramConfig = MutableStateFlow(settingsManager.getTelegramConfig())
    val telegramConfig: StateFlow<TelegramConfig> = _telegramConfig.asStateFlow()

    private val _appPreferences = MutableStateFlow(settingsManager.getAppPreferences())
    val appPreferences: StateFlow<AppPreferences> = _appPreferences.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()


    val sessionPhotos: StateFlow<List<TrackedPhoto>> = repository.allPhotosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        ensurePermanentSessionActive()
    }

    fun ensurePermanentSessionActive() {
        viewModelScope.launch {
            repository.ensurePermanentSessionActive()
        }
    }

    fun startExtractionSession() {
        ensurePermanentSessionActive()
    }

    fun stopExtractionSession() {
        viewModelScope.launch {
            repository.stopExtractionSession()
        }
    }

    fun refreshScan() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val session = repository.ensurePermanentSessionActive()
                repository.scanAndProcessNewPhotos(session.sessionId, session.startTime)
                repository.checkProtectionStatus(session.sessionId)
                repository.dispatchImmediateUpload(session.sessionId)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun openInGooglePhotos(photo: TrackedPhoto) {
        MediaStoreScanner.openPhotoInGooglePhotos(app, photo)
    }

    /**
     * Asistente de Re-bloqueo: Abre automáticamente el primer archivo pendiente de protección en Google Fotos.
     */
    fun openNextPendingMedia() {
        val next = sessionPhotos.value.firstOrNull { !it.isReProtected }
        if (next != null) {
            openInGooglePhotos(next)
        }
    }

    fun retryBackup() {
        viewModelScope.launch {
            val session = activeSession.value ?: latestSession.value
            if (session != null) {
                repository.retryFailedUploads(session.sessionId)
            }
        }
    }

    fun retrySinglePhoto(photo: TrackedPhoto) {
        viewModelScope.launch {
            repository.retrySinglePhoto(photo.mediaStoreId)
        }
    }

    fun dismissPhoto(photo: TrackedPhoto) {
        viewModelScope.launch {
            repository.dismissPhoto(photo.mediaStoreId)
        }
    }

    fun dismissFailedPhotos() {
        viewModelScope.launch {
            val session = activeSession.value ?: latestSession.value
            if (session != null) {
                repository.dismissFailedPhotos(session.sessionId)
            }
        }
    }

    fun saveSmtpConfig(config: SmtpConfig) {
        settingsManager.saveSmtpConfig(config)
        _smtpConfig.value = config
    }

    fun saveTelegramConfig(config: TelegramConfig) {
        settingsManager.saveTelegramConfig(config)
        _telegramConfig.value = config
    }

    fun saveAppPreferences(appPrefs: AppPreferences) {
        settingsManager.saveAppPreferences(appPrefs)
        _appPreferences.value = appPrefs
    }


    fun testTelegram(botToken: String, chatId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            when (val res = TelegramSenderHelper.sendTestMessage(botToken, chatId)) {
                is TelegramSenderHelper.SendResult.Success -> {
                    onResult(true, "¡Mensaje de prueba enviado con éxito a Telegram!")
                }
                is TelegramSenderHelper.SendResult.Error -> {
                    onResult(false, res.message)
                }
            }
        }
    }

    fun computeStats(photos: List<TrackedPhoto>): SessionStats {
        val total = photos.size
        val backedUp = photos.count { it.backupStatus == BackupStatus.BACKED_UP }
        val failed = photos.count { it.backupStatus == BackupStatus.FAILED }
        val protected = photos.count { it.isReProtected }
        val unprotected = total - protected
        return SessionStats(total, backedUp, failed, unprotected, protected)
    }
}
