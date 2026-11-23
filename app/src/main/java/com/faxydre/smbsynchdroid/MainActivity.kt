package com.faxydre.smbsynchdroid

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.faxydre.smbsynchdroid.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var uploadState: WorkInfo.State? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "SmbSynchDroid ${BuildConfigVersion.name(this)}"
        prefs = Prefs(this)

        b.spLayout.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Layout.entries.map { it.label },
        )
        fillForm(prefs.load())

        b.btnSave.setOnClickListener {
            save()
            toast("Réglages enregistrés")
        }
        b.btnTest.setOnClickListener { testConnection() }
        b.btnBrowse.setOnClickListener { browseShare() }
        b.btnSyncNow.setOnClickListener {
            if (!save()) return@setOnClickListener
            SyncScheduler.enqueueUpload(this)
            toast("Synchro lancée")
        }
        b.btnSyncAll.setOnClickListener { confirmSyncAll() }
        b.btnPerms.setOnClickListener { askPermissions(openSettingsIfDenied = true) }
        b.btnBattery.setOnClickListener { askBattery() }
        b.btnClearLog.setOnClickListener {
            LogStore.clear(this)
            refreshStatus()
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncScheduler.UPLOAD)
            .observe(this) { infos ->
                uploadState = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.state
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.state
                refreshStatus()
            }

        askPermissions(openSettingsIfDenied = false)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun fillForm(s: SmbSettings) {
        b.inHost.setText(s.host)
        b.inShare.setText(s.share)
        b.inRemoteDir.setText(s.remoteDir)
        b.inUser.setText(s.username)
        b.inPassword.setText(s.password)
        b.inDomain.setText(s.domain)
        b.inSource.setText(s.sourceDir)
        b.spLayout.setSelection(s.layout.ordinal)
        b.swWifi.isChecked = s.wifiOnly
        b.swEnabled.isChecked = s.enabled
    }

    private fun readForm(): SmbSettings {
        var host = b.inHost.text.toString().trim()
        var share = b.inShare.text.toString().trim()
        var remoteDir = b.inRemoteDir.text.toString().trim()
        // Accepte aussi un chemin complet collé dans le champ adresse : \\nas\partage\dossier
        val parts = host.removePrefix("smb:").replace('/', '\\').trim('\\').split('\\').filter { it.isNotBlank() }
        if (parts.size > 1) {
            host = parts[0]
            if (share.isEmpty()) share = parts[1]
            if (remoteDir.isEmpty() && parts.size > 2) remoteDir = parts.drop(2).joinToString("\\")
        } else if (parts.size == 1) {
            host = parts[0]
        }
        return SmbSettings(
            host = host,
            share = share.trim('\\', '/'),
            remoteDir = remoteDir,
            username = b.inUser.text.toString().trim(),
            password = b.inPassword.text.toString(),
            domain = b.inDomain.text.toString().trim(),
            sourceDir = b.inSource.text.toString().trim(),
            layout = Layout.entries[b.spLayout.selectedItemPosition.coerceAtLeast(0)],
            wifiOnly = b.swWifi.isChecked,
            enabled = b.swEnabled.isChecked,
        )
    }

    /** Enregistre et (re)programme la synchro. Retourne false si la config est incomplète. */
    private fun save(): Boolean {
        val s = readForm()
        prefs.save(s)
        fillForm(s)
        if (!s.isConfigured) {
            toast("Renseigne au moins l'adresse du NAS et le nom du partage")
            return false
        }
        // Première configuration : on part de maintenant (les anciennes photos via le bouton dédié).
        if (prefs.watermark < 0) prefs.watermark = System.currentTimeMillis() / 1000
        SyncScheduler.ensureScheduled(this)
        refreshStatus()
        return true
    }

    private fun testConnection() {
        if (!save()) return
        val s = prefs.load()
        b.btnTest.isEnabled = false
        b.btnTest.text = "Test en cours…"
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    SmbTarget.open(s).use { it.testWrite(s.remoteDir) }
                    null
                } catch (e: Exception) {
                    Errors.friendly(e)
                }
            }
            b.btnTest.isEnabled = true
            b.btnTest.text = "Tester la connexion"
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (error == null) "Connexion OK" else "Échec de la connexion")
                .setMessage(error ?: "Le NAS est joignable et le dossier de destination est accessible en écriture.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---- Navigateur de dossiers du partage ----

    /** Connexion gardée ouverte le temps de parcourir. */
    private var browser: SmbTarget? = null

    private fun browseShare() {
        val s = readForm()
        if (!s.isConfigured) {
            toast("Renseigne d'abord l'adresse du NAS et le nom du partage")
            return
        }
        b.btnBrowse.isEnabled = false
        b.btnBrowse.text = "Connexion…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SmbTarget.open(s) } }
            b.btnBrowse.isEnabled = true
            b.btnBrowse.text = "Parcourir le partage…"
            result.onSuccess {
                closeBrowser()
                browser = it
                openDir(s, RemotePaths.join(s.remoteDir))
            }.onFailure { showError("Connexion impossible", Errors.friendly(it)) }
        }
    }

    private fun openDir(s: SmbSettings, path: String) {
        val t = browser ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { t.listDirs(path) } }
            val dirs = result.getOrElse {
                if (path.isNotEmpty()) {
                    toast("Dossier « $path » introuvable, retour à la racine")
                    openDir(s, "")
                } else {
                    closeBrowser()
                    showError("Lecture du partage impossible", Errors.friendly(it))
                }
                return@launch
            }
            val hasParent = path.isNotEmpty()
            val items = buildList {
                if (hasParent) add("⬆   Dossier parent")
                dirs.forEach { add("📁   $it") }
            }
            val title = "\\\\${s.host}\\${s.share}" + (if (path.isEmpty()) "" else "\\$path")
            val builder = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(title)
                .setPositiveButton("Choisir ce dossier") { _, _ ->
                    closeBrowser()
                    b.inRemoteDir.setText(path)
                    if (save()) toast(if (path.isEmpty()) "Racine du partage choisie" else "Dossier choisi : $path")
                }
                .setNeutralButton("Nouveau dossier") { _, _ -> askNewFolder(s, path) }
                .setNegativeButton("Annuler") { _, _ -> closeBrowser() }
                .setOnCancelListener { closeBrowser() }
            if (items.isEmpty()) {
                builder.setMessage("Aucun sous-dossier.")
            } else {
                builder.setItems(items.toTypedArray()) { _, which ->
                    val next = if (hasParent && which == 0) {
                        path.substringBeforeLast('\\', "")
                    } else {
                        RemotePaths.join(path, dirs[which - if (hasParent) 1 else 0])
                    }
                    openDir(s, next)
                }
            }
            builder.show()
        }
    }

    private fun askNewFolder(s: SmbSettings, parent: String) {
        val input = android.widget.EditText(this).apply {
            hint = "Nom du dossier"
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Nouveau dossier")
            .setView(box)
            .setPositiveButton("Créer") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.any { it in "\\/:*?\"<>|" }) {
                    toast("Nom invalide")
                    openDir(s, parent)
                    return@setPositiveButton
                }
                val t = browser ?: return@setPositiveButton
                val newPath = RemotePaths.join(parent, name)
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { t.ensureDir(newPath) } }
                    r.onFailure { toast("Création impossible : ${Errors.friendly(it)}") }
                    openDir(s, if (r.isSuccess) newPath else parent)
                }
            }
            .setNegativeButton("Annuler") { _, _ -> openDir(s, parent) }
            .setOnCancelListener { openDir(s, parent) }
            .show()
    }

    private fun closeBrowser() {
        val t = browser ?: return
        browser = null
        lifecycleScope.launch(Dispatchers.IO) { t.close() }
    }

    override fun onDestroy() {
        browser?.let { t -> Thread { t.close() }.start() }
        browser = null
        super.onDestroy()
    }

    private fun showError(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmSyncAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Envoyer toutes les photos ?")
            .setMessage(
                "Toutes les photos du dossier « ${readForm().sourceDir.ifEmpty { "(toute la galerie)" }} » " +
                    "seront envoyées. Celles déjà présentes sur le NAS sont ignorées. " +
                    "Ça peut prendre du temps la première fois."
            )
            .setPositiveButton("Envoyer") { _, _ ->
                if (save()) {
                    SyncScheduler.enqueueUpload(this, 0L)
                    toast("Envoi de toutes les photos lancé")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askPermissions(openSettingsIfDenied: Boolean) {
        val missing = Perms.missing(this)
        if (missing.isEmpty()) {
            if (openSettingsIfDenied) toast("Toutes les autorisations sont accordées")
            return
        }
        if (openSettingsIfDenied && missing.none { shouldShowRequestPermissionRationale(it) } &&
            !Perms.hasPhotos(this)
        ) {
            // Refus définitif : seule la page système permet de changer d'avis.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            toast("Autorisations → Photos et vidéos → Toujours tout autoriser")
        }
        permLauncher.launch(missing)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        if (Perms.batteryUnrestricted(this)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast("Déjà non restreinte. Batterie → « Non restreinte » doit rester coché")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun refreshStatus() {
        if (!::prefs.isInitialized) return
        val s = prefs.load()
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        val lines = mutableListOf<String>()

        lines += when {
            !s.isConfigured -> "⚠️ NAS non configuré"
            s.enabled -> "✅ Synchro automatique activée"
            else -> "⏸️ Synchro automatique désactivée"
        }
        lines += if (Perms.hasPhotos(this)) "✅ Accès aux photos" else "⚠️ Accès aux photos refusé (ou limité)"
        if (!Perms.hasMediaLocation(this)) lines += "ℹ️ Position GPS retirée des photos envoyées (autorisation non accordée)"
        lines += if (Perms.batteryUnrestricted(this)) "✅ Batterie non restreinte"
        else "⚠️ Batterie optimisée : la synchro peut être retardée"

        lines += when (uploadState) {
            WorkInfo.State.RUNNING -> "⏳ Envoi en cours…"
            WorkInfo.State.ENQUEUED -> "⏳ Envoi en attente (réseau ou nouvel essai)"
            else -> "Aucun envoi en attente"
        }
        if (prefs.lastRun > 0) {
            val icon = if (prefs.lastOk) "" else "❌ "
            lines += "Dernière synchro : ${fmt.format(Date(prefs.lastRun))} — $icon${prefs.lastMessage}"
        }
        lines += "Total envoyé depuis l'installation : ${prefs.totalSent}"
        if (prefs.watermark > 0) lines += "Photos suivies depuis le : ${fmt.format(Date(prefs.watermark * 1000))}"

        b.txtStatus.text = lines.joinToString("\n")
        b.btnPerms.isEnabled = Perms.missing(this).isNotEmpty()
        b.btnBattery.text = if (Perms.batteryUnrestricted(this)) "Batterie : non restreinte ✓" else "Autoriser en arrière-plan (batterie)"
        b.txtLog.text = LogStore.tail(this)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

object BuildConfigVersion {
    fun name(ctx: android.content.Context): String =
        runCatching { "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")
}
