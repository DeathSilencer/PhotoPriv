package com.david.photopriv.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.david.photopriv.PhotoPrivApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_SESSION_ID = "key_session_id"
        private const val TAG = "PhotoBackupWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? PhotoPrivApp ?: return@withContext Result.failure()
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        val targetSessionId = if (sessionId != -1L) {
            sessionId
        } else {
            app.database.photoDao().getActiveSession()?.sessionId ?: -1L
        }

        if (targetSessionId == -1L) {
            Log.d(TAG, "No hay sesión activa para respaldar.")
            return@withContext Result.success()
        }

        Log.d(TAG, "WorkManager delegando respaldo centralizado para sesión $targetSessionId...")
        app.repository.dispatchImmediateUpload(targetSessionId)
        Result.success()
    }
}
