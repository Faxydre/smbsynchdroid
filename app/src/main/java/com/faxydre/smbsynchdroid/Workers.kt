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

    private enum class Outcome { SENT, REPLACED, ALREADY, UNREADABLE }

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
        // Installations antérieures à la v3 : on suit les retouches à partir de maintenant.
        if (prefs.modWatermark < 0) prefs.modWatermark = System.currentTimeMillis() / 1000
        val modSince = maxOf(0L, prefs.modWatermark - MARGIN_SEC)

        val added: List<Photo>
        val edited: List<Photo>
        try {
            added = PhotoScanner.scan(ctx, s.sourceDir, since)
            // Envoi complet (since = 0) : tout est déjà dans « added ».
            edited = if (since > 0) PhotoScanner.scanModified(ctx, s.sourceDir, since, modSince) else emptyList()
        } catch (e: Exception) {
            LogStore.add(ctx, "Lecture de la galerie impossible : ${e.message}")
            return Result.retry()
        }
        if (added.isEmpty() && edited.isEmpty()) {
            prefs.recordRun("Rien de nouveau à envoyer", true)
            return Result.success()
        }

        val started = SystemClock.elapsedRealtime()
        fun overBudget() = SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS
        val useOriginal = Perms.hasMediaLocation(ctx)
        val counts = IntArray(Outcome.entries.size)
        var cursor = since
        var interrupted = false
        try {
            SmbTarget.open(s).use { target ->
                // 1) Nouvelles photos
                for (p in added) {
                    if (isStopped) { interrupted = true; break }
                    if (overBudget()) {
                        // WorkManager limite une exécution à 10 min : on enchaîne sur une nouvelle.
                        SyncScheduler.enqueueUpload(ctx, cursor)
                        LogStore.add(ctx, "Beaucoup de photos : la suite est programmée")
                        interrupted = true
                        break
                    }
                    counts[sendOne(target, s, p, useOriginal).ordinal]++
                    cursor = p.dateAdded
                    prefs.advanceWatermark(cursor)
                }
                // 2) Photos déjà envoyées puis retouchées sur la tablette
                if (!interrupted) {
                    for (p in edited) {
                        if (isStopped) break
                        if (overBudget()) {
                            SyncScheduler.enqueueUpload(ctx)
                            break
                        }
                        counts[sendOne(target, s, p, useOriginal).ordinal]++
                        prefs.advanceModWatermark(p.dateModified)
                    }
                }
            }
        } catch (e: Exception) {
            val msg = Errors.friendly(e)
            val done = counts[Outcome.SENT.ordinal] + counts[Outcome.REPLACED.ordinal]
            prefs.addSent(done)
            prefs.recordRun("Échec : $msg (envoyées avant l'erreur : $done)", false)
            LogStore.add(ctx, "Échec : $msg — nouvel essai plus tard")
            if (runAttemptCount >= 2) Notifier.error(ctx, msg)
            return Result.retry()
        }

        val sent = counts[Outcome.SENT.ordinal]
        val replaced = counts[Outcome.REPLACED.ordinal]
        val already = counts[Outcome.ALREADY.ordinal]
        val unreadable = counts[Outcome.UNREADABLE.ordinal]
        prefs.addSent(sent + replaced)
        val summary = buildString {
            append("$sent envoyée(s)")
            if (replaced > 0) append(", $replaced retouche(s) remplacée(s)")
            if (already > 0) append(", $already déjà à jour")
            if (unreadable > 0) append(", $unreadable illisible(s)")
        }
        prefs.recordRun(summary, true)
        if (sent > 0 || replaced > 0 || unreadable > 0) LogStore.add(ctx, summary)
        Notifier.clearError(ctx)
        return Result.success()
    }

    /**
     * Envoie une photo si le NAS n'en a pas déjà une copie identique (même nom, même taille).
     * Même nom mais taille différente = photo retouchée (ou homonyme) :
     *  - mode REPLACE : on remplace le fichier du NAS ;
     *  - mode KEEP_BOTH : on l'envoie à côté en nom_1.jpg, nom_2.jpg…
     */
    private fun sendOne(t: SmbTarget, s: SmbSettings, p: Photo, original: Boolean): Outcome {
        val dir = RemotePaths.dirFor(s, p.takenMs)
        t.ensureDir(dir)
        val dot = p.name.lastIndexOf('.')
        val base = if (dot > 0) p.name.substring(0, dot) else p.name
        val ext = if (dot > 0) p.name.substring(dot) else ""

        for (i in 0..MAX_VARIANTS) {
            val name = if (i == 0) p.name else "${base}_$i$ext"
            val path = RemotePaths.join(dir, name)
            val remote = t.remoteSize(path)
            if (remote != null) {
                if (p.size <= 0 || remote == p.size) return Outcome.ALREADY
                if (i == 0 && s.editMode == EditMode.REPLACE) {
                    return if (transfer(t, p, path, original, replace = true)) {
                        LogStore.add(applicationContext, "Retouche remplacée : $path")
                        Outcome.REPLACED
                    } else Outcome.UNREADABLE
                }
                continue
            }
            return if (transfer(t, p, path, original, replace = false)) {
                LogStore.add(applicationContext, "Envoyée : $path")
                Outcome.SENT
            } else Outcome.UNREADABLE
        }
        LogStore.add(applicationContext, "Trop de versions du même nom, ignorée : ${p.name}")
        return Outcome.UNREADABLE
    }

    /** Copie le fichier local vers [path]. Retourne false si le fichier local est illisible. */
    private fun transfer(t: SmbTarget, p: Photo, path: String, original: Boolean, replace: Boolean): Boolean {
        val uri = if (original) MediaStore.setRequireOriginal(p.uri) else p.uri
        val input = try {
            applicationContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
        if (input == null) {
            LogStore.add(applicationContext, "Illisible, ignorée : ${p.name}")
            return false
        }
        input.use { t.upload(it, path, replace) }
        return true
    }

    companion object {
        const val KEY_SINCE = "since"
        /** Marge pour rattraper les photos finalisées en retard par l'appareil photo. */
        private const val MARGIN_SEC = 3600L
        private const val TIME_BUDGET_MS = 8 * 60 * 1000L
        private const val MAX_VARIANTS = 20
    }
}
