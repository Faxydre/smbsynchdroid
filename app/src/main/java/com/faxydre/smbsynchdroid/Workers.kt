package com.faxydre.smbsynchdroid

import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val TRIGGER = "photo-trigger"
    const val PERIODIC = "photo-periodic"
    const val UPLOAD = "photo-upload"

    /** À appeler au démarrage et après chaque changement de réglages. */
    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        val s = Prefs(context).load()
        if (!s.enabled || !s.isConfigured) {
            wm.cancelUniqueWork(TRIGGER)
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        scheduleTrigger(context, ExistingWorkPolicy.KEEP)
        // Filet de sécurité : relance une vérification toutes les heures.
        val periodic = PeriodicWorkRequestBuilder<TriggerWorker>(1, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Se déclenche dès qu'une image est ajoutée dans la galerie. */
    fun scheduleTrigger(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(10))
            .setTriggerContentMaxDelay(Duration.ofMinutes(1))
            .build()
        val req = OneTimeWorkRequestBuilder<TriggerWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, policy, req)
    }

    /**
     * Lance un envoi. [since] = DATE_ADDED de départ en secondes ;
     * null = reprendre après la dernière photo envoyée ; 0 = toutes les photos.
     */
    fun enqueueUpload(context: Context, since: Long? = null) {
        val s = Prefs(context).load()
        if (!s.isConfigured) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(UploadWorker.KEY_SINCE to (since ?: -1L)))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UPLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

/** Réveillé par une nouvelle photo (ou toutes les heures) : programme l'envoi et se réarme. */
class TriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = Prefs(applicationContext).load()
        if (s.enabled && s.isConfigured) {
            SyncScheduler.enqueueUpload(applicationContext)
            SyncScheduler.scheduleTrigger(applicationContext, ExistingWorkPolicy.REPLACE)
        }
        return Result.success()
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private enum class Outcome { SENT, ALREADY, UNREADABLE }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { runSync() }

    private fun runSync(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val s = prefs.load()
        if (!s.isConfigured) return Result.success()
        if (!Perms.hasPhotos(ctx)) {
            prefs.recordRun("Permission d'accès aux photos manquante", false)
            LogStore.add(ctx, "Permission d'accès aux photos manquante")
            Notifier.error(ctx, "Ouvre l'appli pour autoriser l'accès aux photos")
            return Result.success()
        }

        val requested = inputData.getLong(KEY_SINCE, -1L)
        val since = if (requested >= 0) requested else maxOf(0L, prefs.watermark - MARGIN_SEC)

        val photos = try {
            PhotoScanner.scan(ctx, s.sourceDir, since)
        } catch (e: Exception) {
            LogStore.add(ctx, "Lecture de la galerie impossible : ${e.message}")
            return Result.retry()
        }
        if (photos.isEmpty()) {
            prefs.recordRun("Rien de nouveau à envoyer", true)
            return Result.success()
        }

        val started = SystemClock.elapsedRealtime()
        val useOriginal = Perms.hasMediaLocation(ctx)
        var sent = 0
        var already = 0
        var unreadable = 0
        var cursor = since
        try {
            SmbTarget.open(s).use { target ->
                for (p in photos) {
                    if (isStopped) break
                    if (SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS) {
                        // WorkManager limite une exécution à 10 min : on enchaîne sur une nouvelle.
                        SyncScheduler.enqueueUpload(ctx, cursor)
                        LogStore.add(ctx, "Beaucoup de photos : la suite est programmée")
                        break
                    }
                    when (sendOne(target, s, p, useOriginal)) {
                        Outcome.SENT -> sent++
                        Outcome.ALREADY -> already++
                        Outcome.UNREADABLE -> unreadable++
                    }
                    cursor = p.dateAdded
                    prefs.advanceWatermark(cursor)
                }
            }
        } catch (e: Exception) {
            val msg = Errors.friendly(e)
            prefs.addSent(sent)
            prefs.recordRun("Échec : $msg (envoyées avant l'erreur : $sent)", false)
            LogStore.add(ctx, "Échec : $msg — nouvel essai plus tard")
            if (runAttemptCount >= 2) Notifier.error(ctx, msg)
            return Result.retry()
        }

        prefs.addSent(sent)
        val summary = buildString {
            append("$sent envoyée(s)")
            if (already > 0) append(", $already déjà présente(s)")
            if (unreadable > 0) append(", $unreadable illisible(s)")
        }
        prefs.recordRun(summary, true)
        if (sent > 0 || unreadable > 0) LogStore.add(ctx, summary)
        Notifier.clearError(ctx)
        return Result.success()
    }

    private fun sendOne(t: SmbTarget, s: SmbSettings, p: Photo, original: Boolean): Outcome {
        val dir = RemotePaths.dirFor(s, p.takenMs)
        t.ensureDir(dir)
        val dot = p.name.lastIndexOf('.')
        val base = if (dot > 0) p.name.substring(0, dot) else p.name
        val ext = if (dot > 0) p.name.substring(dot) else ""

        // Même nom déjà présent avec une autre taille : autre photo → on ajoute _1, _2…
        for (i in 0..MAX_VARIANTS) {
            val name = if (i == 0) p.name else "${base}_$i$ext"
            val path = RemotePaths.join(dir, name)
            val remote = t.remoteSize(path)
            if (remote != null) {
                if (p.size <= 0 || remote == p.size) return Outcome.ALREADY
                continue
            }
            val uri = if (original) MediaStore.setRequireOriginal(p.uri) else p.uri
            val input = try {
                applicationContext.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (input == null) {
                LogStore.add(applicationContext, "Illisible, ignorée : ${p.name}")
                return Outcome.UNREADABLE
            }
            input.use { t.upload(it, path) }
            LogStore.add(applicationContext, "Envoyée : $path")
            return Outcome.SENT
        }
        LogStore.add(applicationContext, "Trop de doublons de nom, ignorée : ${p.name}")
        return Outcome.UNREADABLE
    }

    companion object {
        const val KEY_SINCE = "since"
        /** Marge pour rattraper les photos finalisées en retard par l'appareil photo. */
        private const val MARGIN_SEC = 3600L
        private const val TIME_BUDGET_MS = 8 * 60 * 1000L
        private const val MAX_VARIANTS = 20
    }
}
