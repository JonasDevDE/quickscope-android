package com.jonasschnell.quickscope.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.logic.OFFLINE_DAYS
import com.jonasschnell.quickscope.logic.parseOfflinePack
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

enum class PackSyncResult { Synced, Pending, Unavailable }

/**
 * Haelt das Offline-Paket aktuell: zuerst offene Antworten senden (idempotent ueber offlineId), dann das Paket holen,
 * erst danach das lokal "Erledigte" zuruecksetzen; so gehen Antworten nie verloren und das neue Paket kennt den neuen Stand.
 * 404/Fehler sind ruhig: dann bleibt das alte Paket liegen.
 */
class OfflineSync(private val api: QuickscopeApi, private val local: LocalData, private val attempts: AttemptSync) {
    suspend fun syncExam(examId: String): PackSyncResult {
        attempts.flush(examId)
        if (attempts.hasPending(examId)) return PackSyncResult.Pending
        val text = try {
            api.offlinePack(examId, OFFLINE_DAYS).use { it.string() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            AppLog.w("Offline", "Paket nicht verfuegbar: HTTP ${e.code()}")
            return PackSyncResult.Unavailable
        } catch (e: Exception) {
            AppLog.w("Offline", "Paket nicht geladen: ${e.javaClass.simpleName}")
            return PackSyncResult.Unavailable
        }
        val pack = parseOfflinePack(text) ?: return PackSyncResult.Unavailable
        local.savePack(examId, text, pack.days)
        local.clearDone(examId)
        runCatching { local.saveTopics(examId, api.topics(examId)) }
        runCatching { local.saveForecast(examId, api.forecast(examId)) }
        return PackSyncResult.Synced
    }

    /** Alle lernbaren Pruefungen; speichert auch die Pruefungsliste fuer den Offline-Start. */
    suspend fun syncAll(): Int {
        val exams = api.exams()
        local.saveExams(exams)
        var ok = 0
        for (e in exams) if (e.status == "ready" && syncExam(e.id) == PackSyncResult.Synced) ok++
        return ok
    }
}

/** WorkManager-Arbeit: Paket-Abgleich nur mit Netz; 401 und Abmeldung beenden still. */
class PackSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as QuickscopeApp
        if (!app.auth.hasValidToken()) return Result.success()
        return try {
            app.offlineSync.syncAll()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 404 || runAttemptCount > 3) Result.success() else Result.retry()
        } catch (_: Exception) {
            if (runAttemptCount > 3) Result.success() else Result.retry()
        }
    }
}

object PackSyncScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Beim App-Start (behalten) bzw. nach jeder Runde ([replace]) einmal abgleichen, zusaetzlich alle 12 Stunden. */
    fun schedule(context: Context, replace: Boolean = false) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            val once = OneTimeWorkRequestBuilder<PackSyncWorker>().setConstraints(online).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
            wm.enqueueUniqueWork("pack-sync", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, once)
            val periodic = PeriodicWorkRequestBuilder<PackSyncWorker>(12, TimeUnit.HOURS).setConstraints(online).build()
            wm.enqueueUniquePeriodicWork("pack-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
    }
}
